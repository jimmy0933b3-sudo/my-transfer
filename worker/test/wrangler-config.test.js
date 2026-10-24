import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const workerConfig = readFileSync(new URL("../wrangler.toml", import.meta.url), "utf8");
const rootConfig = readFileSync(new URL("../../wrangler.toml", import.meta.url), "utf8");

function value(toml, key) {
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const found = toml.match(new RegExp("^" + escaped + "\\s*=\\s*(.+)$", "m"));
  return found?.[1]?.trim();
}

test("both Cloudflare build root configurations allow Worker Previews", () => {
  for (const config of [workerConfig, rootConfig]) {
    assert.equal(value(config, "previews"), "{}", "wrangler preview requires previews = {}");
    assert.equal(value(config, "name"), '"camera-translator-api"');
    assert.equal(value(config, "compatibility_date"), '"2026-10-01"');
    assert.equal(value(config, "workers_dev"), "true");
    assert.equal(value(config, "AZURE_TRANSLATOR_KEY"), undefined);
    assert.equal(value(config, "APP_ACCESS_TOKEN"), undefined);
  }
});

test("Worker entry points resolve for both monorepo root directories", () => {
  assert.equal(value(workerConfig, "main"), '"src/index.js"');
  assert.equal(value(rootConfig, "main"), '"worker/src/index.js"');
});
