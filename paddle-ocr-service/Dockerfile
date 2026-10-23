FROM python:3.11-slim
ENV PYTHONUNBUFFERED=1 PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True
WORKDIR /srv
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 libglib2.0-0 libgl1 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
EXPOSE 8080
CMD ["uvicorn","app:app","--host","0.0.0.0","--port","8080","--workers","1"]
