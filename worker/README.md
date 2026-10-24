# Camera Translator / Cloudflare Worker

This is the backend for the Android app under `../CameraTranslator`.

## Cloudflare Builds settings (existing Worker)

In **camera-translator-api** → Settings → Builds:

- Git repository: `jimmy0933b3-sudo/my-transfer`
- Production branch: `master`
- **Root directory: `worker`** (crucial for this Android monorepo)
- Build command: blank, or `npm install`
- Deploy command: `npx wrangler deploy`
- Wrangler name must remain `camera-translator-api`.

Cloudflare deploys this Worker on a push to the linked branch. Do not embed the Azure key in the repository.

## Worker runtime Secrets

Set these under Worker → Settings → Variables and Secrets:

| Secret | Value |
|---|---|
| `AZURE_TRANSLATOR_KEY` | Azure Translator Key 1 (SECRET) |
| `AZURE_TRANSLATOR_REGION` | `eastasia` (Text or Secret) |
| `APP_ACCESS_TOKEN` | **New**, random secret of at least 32 characters (SECRET) |

The App token is deliberately not bundled with the APK. Enter the same token once in the Android App **Settings → 雲端翻譯**. It is stored on-device using Android Keystore encryption.

If `APP_ACCESS_TOKEN` is absent, the Worker **refuses** translation requests. `GET /health` reports if setup is complete without exposing secrets. Sending a POST without a matching Bearer token returns 401.

### Example API

`POST /translate`, `Authorization: Bearer <APP_ACCESS_TOKEN>`, `Content-Type: application/json`

```json
{"text":"Hello world","from":"en","to":"zh"}
```

Response: `{"text":"你好，世界","characters":11}` (actual translation may vary).

## Safety and cost

- Only plain OCR text is sent; camera images are never uploaded to this Worker.
- Text length capped at 1,500 Unicode characters per call, and one translation target per request.
- Bearer authorization is required before any upstream Azure request.
- Basic rate limiting is **per Worker isolate only**; it is not a global hard ceiling and can reset when the isolate restarts. For globally guaranteed limits configure Cloudflare Rate Limiting / a Durable Object and account-level Azure usage alerts.
- Android deduplicates recognized paragraphs, caches translations, limits concurrent requests, and falls back offline.
- A free-tier Azure F0 resource can deny calls after its free quota; don't upgrade it to paid S1 if zero translation fees are a requirement.
- No Azure key and no app token must ever enter GitHub commits or APK source.

## Validation

`node --test` validates authentication, configuration, input checks, and an Azure response with an intercepted network request. CI also builds the Android APK with the unchanged fixed-signature signing mechanism.

## OCR policy

Photo OCR is exclusively local in the Android app (Paddle v5/v6, ML Kit or Thai Tesseract).
The Worker only accepts OCR **text** for Azure translation through POST /translate.
POST /ocr has been permanently removed and returns 404. Images are never uploaded.
