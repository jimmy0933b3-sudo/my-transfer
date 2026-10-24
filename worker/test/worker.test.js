import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

const env = {
  AZURE_TRANSLATOR_KEY: "mock-test-azure-key",
  AZURE_TRANSLATOR_REGION: "eastasia",
  APP_ACCESS_TOKEN: "unit-test-token-123456789012345678901234567890"
};

function req(path, token, body) {
  return new Request("https://camera-translator-api.jimmy0933.workers.dev" + path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {})
    },
    body: JSON.stringify(body ?? { text: "Hello", from: "en", to: "zh" })
  });
}

test("unconfigured Worker rejects calls (fail closed)", async () => {
  const res = await worker.fetch(req("/translate", env.APP_ACCESS_TOKEN), {});
  assert.equal(res.status, 503);
});

test("health shows only configuration status", async () => {
  const res = await worker.fetch(
    new Request("https://camera-translator-api.jimmy0933.workers.dev/health"), env
  );
  const body = await res.json();
  assert.equal(res.status, 200);
  assert.equal(body.configured, true);
  assert.equal(JSON.stringify(body).includes(env.AZURE_TRANSLATOR_KEY), false);
});

test("unauthorized calls must not reach Azure", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; throw Error("Should not call Azure"); };
  try {
    const res = await worker.fetch(req("/translate", "wrong-token"), env);
    assert.equal(res.status, 401);
    assert.equal(calls, 0);
  } finally { globalThis.fetch = original; }
});

test("authenticated call maps locale and returns translated text", async () => {
  const original = globalThis.fetch;
  let passed = false;
  globalThis.fetch = async (url, options) => {
    passed = true;
    url = new URL(url);
    assert.equal(url.searchParams.get("to"), "zh-Hant");
    assert.equal(url.searchParams.get("from"), "en");
    assert.equal(options.headers["Ocp-Apim-Subscription-Region"], "eastasia");
    assert.equal(options.headers["Ocp-Apim-Subscription-Key"], env.AZURE_TRANSLATOR_KEY);
    assert.equal(JSON.parse(options.body)[0].Text, "Hello");
    return Response.json([{ translations: [{ text: "你好", to: "zh-Hant" }] }]);
  };
  try {
    const res = await worker.fetch(req("/translate", env.APP_ACCESS_TOKEN), env);
    assert.equal(res.status, 200);
    assert.equal((await res.json()).text, "你好");
    assert.equal(passed, true);
  } finally { globalThis.fetch = original; }
});

test("invalid and oversized requests are rejected before Azure", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; throw Error("Should not call Azure"); };
  try {
    const bad = await worker.fetch(req("/translate", env.APP_ACCESS_TOKEN,
      { text: "hello", from: "en", to: "bogus" }), env);
    assert.equal(bad.status, 400);
    const large = await worker.fetch(req("/translate", env.APP_ACCESS_TOKEN,
      { text: "a".repeat(1501), from: "en", to: "zh" }), env);
    assert.equal(large.status, 400);
    assert.equal(calls, 0);
  } finally { globalThis.fetch = original; }
});


test("cloud OCR endpoint is disabled and never receives photos", async () => {
  const original = globalThis.fetch;
  let forwarded = 0;
  globalThis.fetch = async () => { forwarded++; throw Error("No OCR upstream allowed"); };
  try {
    const res = await worker.fetch(req("/ocr", env.APP_ACCESS_TOKEN,
      {language:"ja", image_base64:Buffer.from("photo").toString("base64")}), env);
    assert.equal(res.status, 404);
    assert.equal(forwarded, 0);
  } finally { globalThis.fetch = original; }
});



test("batch returns aligned translations with one Azure request", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async (url, options) => {
    calls++;
    const u = new URL(url);
    assert.equal(u.searchParams.get("from"), "ja");
    assert.equal(u.searchParams.get("to"), "zh-Hant");
    assert.deepEqual(JSON.parse(options.body), [
      { Text: "こんにちは" }, { Text: "ありがとう" }, { Text: "さようなら" }
    ]);
    return Response.json([
      { translations: [{ text: "你好" }] },
      { translations: [{ text: "謝謝" }] },
      { translations: [{ text: "再見" }] }
    ]);
  };
  try {
    const res = await worker.fetch(req("/translate-batch", env.APP_ACCESS_TOKEN, {
      texts: ["こんにちは", "ありがとう", "さようなら"],
      from: "ja", to: "zh"
    }), env);
    assert.equal(res.status, 200);
    assert.deepEqual((await res.json()).translations, ["你好", "謝謝", "再見"]);
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});

test("batch validates item count, content, size, and authentication before Azure", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; throw Error("Must reject before Azure"); };
  try {
    const badInputs = [
      { texts: [], from: "ja", to: "zh" },
      { texts: Array(7).fill("こんにちは"), from: "ja", to: "zh" },
      { texts: ["a".repeat(1501)], from: "en", to: "zh" },
      { texts: ["hello", ""], from: "en", to: "zh" },
      { texts: ["hello", 1], from: "en", to: "zh" },
      { texts: ["Hello"], from: "en", to: "auto" }
    ];
    for (const body of badInputs) {
      const res = await worker.fetch(req("/translate-batch", env.APP_ACCESS_TOKEN, body), env);
      assert.equal(res.status, 400);
    }
    assert.equal((await worker.fetch(req("/translate-batch", "bad", {
      texts: ["hello"], from: "en", to: "zh"
    }), env)).status, 401);
    assert.equal((await worker.fetch(req("/translate-batch", env.APP_ACCESS_TOKEN, {
      texts: ["a".repeat(1500), "b".repeat(1500), "c".repeat(1500),
        "d".repeat(1500), "e".repeat(1500)], from: "en", to: "zh"
    }), env)).status, 400);
    assert.equal(calls, 0);
  } finally { globalThis.fetch = original; }
});

test("batch rejects Azure response size mismatch without returning unaligned text", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => Response.json([
    { translations: [{ text: "你好" }] }
  ]);
  try {
    const res = await worker.fetch(req("/translate-batch", env.APP_ACCESS_TOKEN, {
      texts: ["hello", "world"], from: "en", to: "zh"
    }), env);
    assert.equal(res.status, 502);
  } finally { globalThis.fetch = original; }
});

test("batch same-language short circuits without contacting Azure", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => { throw Error("Must not translate equal languages"); };
  try {
    const res = await worker.fetch(req("/translate-batch", env.APP_ACCESS_TOKEN, {
      texts: ["繁體中文", "Hello"], from: "zh", to: "zh"
    }), env);
    assert.equal(res.status, 200);
    assert.deepEqual((await res.json()).translations, ["繁體中文", "Hello"]);
  } finally { globalThis.fetch = original; }
});
