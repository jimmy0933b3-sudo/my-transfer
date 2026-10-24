// Azure Translator proxy for CameraTranslator.
// Never put the Azure subscription key or the app access token in Git or in an APK.
const languages = new Set(["auto", "en", "ja", "zh", "th"]);
const MAX_CHARACTERS = 1500;
const MAX_BODY_BYTES = 6000;
const MAX_BATCH_BODY_BYTES = 24000;
const MAX_BATCH_ITEMS = 6;
const MAX_BATCH_CHARS = 6000;
const UPSTREAM_TIMEOUT_MS = 8000;
const WINDOW_MS = 60_000;
const MAX_REQUESTS_PER_WINDOW = 25;
// Best-effort per-isolate rate limit. This does NOT replace a globally
// enforced account-level limit or Cloudflare Rate Limiting rules.
const recentRequests = new Map();

function reply(data, status = 200) {
  return Response.json(data, {
    status,
    headers: { "cache-control": "no-store", "x-content-type-options": "nosniff" }
  });
}

function equalsConstantTime(a, b) {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  if (x.length !== y.length) return false;
  let difference = 0;
  for (let i = 0; i < x.length; i++) difference |= x[i] ^ y[i];
  return difference === 0;
}

function checkRateLimit(ip, now = Date.now()) {
  if (recentRequests.size > 1500) {
    for (const [address, record] of recentRequests) {
      if (record.expires <= now) recentRequests.delete(address);
    }
  }
  const prior = recentRequests.get(ip);
  if (!prior || prior.expires <= now) {
    recentRequests.set(ip, { count: 1, expires: now + WINDOW_MS });
    return true;
  }
  if (prior.count >= MAX_REQUESTS_PER_WINDOW) return false;
  prior.count++;
  return true;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return reply({
        ok: true,
        service: "camera-translator-api",
        configured: Boolean(env.AZURE_TRANSLATOR_KEY &&
          env.AZURE_TRANSLATOR_REGION &&
          env.APP_ACCESS_TOKEN && env.APP_ACCESS_TOKEN.length >= 32)
      });
    }
    const batch = url.pathname === "/translate-batch";
    if (!batch && url.pathname !== "/translate") return reply({ error: "Not found" }, 404);
    if (request.method !== "POST") return reply({ error: "Method not allowed" }, 405);
    if (!env.AZURE_TRANSLATOR_KEY || !env.AZURE_TRANSLATOR_REGION ||
        !env.APP_ACCESS_TOKEN || env.APP_ACCESS_TOKEN.length < 32) {
      return reply({ error: "Service not configured" }, 503);
    }
    const supplied = request.headers.get("authorization") || "";
    const token = supplied.startsWith("Bearer ") ? supplied.slice(7) : "";
    if (!equalsConstantTime(token, env.APP_ACCESS_TOKEN)) {
      return reply({ error: "Unauthorized" }, 401);
    }
    if (request.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase() !== "application/json") {
      return reply({ error: "JSON required" }, 415);
    }
    const declaredLength = Number(request.headers.get("content-length") || 0);
    const bodyLimit = batch ? MAX_BATCH_BODY_BYTES : MAX_BODY_BYTES;
    if (declaredLength > bodyLimit) return reply({ error: "Request too large" }, 413);
    const raw = await request.text();
    if (new TextEncoder().encode(raw).length > bodyLimit) {
      return reply({ error: "Request too large" }, 413);
    }
    let body;
    try { body = JSON.parse(raw); } catch { return reply({ error: "Invalid JSON" }, 400); }
    const from = body?.from;
    const to = body?.to;
    // Azure Translator accepts multiple Text entries in one request.
    // A bounded batch reduces Worker calls without concatenating unrelated OCR
    // blocks, so Azure returns each block in the original order.
    const texts = batch ? body?.texts : [body?.text];
    if (!languages.has(from) || !languages.has(to) || to === "auto" ||
        !Array.isArray(texts) || texts.length < 1 ||
        texts.length > (batch ? MAX_BATCH_ITEMS : 1) ||
        texts.some(text => typeof text !== "string" || !text.trim() ||
          [...text].length > MAX_CHARACTERS) ||
        (batch && texts.reduce((sum, text) => sum + [...text].length, 0) > MAX_BATCH_CHARS)) {
      return reply({ error: "Invalid language or text(s)" }, 400);
    }
    if (from === to) {
      return reply(batch ? { translations: texts } : { text: texts[0] });
    }
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    if (!checkRateLimit(ip)) return reply({ error: "Too many requests" }, 429);
    // Restrict target to the known Azure endpoint. Never proxy an arbitrary URL.
    const api = new URL("https://api.cognitive.microsofttranslator.com/translate");
    api.searchParams.set("api-version", "3.0");
    api.searchParams.set("to", to === "zh" ? "zh-Hant" : to);
    if (from !== "auto") api.searchParams.set("from", from === "zh" ? "zh-Hant" : from);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
    try {
      const upstream = await fetch(api.toString(), {
        method: "POST",
        headers: {
          "content-type": "application/json; charset=UTF-8",
          "Ocp-Apim-Subscription-Key": env.AZURE_TRANSLATOR_KEY,
          "Ocp-Apim-Subscription-Region": env.AZURE_TRANSLATOR_REGION
        },
        body: JSON.stringify(texts.map(text => ({ Text: text }))),
        signal: controller.signal
      });
      if (!upstream.ok) {
        // Do not return the upstream response body, which can contain details
        // about internal account state. Fallback is handled on the device.
        return reply({ error: "Translation unavailable", upstreamStatus: upstream.status },
          upstream.status === 429 ? 429 : 502);
      }
      const response = await upstream.json();
      if (!Array.isArray(response) || response.length !== texts.length) {
        return reply({ error: "Translation response mismatch" }, 502);
      }
      const translations = response.map(item => item?.translations?.[0]?.text);
      if (translations.some(text => typeof text !== "string" || !text.trim())) {
        return reply({ error: "Empty translation" }, 502);
      }
      const characters = texts.reduce((sum, text) => sum + [...text].length, 0);
      return reply(batch
        ? { translations, characters }
        : { text: translations[0], characters });
    } catch {
      return reply({ error: "Translation timed out or is unreachable" }, 504);
    } finally {
      clearTimeout(timeout);
    }
  }
};
