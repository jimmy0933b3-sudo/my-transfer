// Azure Translator proxy for CameraTranslator.
// Never put the Azure subscription key or the app access token in Git or in an APK.
const languages = new Set(["auto", "en", "ja", "zh", "th"]);
const MAX_CHARACTERS = 1500;
const MAX_BODY_BYTES = 6000;
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
        paddleOcrConfigured: Boolean(env.PADDLE_OCR_UPSTREAM_URL &&
          env.PADDLE_OCR_UPSTREAM_TOKEN && env.PADDLE_OCR_UPSTREAM_TOKEN.length >= 32),
        configured: Boolean(env.AZURE_TRANSLATOR_KEY &&
          env.AZURE_TRANSLATOR_REGION &&
          env.APP_ACCESS_TOKEN && env.APP_ACCESS_TOKEN.length >= 32)
      });
    }
    // PP-OCRv5 inference requires a separate Python/CPU/GPU service. This
    // Worker authenticates the app and proxies the image without saving it.
    if (url.pathname === "/ocr") {
      if (request.method !== "POST") return reply({error:"Method not allowed"},405);
      if (!env.APP_ACCESS_TOKEN || env.APP_ACCESS_TOKEN.length < 32 ||
          !env.PADDLE_OCR_UPSTREAM_URL ||
          !env.PADDLE_OCR_UPSTREAM_TOKEN ||
          env.PADDLE_OCR_UPSTREAM_TOKEN.length < 32) {
        return reply({error:"PaddleOCR upstream not configured"},503);
      }
      const supplied = request.headers.get("authorization") || "";
      const token = supplied.startsWith("Bearer ") ? supplied.slice(7) : "";
      if (!equalsConstantTime(token, env.APP_ACCESS_TOKEN)) {
        return reply({error:"Unauthorized"},401);
      }
      if (request.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase()
        !== "application/json") return reply({error:"JSON required"},415);
      let endpoint;
      try {
        endpoint = new URL(env.PADDLE_OCR_UPSTREAM_URL);
        if (endpoint.protocol !== "https:" || endpoint.username ||
            endpoint.password || endpoint.pathname !== "/ocr") throw Error("unsafe");
      } catch {
        return reply({error:"Invalid OCR service configuration"},503);
      }
      const declared = Number(request.headers.get("content-length") || 0);
      if (declared > 3_500_000) return reply({error:"Image too large"},413);
      const raw = await request.text();
      if (raw.length > 3_500_000) return reply({error:"Image too large"},413);
      let payload;
      try { payload = JSON.parse(raw); } catch { return reply({error:"Invalid JSON"},400); }
      if (!["auto","en","ja","zh","th"].includes(payload?.language) ||
          typeof payload?.image_base64 !== "string" ||
          payload.image_base64.length < 100 ||
          payload.image_base64.length > 3_400_000 ||
          !/^[A-Za-z0-9+/]+={0,2}$/.test(payload.image_base64)) {
        return reply({error:"Invalid OCR image or language"},400);
      }
      const ip = request.headers.get("cf-connecting-ip") || "unknown";
      if (!checkRateLimit(ip)) return reply({error:"Too many requests"},429);
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 30_000);
      try {
        const upstream = await fetch(endpoint.href, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Authorization": "Bearer " + env.PADDLE_OCR_UPSTREAM_TOKEN
          },
          body: JSON.stringify(payload),
          signal: controller.signal
        });
        if (!upstream.ok) {
          return reply({error:"PaddleOCR is unavailable"}, upstream.status===429?429:502);
        }
        const result = await upstream.json();
        if (result?.engine !== "PP-OCRv5" || !Array.isArray(result.blocks) ||
            !Number.isInteger(result.width) || !Number.isInteger(result.height) ||
            result.width < 1 || result.height < 1) {
          return reply({error:"Invalid upstream OCR response"},502);
        }
        const blocks = result.blocks.slice(0,45).filter(b =>
          typeof b.text === "string" && b.text.length <= 1500 &&
          ["en","ja","zh","th"].includes(b.language) &&
          Number.isFinite(b.score) &&
          Array.isArray(b.box) && b.box.length === 4 &&
          b.box.every(Number.isFinite)
        );
        return reply({engine:"PP-OCRv5",width:result.width,
          height:result.height,blocks});
      } catch {
        return reply({error:"PaddleOCR timeout or connection error"},504);
      } finally {
        clearTimeout(timeout);
      }
    }
    if (url.pathname !== "/translate") return reply({ error: "Not found" }, 404);
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
    if (declaredLength > MAX_BODY_BYTES) return reply({ error: "Request too large" }, 413);
    const raw = await request.text();
    if (new TextEncoder().encode(raw).length > MAX_BODY_BYTES) {
      return reply({ error: "Request too large" }, 413);
    }
    let body;
    try { body = JSON.parse(raw); } catch { return reply({ error: "Invalid JSON" }, 400); }
    const from = body?.from;
    const to = body?.to;
    const text = body?.text;
    if (!languages.has(from) || !languages.has(to) ||
        to === "auto" || typeof text !== "string" ||
        text.trim().length < 1 || [...text].length > MAX_CHARACTERS) {
      return reply({ error: "Invalid language or text (maximum 1500 characters)" }, 400);
    }
    if (from === to) return reply({ text });
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
        body: JSON.stringify([{ Text: text }]),
        signal: controller.signal
      });
      if (!upstream.ok) {
        // Do not return the upstream response body, which can contain details
        // about internal account state. Fallback is handled on the device.
        return reply({ error: "Translation unavailable", upstreamStatus: upstream.status },
          upstream.status === 429 ? 429 : 502);
      }
      const response = await upstream.json();
      const translation = response?.[0]?.translations?.[0]?.text;
      if (typeof translation !== "string" || !translation.trim()) {
        return reply({ error: "Empty translation" }, 502);
      }
      return reply({ text: translation, characters: [...text].length });
    } catch {
      return reply({ error: "Translation timed out or is unreachable" }, 504);
    } finally {
      clearTimeout(timeout);
    }
  }
};
