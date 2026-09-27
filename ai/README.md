# AI

Gitory AI 서버의 독립 실행 루트입니다. A(LLM 분석)와 B(Agent/RAG) 코드를
이 디렉터리에서 함께 관리합니다.

## 구조

```text
ai/
├── api/
├── rag/
├── schemas/
├── services/
├── tests/
├── main.py
├── requirements.txt
└── requirements-dev.txt
```

## 로컬 실행

저장소 루트에서 아래와 같이 실행합니다.

```bash
cd ai
python -m pip install -r requirements-dev.txt
python -m pytest -q
uvicorn main:app --reload
```
