"""PP-OCRv5 photo OCR service. Self-host behind TLS and Cloudflare Workers.

This is not run inside Cloudflare Workers: PaddlePaddle requires a CPU/GPU host.
"""
import base64
import hmac
import io
import os
import threading
from functools import lru_cache

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="Camera Translator PP-OCRv5", docs_url=None, redoc_url=None)
_inference_lock = threading.Lock()
LANGUAGES = {"auto": "ch", "en": "en", "zh": "chinese_cht", "ja": "japan", "th": "th"}


class OcrRequest(BaseModel):
    image_base64: str = Field(min_length=50, max_length=3_400_000)
    language: str = "auto"


def normalize_result(result, lang: str, width: int, height: int):
    """Normalize PP-OCRv5 polygons to bounded photo pixel rectangles."""
    from numpy import asarray

    blocks = []
    for item in result:
        data = item.json
        if callable(data):
            data = data()
        data = data.get("res", data)
        texts = data.get("rec_texts", [])
        scores = data.get("rec_scores", [])
        polygons = data.get("rec_polys", [])
        for i, text in enumerate(texts):
            if not isinstance(text, str) or not text.strip() or i >= len(polygons):
                continue
            score = float(scores[i]) if i < len(scores) else 0.0
            if score < 0.45:
                continue
            polygon = asarray(polygons[i])
            if polygon.size < 8:
                continue
            xs = polygon[:, 0]
            ys = polygon[:, 1]
            x1, y1 = max(0, int(xs.min())), max(0, int(ys.min()))
            x2, y2 = min(width, int(xs.max())), min(height, int(ys.max()))
            if x2 - x1 < 6 or y2 - y1 < 6:
                continue
            value = text.strip()
            if any("\u0e00" <= char <= "\u0e7f" for char in value):
                language = "th"
            elif any("\u3040" <= char <= "\u30ff" for char in value):
                language = "ja"
            elif any("\u3400" <= char <= "\u9fff" for char in value):
                language = "zh" if lang != "ja" else "ja"
            else:
                language = "en"
            blocks.append({"text": value[:1500], "box": [x1, y1, x2, y2],
                           "score": score, "language": language})
    return blocks[:120]


@lru_cache(maxsize=5)
def pipeline(language: str):
    from paddleocr import PaddleOCR
    return PaddleOCR(
        lang=LANGUAGES[language],
        ocr_version="PP-OCRv5",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        device=os.getenv("PADDLE_DEVICE", "cpu"),
    )


@app.get("/health")
def health():
    return {"ok": True, "engine": "PP-OCRv5"}


@app.post("/ocr")
def recognize(body: OcrRequest, authorization: str = Header(default="")):
    secret = os.getenv("PADDLE_OCR_SERVICE_TOKEN", "")
    if len(secret) < 32:
        raise HTTPException(503, "OCR service not configured")
    if not authorization.startswith("Bearer ") or not hmac.compare_digest(
        authorization[7:], secret
    ):
        raise HTTPException(401, "Unauthorized")
    if body.language not in LANGUAGES:
        raise HTTPException(400, "Unsupported language")
    try:
        raw = base64.b64decode(body.image_base64, validate=True)
        if not (100 < len(raw) <= 2_500_000):
            raise ValueError("Image length out of bounds")
        import cv2
        import numpy as np
        image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Invalid image")
        height, width = image.shape[:2]
        if width <= 0 or height <= 0 or width * height > 12_000_000:
            raise ValueError("Invalid image dimensions")
    except (ValueError, base64.binascii.Error):
        raise HTTPException(400, "Invalid JPEG payload")

    # PaddleOCR predictors may not be thread-safe; serialize a single service
    # process and scale with separate workers/replicas only as memory allows.
    with _inference_lock:
        result = pipeline(body.language).predict(image)
        blocks = normalize_result(result, body.language, width, height)
    return {"engine": "PP-OCRv5", "width": width, "height": height, "blocks": blocks}
