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

## PP-OCRv5 image recognition (optional)

The App includes photo text-replacement rendering. Its **primary cloud OCR** is PP-OCRv5 via `POST /ocr`; translation still uses the existing Azure F0 Text Translator via `POST /translate`. Running a PP-OCRv5 predictor requires a separate self-hosted CPU/GPU container. See [paddle-ocr-service/README.md](../paddle-ocr-service/README.md).

Add Worker variables:
- `PADDLE_OCR_UPSTREAM_URL` = `https://your-ocr-server.example.com/ocr`
- `PADDLE_OCR_UPSTREAM_TOKEN` = separate Secret shared with container `PADDLE_OCR_SERVICE_TOKEN`, length >=32

After deployment, `GET /health` returns `paddleOcrConfigured:true`; this only verifies settings exist, **not** that inference successfully ran. Android explicitly shows `PaddleOCR PP-OCRv5 雲端` only after a successful PP-OCRv5 response. If unavailable, it shows `ML Kit 本機 OCR` or `Tesseract 泰文離線` instead. In App-only offline mode no photo is uploaded. PP-OCRv5 compute hosting may incur separate costs.
