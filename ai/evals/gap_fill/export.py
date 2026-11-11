"""모자란 조건 채우기 측정 세트를 로컬 DB(실제 공고·내 카드·후보·레포)에서 만든다.

공고는 분야마다 정한 수를 공고 id 해시 순으로 고르고(다시 돌려도 같은 표본), 필수 기술·경험 조건을 5개까지 '못 채운 조건' 으로 쓴다.
카드·후보·레포는 실제 사용자 데이터라 저장소에 두지 않고, 결과는 ai/evals/results/gap_fill/fixture.json(git 이 무시)에 쓴다.

    cd ai
    python evals/gap_fill/export.py                    # 로컬 DB 컨테이너가 떠 있어야 한다
    python evals/gap_fill/export.py --container <이름> --output <경로>
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

RESULTS_DIR = Path(__file__).resolve().parents[1] / "results" / "gap_fill"
CONTAINER = "gitory-local-db-1"
PER_CATEGORY = {"BACKEND": 6, "AI_ML": 4, "DEVOPS": 3, "DATA": 3, "SECURITY": 3, "FRONTEND": 3, "MOBILE": 2}


def query(sql: str):
    out = subprocess.run(["docker", "exec", CONTAINER, "psql", "-U", "gitory", "-d", "gitory", "-tAc", sql],
                         capture_output=True, text=True, encoding="utf-8", check=True).stdout.strip()
    return json.loads(out) if out else []


def card_rows(status: str):
    return query(f"""
        select coalesce(json_agg(row_to_json(c) order by c.updated desc), '[]') from (
          select c.public_id as card_id, c.title, coalesce(to_json(c.techs), '[]'::json) as techs, c.updated_at as updated,
            (select string_agg(s.body, ' ' order by s.seq) from card_statement s where s.card_id = c.id and s.version_no = c.current_version and s.star_slot = 'S') as situation,
            (select string_agg(s.body, ' ' order by s.seq) from card_statement s where s.card_id = c.id and s.version_no = c.current_version and s.star_slot = 'T') as task,
            (select string_agg(s.body, ' ' order by s.seq) from card_statement s where s.card_id = c.id and s.version_no = c.current_version and s.star_slot = 'A') as action,
            (select string_agg(s.body, ' ' order by s.seq) from card_statement s where s.card_id = c.id and s.version_no = c.current_version and s.star_slot = 'R') as result
          from card c where c.status = '{status}' and c.deleted_at is null) c""")


def main() -> None:
    global CONTAINER
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--container", default=CONTAINER, help="로컬 PostgreSQL 컨테이너 이름")
    parser.add_argument("--output", type=Path, default=RESULTS_DIR / "fixture.json", help="측정 세트를 쓸 경로")
    args = parser.parse_args()
    CONTAINER = args.container
    cards = card_rows("CONFIRMED")
    drafts = card_rows("DRAFT")
    for card in cards + drafts:
        card.pop("updated", None)
        card["techs"] = [tech for tech in card["techs"] if tech][:20]
    candidates = query("""
        select coalesce(json_agg(row_to_json(x) order by x.score desc), '[]') from (
          select c.public_id as candidate_id, r.owner_login || '/' || r.name as repo_name, c.title, c.reason_text as reason, c.score,
            coalesce((select json_agg(split_part(g.message, E'\\n', 1)) from (
                select g.message from candidate_commit cc join git_commit g on g.id = cc.commit_id
                where cc.candidate_id = c.id and cc.is_included order by g.authored_at desc limit 15) g), '[]') as commit_messages
          from candidate c join user_repository ur on ur.id = c.user_repository_id join repository r on r.id = ur.repository_id
          where c.status = 'NEW') x""")
    for candidate in candidates:
        candidate.pop("score", None)
    repos = query("""
        select coalesce(json_agg(json_build_object('repo_id', ur.public_id, 'full_name', r.owner_login || '/' || r.name,
                                                   'analyzed', ur.last_analyzed_at is not null) order by r.name), '[]')
        from user_repository ur join repository r on r.id = ur.repository_id""")
    postings = []
    for category, count in PER_CATEGORY.items():
        postings += query(f"""
            select coalesce(json_agg(row_to_json(p)), '[]') from (
              select p.public_id as posting_id, p.category, p.company_name, p.title as job_title,
                (select json_agg(json_build_object('condition_id', k.public_id, 'criterion_type', 'REQUIRED', 'text', k.text) order by k.seq)
                   from (select * from job_posting_criterion k where k.job_posting_id = p.id and k.type = 'REQUIRED'
                         and k.general_type is null order by k.seq limit 5) k) as conditions
              from job_posting p where p.status = 'ACTIVE' and p.category = '{category}'
                and exists (select 1 from job_posting_criterion k where k.job_posting_id = p.id and k.type = 'REQUIRED' and k.general_type is null)
              order by md5(p.public_id::text) limit {count}) p""")
    login = query("select to_json(github_login) from users limit 1")
    fixture = {"login": login, "cards": cards[:40], "draft_cards": drafts[:20], "candidates": candidates[:100],
               "repos": repos[:300], "postings": postings}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(fixture, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"카드 {len(cards)} · 작성 중 {len(drafts)} · 후보 {len(candidates)} · 레포 {len(repos)} · 공고 {len(postings)} · "
          f"조건 {sum(len(p['conditions']) for p in postings)}")


if __name__ == "__main__":
    main()
