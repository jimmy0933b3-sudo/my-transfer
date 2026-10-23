# PP-OCRv5 OCR service (optional cloud OCR)

The photo renderer, original/translated toggle, gallery save/share and Azure Translator already ship in the Android app. This service supplies actual PP-OCRv5 OCR polygons; it is **not hosted inside a Cloudflare Worker**.

## Deploy

Requires your own VM/container hosting (CPU/GPU) with outbound model-download access. **Hosting may cost money** and Azure Translator F0 does not pay for OCR hosting.

```bash
docker build -t camera-paddle-ocr .
docker run -d --restart unless-stopped -p 127.0.0.1:8080:8080 \
  -e PADDLE_OCR_SERVICE_TOKEN='YOUR_LONG_RANDOM_SECRET' camera-paddle-ocr
```

Expose it behind an HTTPS reverse proxy. The model downloads automatically on first inference and may take significant disk/RAM. Use a **different token** from Azure and APP_ACCESS_TOKEN. Do not open the underlying HTTP port publicly.

Set the following secrets on the **existing** Cloudflare Worker `camera-translator-api`:
- `PADDLE_OCR_UPSTREAM_URL` (Text or Secret): `https://your-ocr-host.example.com/ocr`.
- `PADDLE_OCR_UPSTREAM_TOKEN` (Secret): same value as the Docker service `PADDLE_OCR_SERVICE_TOKEN`.

Keep existing `AZURE_TRANSLATOR_KEY`, `AZURE_TRANSLATOR_REGION` and `APP_ACCESS_TOKEN`. Cloudflare GitHub Builds root directory remains `worker`.

Android photo OCR order when cloud enabled: **PP-OCRv5 → local ML Kit/Tesseract fallback**, then **Azure Translator → ML Kit translation fallback**. Offline mode never uploads photos. App UI displays actual OCR provider; if the Paddle service isn't configured, **it has NOT executed PaddleOCR**.

### Security

- The image upload is opt-in via App settings (Paddle OCR preferred), never sent if offline mode is selected. An authenticated upload contains the **photo JPEG**, unlike text-only Azure Translator calls.
- Keep upstream service private/TLS and enforce quotas, access restrictions and storage limits. Neither Worker code nor API stores photos intentionally.
- A Worker per-isolate rate limit does not guarantee a global quota; implement account-level usage alarms and edge rate limiting for a public service.

## Model

PaddleOCR 3.x `PaddleOCR(lang=..., ocr_version="PP-OCRv5")` supports Chinese, Japanese, English and Thai, with `rec_polys` / `rec_texts`. Thai uses the PP-OCRv5 multilingual recognition model, not a Latin model. Test runtime image quality on your deployment host before production.

Docs: https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/OCR.html
