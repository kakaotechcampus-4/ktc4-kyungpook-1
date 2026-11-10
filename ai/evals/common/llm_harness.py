"""측정용 공통 장치: 요청의 모델 이름에 맞는 주소로 보내고, 쓴 돈을 장부에 적어 상한에서 멈춘다.

LLM 게이트웨이가 모델마다 주소(<서버>/<ID>/v1)를 따로 주면, 운영 코드처럼 주소 하나(GITORY_LLM_BASE_URL)로
여러 모델을 부르기 위해 요청을 보내기 직전에 주소를 바꿔 끼운다. 키와 주소는 어디에도 찍지 않는다.

쓰는 법: 측정 스크립트에서 ai/ 를 sys.path 에 넣은 뒤 `from evals.common import llm_harness; llm_harness.install()`.

    GITORY_EVAL_KEY_FILE   키 파일 경로(기본 ~/.gitory-llm.env). KEY=VALUE 줄이며 저장소에 두지 않는다.
                           GITORY_LLM_BASE_URL, GITORY_LLM_API_KEY 가 이미 환경변수에 있으면 없어도 된다.
    GITORY_EVAL_ENDPOINTS  모델 이름 → 주소 ID 의 JSON(예: {"gpt-6-luna": "<ID>"}). 없는 모델은 기본 주소로 간다.
    GITORY_EVAL_CAP_WON    쓴 돈 상한(기본 15000원). 닿으면 프로세스를 멈춘다.
    GITORY_EVAL_LEDGER     장부 경로(기본 ai/evals/results/spend.jsonl, git 이 무시하는 폴더).
    GITORY_EVAL_TAG        장부에 붙일 단계 이름.
"""

from __future__ import annotations

import json
import math
import os
import re
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

import httpx

_EVALS_ROOT = Path(__file__).resolve().parents[1]
KEY_FILE = Path(os.getenv("GITORY_EVAL_KEY_FILE", str(Path.home() / ".gitory-llm.env")))
LEDGER = Path(os.getenv("GITORY_EVAL_LEDGER", str(_EVALS_ROOT / "results" / "spend.jsonl")))
LOCK = LEDGER.with_suffix(".lock")
CAP_WON = float(os.getenv("GITORY_EVAL_CAP_WON", "15000"))

#: 모델 이름(앞의 'openai/' 같은 회사 이름은 뗌) → (원/100만 토큰 입력, 출력). 표에 없는 모델은 0원으로 적힌다.
PRICES = {
    "gpt-5.6-luna": (283, 1_701),
    "gpt-6-luna": (141, 708),
    "claude-haiku-5-5": (141, 708),
    "gemini-3.8-flash": (1_063, 5_315),
    "gemini-4-argon": (2_835, 14_175),
    "gpt-6.1-sol": (2_835, 14_175),
    "gpt-6.1-terra": (1_417, 7_087),
    "claude-opus-5-5": (5_670, 28_350),
}

_lock = threading.Lock()
_spent = 0.0
_installed = False
_refreshed_at = 0.0
_endpoints: dict[str, str] = {}


def short(model: str) -> str:
    return model.split("/")[-1]


def price(model: str) -> tuple[int, int]:
    return PRICES.get(short(model), (0, 0))


def load_env() -> None:
    """키 파일의 값을 환경변수로 올린다. 이미 있는 값은 덮지 않고, 파일이 없으면 환경변수만 쓴다."""
    if not KEY_FILE.exists():
        return
    for line in KEY_FILE.read_text(encoding="utf-8").splitlines():
        name, sep, value = line.partition("=")
        if sep and name.strip() and not name.strip().startswith("#"):
            os.environ.setdefault(name.strip(), value.strip())


def spent() -> float:
    return _spent


def spent_by_tag() -> dict[str, float]:
    totals: dict[str, float] = {}
    if LEDGER.exists():
        for line in LEDGER.read_text(encoding="utf-8").splitlines():
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            totals[row["tag"]] = totals.get(row["tag"], 0.0) + row["won"]
    return totals


class _FileLock:
    """여러 측정 프로세스가 장부에 동시에 써도 줄이 섞이지 않게 잠근다."""

    def __enter__(self):
        LOCK.parent.mkdir(parents=True, exist_ok=True)
        self._file = LOCK.open("a+b")
        while True:
            try:
                self._lock()
                return self
            except OSError:
                time.sleep(0.05)

    def __exit__(self, *exc):
        self._file.seek(0)
        self._unlock()
        self._file.close()

    def _lock(self) -> None:
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(self._file.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl

            fcntl.flock(self._file.fileno(), fcntl.LOCK_EX)

    def _unlock(self) -> None:
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(self._file.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.flock(self._file.fileno(), fcntl.LOCK_UN)


def _refresh() -> None:
    """다른 프로세스가 쓴 돈까지 상한에 넣으려고 10초마다 장부 합계를 다시 읽는다."""
    global _spent, _refreshed_at
    if time.monotonic() - _refreshed_at > 10:
        _spent = sum(spent_by_tag().values())
        _refreshed_at = time.monotonic()


def _record(model: str, usage: dict) -> None:
    global _spent
    tokens_in = usage.get("prompt_tokens") or 0
    tokens_out = usage.get("completion_tokens") or 0
    price_in, price_out = price(model)
    won = (tokens_in * price_in + tokens_out * price_out) / 1_000_000
    row = {
        "t": datetime.now().isoformat(timespec="seconds"),
        "tag": os.getenv("GITORY_EVAL_TAG", "-"),
        "model": short(model),
        "in": tokens_in,
        "out": tokens_out,
        "won": round(won, 4),
    }
    with _lock, _FileLock():
        _spent += won
        with LEDGER.open("a", encoding="utf-8") as ledger:
            ledger.write(json.dumps(row, ensure_ascii=False) + "\n")


def _usage_from_sse(tail: str) -> dict | None:
    for line in reversed(tail.splitlines()):
        line = line.strip()
        if line.startswith("data:") and '"usage"' in line:
            try:
                usage = json.loads(line[5:].strip()).get("usage")
            except json.JSONDecodeError:
                continue
            if usage:
                return usage
    return None


class _TeeStream(httpx.SyncByteStream):
    """스트리밍 응답을 그대로 흘려보내면서 끝부분만 남겨 두었다가, 닫힐 때 마지막 usage 를 장부에 적는다."""

    def __init__(self, inner, model: str) -> None:
        self._inner = inner
        self._model = model
        self._tail = ""
        self._done = False

    def __iter__(self):
        for chunk in self._inner:
            self._tail = (self._tail + chunk.decode("utf-8", errors="ignore"))[-16_000:]
            yield chunk

    def close(self) -> None:
        if not self._done:
            self._done = True
            usage = _usage_from_sse(self._tail)
            if usage:
                _record(self._model, usage)
        close = getattr(self._inner, "close", None)
        if close:
            close()


def install() -> None:
    """httpx 로 나가는 LLM 요청을 모델별 주소로 돌리고 비용을 센다. 여러 번 불러도 한 번만 건다."""
    global _installed, _spent, _endpoints
    if _installed:
        return
    _installed = True
    load_env()
    _endpoints = json.loads(os.getenv("GITORY_EVAL_ENDPOINTS") or "{}")
    _spent = sum(spent_by_tag().values())
    server = httpx.URL(os.environ["GITORY_LLM_BASE_URL"].rstrip("/"))
    original_send = httpx.Client.send

    def send(self, request: httpx.Request, **kwargs):
        if request.url.host != server.host or request.method != "POST" or not request.url.path.endswith("/chat/completions"):
            return original_send(self, request, **kwargs)
        _refresh()
        if _spent >= CAP_WON:
            sys.stderr.write(f"\n[llm_harness] 상한 {CAP_WON:,.0f}원에 닿아 멈춘다(쓴 돈 {_spent:,.1f}원)\n")
            sys.stderr.flush()
            os._exit(3)
        model = json.loads(request.content or b"{}").get("model", "")
        endpoint = _endpoints.get(short(model))
        if endpoint:
            request.url = request.url.copy_with(path=f"/{endpoint}/v1/chat/completions")
        response = original_send(self, request, **kwargs)
        if "text/event-stream" in response.headers.get("content-type", ""):
            response.stream = _TeeStream(response.stream, model)
        elif response.status_code == 200:
            response.read()
            try:
                usage = response.json().get("usage")
            except (json.JSONDecodeError, AttributeError):
                usage = None
            if usage:
                _record(model, usage)
        return response

    httpx.Client.send = send


HISTORY_DIR = _EVALS_ROOT / "results" / "history"
BACKFILL_NOTE = "원본 결과 파일에서 뒤늦게 만든 요약 — date 는 원본 파일 시각, git_sha 는 요약을 만든 때의 커밋"
_MAX_TEXT = 200


def _clean(value):
    """숫자·짧은 글·목록·사전만 남긴다. 모델이 쓴 긴 글과 비밀값·이메일이 요약 파일에 새지 않게 하려는 것이다."""
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, str):
        if len(value) > _MAX_TEXT:
            return None
        try:
            from services.secret_redaction import mask_emails, redact_secrets
        except ImportError:
            return value
        return mask_emails(redact_secrets(value))
    if isinstance(value, (list, tuple)):
        return [item for item in map(_clean, value) if item is not None]
    if isinstance(value, dict):
        cleaned = {key: _clean(item) for key, item in value.items() if isinstance(key, str)}
        return {key: item for key, item in cleaned.items() if item is not None}
    return None


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9._-]+", "-", text.lower()).strip("-") or "x"


def _git_sha() -> str:
    try:
        done = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=_EVALS_ROOT, capture_output=True,
                              text=True, timeout=10)
        return done.stdout.strip() if done.returncode == 0 and done.stdout.strip() else "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def record_summary(eval_name: str, label: str, metrics: dict, *, sample: str | None = None, model: str | None = None,
                   calls: int | None = None, spend_won: float | None = None, measured_at: datetime | None = None,
                   note: str | None = None) -> Path:
    """측정 요약을 날짜·평가·이름이 든 파일로 남긴다. 같은 날 같은 측정을 다시 하면 그 파일을 덮어쓴다."""
    # GITORY_EVAL_BACKFILL=1 이면 옛 원본에서 뒤늦게 만든 요약이라 측정 때의 커밋을 알 수 없다
    backfill = os.getenv("GITORY_EVAL_BACKFILL") == "1"
    when = measured_at or datetime.now()
    HISTORY_DIR.mkdir(parents=True, exist_ok=True)
    path = HISTORY_DIR / f"{when:%Y-%m-%d}-{_slug(eval_name)}-{_slug(label)}.json"
    body = {
        "date": f"{when:%Y-%m-%d}", "measured_at": when.isoformat(timespec="seconds"), "eval": eval_name,
        "label": label, "sample": _clean(sample), "model": _clean(model), "git_sha": None if backfill else _git_sha(), "calls": calls,
        "spend_won": None if spend_won is None else round(spend_won, 2), "metrics": _clean(metrics),
        "note": BACKFILL_NOTE if backfill else _clean(note),
    }
    path.write_text(json.dumps(body, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    return path
