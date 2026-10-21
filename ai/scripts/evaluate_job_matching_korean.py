"""한국어 카드 문장으로 실제 공고의 조건 문장을 찾을 때 임베딩 모델과 거리 상한을 견준다.

실행 예시::

    cd ai
    python -m scripts.evaluate_job_matching_korean       --provider multilingual-e5-small       --output evals/results/job_matching_korean-multilingual-e5-small.json

운영처럼 문장마다 가까운 조건 top_k 개를 받은 뒤 거리 상한으로 거른다. 한국어 공고만 색인했을 때(KO),
영어 공고만 색인했을 때(EN), 둘을 함께 색인했을 때(ALL)를 따로 센다. 거리는 모든 조건과 정확히 계산하므로
근사 검색을 쓰는 운영 Chroma 와 아주 조금 다를 수 있다.

공고 원문은 저장소에 없다. fixture 에는 조건 문장의 SHA-256 만 있어서, 실행할 때 Greenhouse 공개 API 에서
공고를 받아 조건 문장을 다시 뽑고 해시가 같은 것만 쓴다. 받은 응답은 GITORY_EVAL_CACHE_DIR(기본 .chroma/postings,
git 이 무시하는 폴더)에만 저장하고, --cache-only 를 주면 네트워크 없이 그 파일만 읽는다.
공고는 시간이 지나면 바뀌거나 내려가므로 해시가 맞지 않는 조건은 빼고, 몇 개를 뺐는지 결과에 적는다.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import statistics
import time
import unicodedata
import urllib.request
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from scripts.evaluate_job_matching import PROVIDERS, create_embedders

USER_AGENT = "Gitory/1.0"
REQUEST_INTERVAL_SECONDS = 1.0
MAX_RESPONSE_BYTES = 50 * 1024 * 1024
ALLOWED_HOST = "boards-api.greenhouse.io"
MIN_CONDITION_CHARS = 8
MAX_CONDITION_CHARS = 300

THRESHOLDS = [round(0.05 + 0.005 * step, 3) for step in range(151)]
DISTANCE_BINS = [round(0.01 * step, 2) for step in range(101)]
SUBSETS = ("KO", "EN", "ALL")


def normalize_text(text: str) -> str:
    """해시를 내기 전에 쓰는 정규화: NFC, 공백 연속을 한 칸으로, 앞뒤 공백 제거."""
    return " ".join(unicodedata.normalize("NFC", text).split())


def text_sha256(text: str) -> str:
    return hashlib.sha256(normalize_text(text).encode("utf-8")).hexdigest()


class _BlockParser(HTMLParser):
    """제목(h*, strong, b)·목록(li)·문단(p) 단위로 글자를 모은다."""

    BLOCK_TAGS = ("li", "p", "h1", "h2", "h3", "h4", "h5", "h6")

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.blocks: list[tuple[str, str]] = []
        self._stack: list[str] = []
        self._buffer: list[str] = []

    def flush(self, kind: str) -> None:
        text = " ".join("".join(self._buffer).split())
        if text:
            self.blocks.append((kind, text))
        self._buffer = []

    def handle_starttag(self, tag: str, attrs: Any) -> None:
        if tag in (*self.BLOCK_TAGS, "div", "br") and self._buffer:
            self.flush(self._stack[-1] if self._stack else "p")
        if tag in self.BLOCK_TAGS:
            self._stack.append(tag)
        if tag in ("strong", "b"):
            self._stack.append("strong")

    def handle_endtag(self, tag: str) -> None:
        if tag in (*self.BLOCK_TAGS, "strong", "b"):
            kind = "strong" if tag in ("strong", "b") else tag
            if self._buffer:
                self.flush(kind)
            if self._stack and self._stack[-1] == kind:
                self._stack.pop()

    def handle_data(self, data: str) -> None:
        self._buffer.append(data)

    def finish(self) -> list[tuple[str, str]]:
        self.close()
        if self._buffer:
            self.flush("p")
        return self.blocks


def content_blocks(content: str) -> list[tuple[str, str]]:
    """Greenhouse 의 content(HTML 이 한 번 이스케이프된 문자열)를 블록 목록으로 바꾼다."""
    parser = _BlockParser()
    parser.feed(html.unescape(content))
    return parser.finish()


SECTION_RULES = (
    ("PREFERRED", re.compile(r"더 좋아요|우대|있으면 좋은|preferred|nice to have|bonus|is a plus|plus if", re.I)),
    (
        "REQUIRED",
        re.compile(
            r"찾고 있어요|함께하고 싶어요|자격|필수|minimum qualifications|basic qualifications|requirements|"
            r"qualifications|right for me|what we.re looking for|you have|about you|who you are|^required$|"
            r"^experience:?$",
            re.I,
        ),
    ),
    (
        "RESPONSIBILITY",
        re.compile(
            r"이런 일을 해요|이런 문제|이런 경험을 해요|담당|주요 업무|업무 내용|opportunity|responsibilities|"
            r"what you.ll do|what you will do|working on|mission of the role|the impact you",
            re.I,
        ),
    ),
    ("SKILL", re.compile(r"tech stack|이런 기술|사용하는 기술|기술이에요|기술을 사용", re.I)),
)
STOP_SECTION = re.compile(
    r"참고해 주세요|합류해요|전형|안내사항|문의|why join|benefit|values|about moloco|about furiosa|"
    r"resources|privacy|ai use|contact|supporting your|work-from-home|소개해요|환영해요|equal opportunity|"
    r"compensation|salary|region [a-c]|how you work|not for you|faq|기타 사항|누릴 수 있어요|이렇게 일해요",
    re.I,
)


def section_of(heading: str) -> str | None:
    if STOP_SECTION.search(heading):
        return None
    for kind, pattern in SECTION_RULES:
        if pattern.search(heading):
            return kind
    return "UNKNOWN"


def heading_level(kind: str, text: str) -> int:
    """0 = 제목 아님, 1 = 절 제목(h1~h3), 2 = 작은 제목(h4~h6, 짧은 굵은 글씨)."""
    if kind in ("h1", "h2", "h3"):
        return 1
    if kind in ("h4", "h5", "h6"):
        return 2
    if kind == "strong" and len(text) <= 80 and (text.endswith((":", "?")) or len(text) <= 60):
        return 2
    return 0


def extract_conditions(content: str) -> list[dict[str, str]]:
    """공고 본문에서 담당업무·자격요건·우대사항 목록 항목을 뽑는다(종류 + 문장, 종류 안에서 중복 제거)."""
    current: str | None = None
    items: list[dict[str, str]] = []
    for kind, text in content_blocks(content):
        level = heading_level(kind, text)
        clean = text.strip(" -•·*")
        if level:
            section = section_of(text)
            if level == 2 and section == "UNKNOWN" and current not in (None, "UNKNOWN"):
                # 절 안의 작은 제목: 절은 그대로 두고, 담당업무·기술 절이면 제목 자체가 조건 문장이다.
                short_enough = MIN_CONDITION_CHARS // 2 <= len(clean) <= MAX_CONDITION_CHARS
                if current in ("RESPONSIBILITY", "SKILL") and short_enough:
                    items.append({"criterion_type": current, "text": clean})
                continue
            current = section
            continue
        if current in (None, "UNKNOWN") or kind not in ("li", "p"):
            continue
        if MIN_CONDITION_CHARS <= len(clean) <= MAX_CONDITION_CHARS:
            items.append({"criterion_type": current, "text": clean})
    seen: set[tuple[str, str]] = set()
    unique = []
    for item in items:
        key = (item["criterion_type"], item["text"].casefold())
        if key not in seen:
            seen.add(key)
            unique.append(item)
    return unique


def cache_directory() -> Path:
    return Path(os.getenv("GITORY_EVAL_CACHE_DIR", ".chroma/postings"))


def board_of(api_url: str) -> str:
    return urlparse(api_url).path.rstrip("/").split("/")[-2]


def fetch_boards(
    sources: list[dict[str, str]], cache_dir: Path, cache_only: bool
) -> dict[str, dict[int, dict[str, Any]]]:
    """회사마다 공고 목록 응답을 캐시에서 읽고, 없으면(--cache-only 가 아닐 때) 받아서 캐시에 저장한다."""
    boards: dict[str, dict[int, dict[str, Any]]] = {}
    fetched = False
    for source in sources:
        board = board_of(source["api"])
        path = cache_dir / f"{board}.json"
        if not path.exists():
            if cache_only:
                raise FileNotFoundError(f"--cache-only 인데 캐시가 없습니다: {path}")
            parsed = urlparse(source["api"])
            if parsed.scheme != "https" or parsed.hostname != ALLOWED_HOST:
                raise ValueError(f"허용하지 않은 주소입니다: {source['api']}")
            if fetched:
                time.sleep(REQUEST_INTERVAL_SECONDS)
            request = urllib.request.Request(source["api"], headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                raise ValueError(f"응답이 너무 큽니다: {source['api']}")
            cache_dir.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
            fetched = True
        jobs = json.loads(path.read_text(encoding="utf-8")).get("jobs", [])
        boards[board] = {job["id"]: job for job in jobs if isinstance(job.get("id"), int)}
    return boards


def load_fixture(path: Path) -> dict[str, Any]:
    """fixture 가 공고 원문을 담지 않았는지, 정답이 가리키는 조건이 목록에 있는지 확인하고 읽는다."""
    fixture = json.loads(path.read_text(encoding="utf-8"))
    indexes: dict[str, set[int]] = {}
    for posting in fixture["postings"]:
        key = f"{posting['source']}:{posting['external_posting_id']}"
        for criterion in posting["criteria"]:
            if set(criterion) != {"index", "criterion_type", "text_sha256"}:
                raise ValueError(f"{key} 의 조건 칸이 index·criterion_type·text_sha256 이 아닙니다: {sorted(criterion)}")
            if not re.fullmatch(r"[0-9a-f]{64}", criterion["text_sha256"]):
                raise ValueError(f"{key} 의 text_sha256 이 SHA-256 16진수가 아닙니다")
        indexes[key] = {criterion["index"] for criterion in posting["criteria"]}
    for case in fixture["cases"]:
        for item in case["relevant"]:
            if set(item) != {"posting_key", "index"}:
                raise ValueError(f"{case['case_id']} 의 정답 칸이 posting_key·index 가 아닙니다: {sorted(item)}")
            if item["index"] not in indexes[item["posting_key"]]:
                raise ValueError(f"{case['case_id']} 의 정답이 공고 조건에 없습니다: {item}")
    return fixture


def hydrate_fixture(
    fixture: dict[str, Any], boards: dict[str, dict[int, dict[str, Any]]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    """받은 공고에서 조건 문장을 다시 뽑아, 해시가 같은 조건만 (rows, cases, 보고) 로 돌려준다.

    조건 순서(index)는 fixture 의 값을 그대로 쓴다. 해시가 안 맞거나 공고가 내려간 조건은 빼고
    그 조건을 가리키는 정답도 뺀다. 정답이 있던 질의가 정답을 모두 잃으면 그 질의는 센 대상에서 뺀다.
    """
    rows: list[dict[str, Any]] = []
    removed = 0
    missing_postings = []
    for posting in fixture["postings"]:
        key = f"{posting['source']}:{posting['external_posting_id']}"
        board, _, job_id = posting["external_posting_id"].rpartition("-")
        job = boards.get(board, {}).get(int(job_id)) if job_id.isdigit() else None
        if job is None:
            missing_postings.append(key)
        found = {text_sha256(c["text"]): c["text"] for c in extract_conditions(job["content"])} if job else {}
        for criterion in posting["criteria"]:
            text = found.get(criterion["text_sha256"])
            if text is None:
                removed += 1
                continue
            rows.append(
                {
                    "posting_key": key,
                    "index": criterion["index"],
                    "language": fixture["posting_language"][key],
                    "text": text,
                }
            )
    available = {(row["posting_key"], row["index"]) for row in rows}
    cases = []
    skipped_cases = []
    removed_relevant = 0
    for case in fixture["cases"]:
        relevant = [item for item in case["relevant"] if (item["posting_key"], item["index"]) in available]
        removed_relevant += len(case["relevant"]) - len(relevant)
        if case["relevant"] and not relevant:
            skipped_cases.append(case["case_id"])
            continue
        cases.append({**case, "relevant": relevant})
    report = {
        "criteria_in_fixture": sum(len(p["criteria"]) for p in fixture["postings"]),
        "criteria_used": len(rows),
        "criteria_removed": removed,
        "postings_missing": missing_postings,
        "relevant_pairs_removed": removed_relevant,
        "cases_skipped": skipped_cases,
    }
    return rows, cases, report


def score_threshold(
    rankings: list[dict[str, Any]], maximum_distance: float, top_k: int
) -> dict[str, float | int | None]:
    """문장마다 top_k 안에서 거리 상한 이하만 남겼을 때의 정밀도·재현율을 센다.

    재현율의 분모는 min(정답 수, top_k) 라 top_k 보다 정답이 많아도 1.0 까지 갈 수 있다.
    정답이 없는 문장은 재현율에서 빼고, 무엇이라도 돌려주면 '헛 매칭' 으로 센다.
    """
    hits = retrieved = relevant_cap = 0
    posting_hits = posting_retrieved = posting_cap = 0
    empty_cases = empty_with_result = 0
    for ranking in rankings:
        kept = [hit for hit in ranking["hits"][:top_k] if hit["distance"] <= maximum_distance]
        relevant = ranking["relevant"]
        relevant_postings = {ref[0] for ref in relevant}
        kept_postings = {hit["ref"][0] for hit in kept}
        retrieved += len(kept)
        posting_retrieved += len(kept_postings)
        hits += sum(hit["ref"] in relevant for hit in kept)
        posting_hits += len(kept_postings & relevant_postings)
        if relevant:
            relevant_cap += min(len(relevant), top_k)
            posting_cap += min(len(relevant_postings), top_k)
        else:
            empty_cases += 1
            empty_with_result += bool(kept)
    precision = hits / retrieved if retrieved else None
    recall = hits / relevant_cap if relevant_cap else None
    f1 = (
        2 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and precision + recall
        else 0.0
    )
    return {
        "maximum_distance": maximum_distance,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "posting_precision": posting_hits / posting_retrieved if posting_retrieved else None,
        "posting_recall": posting_hits / posting_cap if posting_cap else None,
        "returned_per_case": retrieved / len(rankings) if rankings else 0.0,
        "no_answer_cases": empty_cases,
        "no_answer_cases_with_result": empty_with_result,
    }


def score_ranking(rankings: list[dict[str, Any]], top_k: int) -> dict[str, float]:
    """거리 상한 없이 순위만 본다: 정답이 있는 문장의 Recall@top_k 와 MRR."""
    with_answer = [ranking for ranking in rankings if ranking["relevant"]]
    recall = mrr = 0.0
    for ranking in with_answer:
        refs = [hit["ref"] for hit in ranking["hits"][:top_k]]
        found = [rank for rank, ref in enumerate(refs, start=1) if ref in ranking["relevant"]]
        recall += len(found) / min(len(ranking["relevant"]), top_k)
        mrr += 1.0 / found[0] if found else 0.0
    count = len(with_answer)
    return {"recall_at_k": recall / count, "mrr": mrr / count, "cases": count}


def best_threshold(rankings: list[dict[str, Any]], top_k: int) -> dict[str, Any]:
    scored = [score_threshold(rankings, threshold, top_k) for threshold in THRESHOLDS]
    return max(scored, key=lambda item: (item["f1"], -item["maximum_distance"]))


def cross_validated_f1(rankings: list[dict[str, Any]], top_k: int) -> float:
    """문장을 홀짝 두 묶음으로 나눠, 한쪽에서 고른 상한을 다른 쪽에 적용한 F1 의 평균."""
    folds = [rankings[0::2], rankings[1::2]]
    scores = []
    for chosen, held_out in ((folds[0], folds[1]), (folds[1], folds[0])):
        threshold = best_threshold(chosen, top_k)["maximum_distance"]
        scores.append(score_threshold(held_out, threshold, top_k)["f1"])
    return sum(scores) / len(scores)


def distance_histogram(rankings: list[dict[str, Any]], top_k: int) -> dict[str, list[int]]:
    """정답 거리, top_k 안에 든 오답 거리, 전체 오답 거리를 0.01 구간으로 센다(1.0 이상은 마지막 칸)."""
    histogram = {"relevant": [0] * 100, "wrong_in_top_k": [0] * 100, "wrong_all": [0] * 100}

    def bucket(distance: float) -> int:
        return min(max(int(distance / 0.01), 0), 99)

    for ranking in rankings:
        top_refs = {hit["ref"] for hit in ranking["hits"][:top_k]}
        for hit in ranking["hits"]:
            if hit["ref"] in ranking["relevant"]:
                histogram["relevant"][bucket(hit["distance"])] += 1
            else:
                histogram["wrong_all"][bucket(hit["distance"])] += 1
                if hit["ref"] in top_refs:
                    histogram["wrong_in_top_k"][bucket(hit["distance"])] += 1
    return histogram


def build_rankings(
    cases: list[dict[str, Any]], rows: list[dict[str, Any]], distances: list[list[float]], subset: str
) -> list[dict[str, Any]]:
    members = [i for i, row in enumerate(rows) if subset == "ALL" or row["language"] == subset]
    language = {row["posting_key"]: row["language"] for row in rows}
    rankings = []
    for case, row_distances in zip(cases, distances):
        ordered = sorted(members, key=lambda i: row_distances[i])
        relevant = {
            (item["posting_key"], item["index"])
            for item in case["relevant"]
            if subset == "ALL" or language[item["posting_key"]] == subset
        }
        rankings.append(
            {
                "case_id": case["case_id"],
                "style": case["style"],
                "relevant": relevant,
                "hits": [
                    {"ref": (rows[i]["posting_key"], rows[i]["index"]), "distance": row_distances[i]}
                    for i in ordered
                ],
            }
        )
    return rankings


def run_evaluation(
    fixture_path: Path, provider: str, top_k: int, threads: int | None, cache_dir: Path, cache_only: bool
) -> dict[str, Any]:
    import numpy as np

    fixture = load_fixture(fixture_path)
    boards = fetch_boards(fixture["sources"], cache_dir, cache_only)
    rows, cases, hydration = hydrate_fixture(fixture, boards)
    started = time.perf_counter()
    passage_embedder, query_embedder = create_embedders(provider, threads=threads)
    load_seconds = time.perf_counter() - started

    started = time.perf_counter()
    criteria = np.array(passage_embedder.embed([row["text"] for row in rows]), dtype=np.float64)
    index_ms = (time.perf_counter() - started) * 1000 / len(rows)

    query_ms = []
    queries = []
    for case in cases:
        started = time.perf_counter()
        queries.append(query_embedder.embed([case["statement"]["body"]])[0])
        query_ms.append((time.perf_counter() - started) * 1000)
    queries_array = np.array(queries, dtype=np.float64)

    criteria /= np.linalg.norm(criteria, axis=1, keepdims=True)
    queries_array /= np.linalg.norm(queries_array, axis=1, keepdims=True)
    distances = (1.0 - queries_array @ criteria.T).tolist()

    subsets = {}
    for subset in SUBSETS:
        rankings = build_rankings(cases, rows, distances, subset)
        by_style: dict[str, list[dict[str, Any]]] = {}
        for ranking in rankings:
            if ranking["relevant"]:
                by_style.setdefault(ranking["style"], []).append(ranking)
        subsets[subset] = {
            "ranking": score_ranking(rankings, top_k),
            "ranking_by_style": {style: score_ranking(items, top_k) for style, items in sorted(by_style.items())},
            "best": best_threshold(rankings, top_k),
            "cross_validated_f1": cross_validated_f1(rankings, top_k),
            "sweep": [score_threshold(rankings, threshold, top_k) for threshold in THRESHOLDS],
            "histogram": distance_histogram(rankings, top_k),
        }

    return {
        "fixture": str(fixture_path),
        "embedding_provider": provider,
        "top_k": top_k,
        "case_count": len(cases),
        "hydration": hydration,
        "criterion_count": len(rows),
        "criterion_count_by_language": {
            language: sum(row["language"] == language for row in rows) for language in ("KO", "EN")
        },
        "load_seconds": round(load_seconds, 2),
        "index_ms_per_sentence": round(index_ms, 2),
        "query_ms_per_sentence": {
            "median": round(statistics.median(query_ms), 2),
            "mean": round(statistics.fmean(query_ms), 2),
        },
        "distance_bins": DISTANCE_BINS,
        "subsets": subsets,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, default=Path("evals/job_matching_korean_cases.json"))
    parser.add_argument("--provider", choices=PROVIDERS, default="chroma-default")
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--threads", type=int, help="ONNX 모델의 계산 스레드 수(chroma-default 에는 적용 안 됨)")
    parser.add_argument(
        "--cache-dir", type=Path, default=cache_directory(), help="받은 공고 응답을 두는 곳(기본: GITORY_EVAL_CACHE_DIR 또는 .chroma/postings)"
    )
    parser.add_argument("--cache-only", action="store_true", help="네트워크 없이 캐시에 있는 공고만 쓴다")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    result = run_evaluation(
        args.fixture, args.provider, args.top_k, args.threads, args.cache_dir, args.cache_only
    )
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    hydration = result["hydration"]
    print(
        f"조건 {hydration['criteria_used']}/{hydration['criteria_in_fixture']}개 사용 "
        f"(해시가 안 맞거나 내려간 조건 {hydration['criteria_removed']}개 제외, "
        f"정답 쌍 {hydration['relevant_pairs_removed']}개 제외, 센 대상에서 뺀 질의 {len(hydration['cases_skipped'])}개)"
    )
    for subset in SUBSETS:
        best = result["subsets"][subset]["best"]
        ranking = result["subsets"][subset]["ranking"]
        print(
            f"{args.provider} {subset}: Recall@{args.top_k}={ranking['recall_at_k']:.3f} "
            f"MRR={ranking['mrr']:.3f} best distance={best['maximum_distance']} "
            f"P={best['precision'] or 0:.3f} R={best['recall'] or 0:.3f} F1={best['f1']:.3f}"
        )


if __name__ == "__main__":
    main()
