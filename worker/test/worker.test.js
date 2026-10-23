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
