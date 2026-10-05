# 운영 이미지. 실행은 deploy/compose.yaml 에서 한다.
# 외부에 포트를 열지 않는다 — Spring 만 내부 Docker 네트워크로 호출한다(docs/DEPLOYMENT.md 5절).

FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY . .
RUN useradd --system --no-create-home app
USER app
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
