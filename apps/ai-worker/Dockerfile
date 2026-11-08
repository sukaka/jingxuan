FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /app
COPY apps/ai-worker/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt \
    && groupadd --system worker \
    && useradd --system --gid worker --home-dir /app --shell /usr/sbin/nologin worker
COPY apps/ai-worker/app /app/app
USER worker
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=8 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=3)" || exit 1
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090", "--workers", "1"]
