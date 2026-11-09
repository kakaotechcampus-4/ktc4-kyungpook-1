"""공고 뽑기 결과(기술 이름표·일반 조건 종류·학력)를 정답과 견줘 숫자를 낸다. 판정 파일이 있으면 갈린 곳은 판정을 정답으로 쓴다.

gold/gold_*.json 은 [{key, team_techs, criteria:[{i, general_type, techs, education_level, education_exempt}]}] 이고
adjudicated.json 은 정답과 AI 가 갈린 곳의 판정이다. 정답에 없는 공고는 건너뛴다. LLM 을 부르지 않는다.

    cd ai
    python evals/posting_tags/compare.py evals/results/posting_tags/results.jsonl
    python evals/posting_tags/compare.py evals/results/posting_tags/results.jsonl --disagreements evals/results/posting_tags/dis.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_gold(gold_dir: Path) -> dict:
    gold = {}
    for f in sorted(gold_dir.glob("gold_*.json")):
        for g in json.loads(f.read_text(encoding="utf-8")):
            gold[g["key"]] = g
    return gold


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("results", type=Path, help="extract.py 가 쓴 결과 jsonl")
    parser.add_argument("--gold", type=Path, default=HERE / "gold", help="gold_*.json 이 있는 폴더")
    parser.add_argument("--adjudicated", type=Path, default=HERE / "adjudicated.json", help="갈린 곳의 판정 json")
    parser.add_argument("--disagreements", type=Path, help="갈린 곳을 저장할 json")
    args = parser.parse_args()
    results_path, gold_dir, dis_out = args.results, args.gold, args.disagreements
    adjudicated = json.loads(args.adjudicated.read_text(encoding="utf-8")) if args.adjudicated.exists() else {}
    gold = load_gold(gold_dir)
    rows = [json.loads(l) for l in results_path.read_text(encoding="utf-8").splitlines() if l.strip()]

    failed = sum(1 for r in rows if "error" in r)
    tag_ai = tag_gold = tag_fp = tag_fn = 0
    team_ai = team_gold = team_fp = team_fn = 0
    gen_total = gen_wrong = 0
    gen_wrong_postings = set()
    edu_total = edu_level_ok = edu_exempt_ok = 0
    disagreements = []

    for r in rows:
        if "result" not in r:
            continue
        key = f"{r['company']}/{r['id']}"
        g = gold.get(key)
        if g is None:
            continue
        crit = r["result"]["criteria"]
        gold_by_i = {c["i"]: c for c in g["criteria"]}
        # 같은 원문을 조건 둘 이상으로 나눈 경우 정답과 AI 의 순서가 다를 수 있어, 그 묶음 안에서는 일반 조건 종류가 같은 것끼리 짝짓는다
        by_quote = {}
        for i in gold_by_i:
            by_quote.setdefault(crit[i]["source_quote"], []).append(i)
        for same in (v for v in by_quote.values() if len(v) > 1):
            pool = [gold_by_i[i] for i in same]
            for i in same:
                pick = next((x for x in pool if x.get("general_type") == crit[i].get("general_type")), pool[0])
                pool.remove(pick)
                gold_by_i[i] = {**pick, "i": i}

        def final(kind: str, i, ai_value, gold_value):
            """갈리면 판정을 따르고, 판정이 없으면 정답을 따른다."""
            if ai_value == gold_value:
                return gold_value
            dk = f"{key}#{i}#{kind}"
            disagreements.append({"id": dk, "key": key, "i": i, "kind": kind,
                                  "quote": crit[i]["source_quote"] if isinstance(i, int) else None,
                                  "ai": ai_value, "gold": gold_value})
            return adjudicated.get(dk, gold_value)

        # 팀 기술
        ai_team = sorted(r["result"].get("team_techs", []))
        right_team = final("team_techs", "team", ai_team, sorted(g.get("team_techs", [])))
        team_ai += len(ai_team); team_gold += len(right_team)
        team_fp += len(set(ai_team) - set(right_team)); team_fn += len(set(right_team) - set(ai_team))

        for i, c in enumerate(crit):
            if c["criterion_type"] == "RESPONSIBILITY" or i not in gold_by_i:
                continue
            gc = gold_by_i[i]
            # 일반 조건 구분
            right_gen = final("general_type", i, c.get("general_type"), gc.get("general_type"))
            gen_total += 1
            if c.get("general_type") != right_gen:
                gen_wrong += 1
                gen_wrong_postings.add(key)
            # 기술 이름표: 정답상 기술 조건인 것만
            if right_gen is None:
                ai_tags = sorted(c.get("techs", []))
                right_tags = final("techs", i, ai_tags, sorted(gc.get("techs", [])))
                tag_ai += len(ai_tags); tag_gold += len(right_tags)
                tag_fp += len(set(ai_tags) - set(right_tags)); tag_fn += len(set(right_tags) - set(ai_tags))
            # 학력: 정답상 학력 조건인 것만
            if right_gen == "EDUCATION":
                edu_total += 1
                right_level = final("education_level", i, c.get("education_level"), gc.get("education_level"))
                right_exempt = final("education_exempt", i, bool(c.get("education_exempt")),
                                     bool(gc.get("education_exempt")))
                edu_level_ok += int(c.get("education_level") == right_level)
                edu_exempt_ok += int(bool(c.get("education_exempt")) == right_exempt)

    pct = lambda a, b: f"{a}/{b} ({100 * a / b:.1f}%)" if b else f"{a}/0"
    report = {
        "공고": f"{len(rows)} (실패 {failed})",
        "이름표 잘못 붙음": pct(tag_fp, tag_ai),
        "이름표 빠짐": pct(tag_fn, tag_gold),
        "팀 기술 잘못 붙음": pct(team_fp, team_ai),
        "팀 기술 빠짐": pct(team_fn, team_gold),
        "일반 조건 잘못 구분(조건)": pct(gen_wrong, gen_total),
        "일반 조건 잘못 구분(공고)": pct(len(gen_wrong_postings), len([r for r in rows if "result" in r and f"{r['company']}/{r['id']}" in gold])),
        "학력 수준 맞음": pct(edu_level_ok, edu_total),
        "학력 예외 맞음": pct(edu_exempt_ok, edu_total),
        "갈린 곳": len(disagreements),
    }
    print(json.dumps(report, ensure_ascii=False, indent=1))
    if dis_out:
        dis_out.parent.mkdir(parents=True, exist_ok=True)
        dis_out.write_text(json.dumps(disagreements, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
