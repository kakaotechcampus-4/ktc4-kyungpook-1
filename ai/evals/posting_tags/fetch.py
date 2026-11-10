"""공고 뽑기 평가용 공고 100건을 모은다 (서버와 같은 회사 목록·같은 개발 공고 거르기·같은 본문 글 바꾸기).

Greenhouse 공개 API 와 Greeting 채용 페이지만 쓴다(서버 회사 52곳). 회사당 최대 4건, 요청 사이 1초 쉰다.
공고 본문은 저장소에 두지 않고 실행 때 받아 ai/evals/results/posting_tags/postings.jsonl 에 쓴다(git 이 무시).
채용 중인 공고가 그때그때 달라서 정답(gold/)의 공고가 일부 내려갔을 수 있고, 그 공고는 compare.py 가 건너뛴다.

    cd ai
    python evals/posting_tags/fetch.py
    python evals/posting_tags/fetch.py --output <경로>
"""

from __future__ import annotations

import html
import json
import argparse
import re
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit

import httpx

_AI_ROOT = Path(__file__).resolve().parents[2]
RESULTS_DIR = _AI_ROOT / "evals" / "results" / "posting_tags"
PER_COMPANY = 4
TOTAL = 100
UA = "gitory-eval-measurement/1.0"

KOREA_WORDS = ["korea", "seoul", "busan", "incheon", "daejeon", "pangyo", "seongnam", "suwon", "hwaseong", "dongtan",
               "bundang", "gwacheon", "pohang", "jeju", "changwon", "ulsan", "naju", "goyang", "한국", "대한민국", "서울",
               "부산", "인천", "대전", "판교", "성남", "수원", "화성", "분당", "과천", "포항", "제주", "창원", "울산", "나주",
               "고양", "광역시"]
DEV_ROLE = re.compile(
    r"\b(software|s/w|developer|back-?end|front-?end|full[- ]?stack|android|ios|devops|sre|site reliability|"
    r"machine learning|ml|mlops|llms?|programmer|sw engineer|data engineer|data scientist|applied scientist|"
    r"research scientist|(mobile|client|server|research|security|network|platform) engineer|"
    r"ai (agent |research )?engineer|threat detection engineer|forward deployed engineer|qa engineer|qa automation|"
    r"sdet|test automation|compiler|firmware|robot(ics)? (\w+ )?engineer|(sw|cloud/infra) engineering)\b"
    r"|개발자|프로그래머|소프트웨어|백엔드|프론트엔드|머신러닝|펌웨어|(서버|데이터|클라우드|프로덕트)\s?엔지니어"
    r"|(it|ax|앱|미들웨어|풀\s?스택|ai\s?서비스)\s?개발|(ai|ax)\s?연구개발", re.IGNORECASE)
NON_DEV = re.compile(r"\bdeveloper relations\b", re.IGNORECASE)
BLOCKS = {"p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "table", "tr", "section", "article",
          "header", "footer", "blockquote", "pre", "dl", "dt", "dd", "hr"}


class _Text(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag in BLOCKS or tag == "br":
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in BLOCKS:
            self.parts.append("\n")

    def handle_data(self, data):
        self.parts.append(data)


def text_of(fragment: str) -> str:
    parser = _Text()
    parser.feed(fragment)
    lines = (re.sub(r"[ \t]+", " ", line).strip() for line in "".join(parser.parts).split("\n"))
    return "\n".join(line for line in lines if line)


def accepts(title: str, location: str) -> bool:
    location = location.lower()
    return any(w in location for w in KOREA_WORDS) and DEV_ROLE.search(title) is not None and not NON_DEV.search(title)


def companies(repo: Path) -> list[dict]:
    yml = (repo / "backend/src/main/resources/application.yml").read_text(encoding="utf-8")
    found = []
    for m in re.finditer(r"\{ key: ([\w-]+), name: ([^,]+), type: (GREENHOUSE|GREETING), board: '?([^,']+)'?", yml):
        found.append({"key": m.group(1), "name": m.group(2).strip(), "type": m.group(3), "board": m.group(4).strip()})
    return found


def next_data(page: str, prefix: list[str]):
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>', page, re.S)
    if not m:
        return None
    queries = json.loads(m.group(1)).get("props", {}).get("pageProps", {}).get("dehydratedState", {}).get("queries", [])
    for q in queries:
        key = q.get("queryKey") or []
        if key[: len(prefix)] == prefix:
            return q.get("state", {}).get("data")
    return None


def greenhouse(client: httpx.Client, c: dict) -> list[dict]:
    body = client.get(f"https://boards-api.greenhouse.io/v1/boards/{c['board']}/jobs?content=true").json()
    out = []
    for job in body.get("jobs", []):
        title = job.get("title") or ""
        location = (job.get("location") or {}).get("name") or ""
        if accepts(title, location):
            out.append({"id": str(job["id"]), "title": title, "url": job.get("absolute_url"),
                        "text": text_of(html.unescape(job.get("content") or ""))})
    return out


def greeting(client: httpx.Client, c: dict) -> list[dict]:
    data = next_data(client.get(c["board"]).text, ["openings"])
    openings = data if isinstance(data, list) else (data or {}).get("openings", []) if isinstance(data, dict) else []
    origin = "{0.scheme}://{0.netloc}".format(urlsplit(c["board"]))
    picked = []
    for o in openings:
        if o.get("deploy") is False:
            continue
        places = [((p.get("workspacePlace") or {}).get("place") or "")
                  for p in (o.get("openingJobPosition") or {}).get("openingJobPositions", [])]
        title = o.get("title") or ""
        if accepts(title, " ".join(places) or "대한민국"):
            picked.append({"id": str(o["openingId"]), "title": title, "url": f"{origin}/ko/o/{o['openingId']}"})
    return picked


def greeting_detail(client: httpx.Client, posting: dict) -> str:
    time.sleep(1)
    data = next_data(client.get(posting["url"]).text, ["career", "getOpeningById"])
    detail = (((data or {}).get("data") or {}).get("openingsInfo") or {}).get("detail")
    return text_of(detail) if isinstance(detail, str) else ""


def spread(items: list[dict], n: int) -> list[dict]:
    items = sorted(items, key=lambda x: x["id"])
    if len(items) <= n:
        return items
    step = len(items) / n
    return [items[int(i * step)] for i in range(n)]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", type=Path, default=_AI_ROOT.parent, help="회사 목록(application.yml)을 읽을 저장소 루트")
    parser.add_argument("--output", type=Path, default=RESULTS_DIR / "postings.jsonl", help="공고 jsonl 경로")
    args = parser.parse_args()
    repo, out = args.repo, args.output
    per_company: dict[str, list[dict]] = {}
    with httpx.Client(headers={"User-Agent": UA}, timeout=20, follow_redirects=True) as client:
        for c in companies(repo):
            try:
                found = greenhouse(client, c) if c["type"] == "GREENHOUSE" else greeting(client, c)
            except Exception as e:  # 한 회사가 실패해도 다음 회사로
                print(f"skip {c['key']}: {type(e).__name__}")
                continue
            time.sleep(1)
            chosen = spread(found, PER_COMPANY)
            for p in chosen:
                if c["type"] == "GREETING":
                    p["text"] = greeting_detail(client, p)
                p.update(company=c["key"], company_name=c["name"])
            chosen = [p for p in chosen if len(p.get("text", "")) >= 200]
            per_company[c["key"]] = chosen
            print(f"{c['key']}: 개발 공고 {len(found)} → {len(chosen)}")
    # 회사마다 한 건씩 돌아가며 담아 100건에서 끊어, 앞쪽 회사에 쏠리지 않게 한다
    result, round_ = [], 0
    while len(result) < TOTAL and any(round_ < len(v) for v in per_company.values()):
        for key in sorted(per_company):
            if round_ < len(per_company[key]) and len(result) < TOTAL:
                result.append(per_company[key][round_])
        round_ += 1
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(json.dumps(p, ensure_ascii=False) for p in result) + "\n", encoding="utf-8")
    print(f"저장 {len(result)}건, 회사 {len({p['company'] for p in result})}곳")


if __name__ == "__main__":
    main()
