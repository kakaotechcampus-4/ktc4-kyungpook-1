"""공통 장치(llm_harness)가 요청의 모델에 맞게 주소를 바꾸고 쓴 돈을 장부에 적는지 확인한다.

기본은 가짜 전송 계층으로 돌아서 키도 네트워크도 필요 없고, 장부는 임시 폴더에 쓴다.
--live 는 실제 키로 모델 셋에 질문 하나씩을 보내 같은 것을 확인한다(돈이 든다).

    cd ai
    python evals/common/harness_selftest.py
    python evals/common/harness_selftest.py --live
"""

import json
import os
import sys
import tempfile
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))

LIVE = "--live" in sys.argv
MODELS = ("gpt-5.6-luna", "openai/gpt-6-luna", "google/gemini-4-argon")

if not LIVE:
    # llm_harness 는 불러올 때 환경변수를 읽으므로 그 전에 가짜 값을 둔다
    _tmp = tempfile.mkdtemp(prefix="harness-selftest-")
    os.environ["GITORY_EVAL_LEDGER"] = str(Path(_tmp) / "spend.jsonl")
    os.environ["GITORY_EVAL_KEY_FILE"] = str(Path(_tmp) / "no-such.env")
    os.environ["GITORY_LLM_BASE_URL"] = "https://llm.example.invalid/gateway/v1"
    os.environ["GITORY_LLM_API_KEY"] = "fake"
    os.environ["GITORY_EVAL_ENDPOINTS"] = json.dumps({"gpt-6-luna": "id-luna6", "gemini-4-argon": "id-argon"})

import httpx  # noqa: E402

seen: list[str] = []
if not LIVE:
    def fake_send(self, request, **kwargs):
        seen.append(request.url.path)
        body = {"choices": [{"message": {"content": "{}"}}], "usage": {"prompt_tokens": 1000, "completion_tokens": 100}}
        return httpx.Response(200, json=body, request=request)

    # install() 이 원래 전송을 이 함수로 기억하게 먼저 바꿔 둔다
    httpx.Client.send = fake_send

from evals.common import llm_harness  # noqa: E402

os.environ["GITORY_EVAL_TAG"] = "selftest"
llm_harness.install()
before = llm_harness.spent()

if LIVE:
    from pydantic import BaseModel
    from services.llm_structured_client import LLMSettings, StructuredLLMClient

    class Answer(BaseModel):
        color: str

    for model in MODELS:
        client = StructuredLLMClient(LLMSettings(os.environ["GITORY_LLM_BASE_URL"], os.environ["GITORY_LLM_API_KEY"], model, "low"))
        result = client.call("입력의 질문에 한 단어로 답한다.", {"q": "하늘 색은?"}, Answer, 300)
        print("OK", model, result.output.color, result.prompt_tokens, result.completion_tokens)
else:
    base = os.environ["GITORY_LLM_BASE_URL"].rstrip("/")
    with httpx.Client() as http:
        for model in MODELS:
            http.post(f"{base}/chat/completions", json={"model": model, "messages": []})
    expected = ["/gateway/v1/chat/completions", "/id-luna6/v1/chat/completions", "/id-argon/v1/chat/completions"]
    assert seen == expected, f"주소가 모델별로 바뀌지 않았다: {seen}"
    rows = [json.loads(line) for line in llm_harness.LEDGER.read_text(encoding="utf-8").splitlines()]
    assert [r["model"] for r in rows] == ["gpt-5.6-luna", "gpt-6-luna", "gemini-4-argon"], rows
    assert all(r["tag"] == "selftest" for r in rows), rows
    assert rows[0]["won"] == round((1000 * 283 + 100 * 1701) / 1e6, 4), rows[0]
    print("OK 모델별 주소", seen)

print(f"장부에 더해진 돈 {llm_harness.spent() - before:.3f}원")
