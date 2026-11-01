"""STAR 초안 검사기가 칸마다 무엇이 왜 걸렸는지 정해 둔 이유로 돌려주는지 검증한다."""

from __future__ import annotations

from schemas.analysis import DiffEvidence
from services.star_check import (
    CODE_NAME_REASON,
    NO_EVIDENCE_REASON,
    DraftProblem,
    DraftSlot,
    check_draft,
)

SHA_A = "a" * 40
SHA_B = "b" * 40


def _evidence(sha: str, message: str) -> DiffEvidence:
    return DiffEvidence(sha=sha, message=message, churn="+10/-2", summary="요약", summary_source="DIFF")


EVIDENCE = {
    SHA_A: _evidence(SHA_A, "feat: 목록 조회에 캐시 추가"),
    SHA_B: _evidence(SHA_B, "perf: 조회 시간 30% 단축"),
}


def test_sentence_without_commit_or_answer_is_no_evidence() -> None:
    checks = check_draft({"A": DraftSlot("캐시를 도입했다.", [])}, EVIDENCE, [], None)

    assert checks["A"].cited == []
    assert checks["A"].problems == [DraftProblem("A", "NO_EVIDENCE", "캐시를 도입했다.", None, NO_EVIDENCE_REASON)]


def test_unsourced_numbers_are_listed_with_the_reason() -> None:
    text = "조회 시간을 50% 줄이고 2배 빨라졌다."

    checks = check_draft({"R": DraftSlot(text, [SHA_A])}, EVIDENCE, [], None)

    assert checks["R"].problems == [DraftProblem(
        "R", "UNSOURCED_NUMBER", text, "2배, 50%", "근거에서 확인되지 않는 수치(2배, 50%)가 있어 사용자 확인이 필요합니다."
    )]


def test_number_found_in_cited_commit_answer_or_pull_request_body_is_not_a_problem() -> None:
    cited = check_draft({"R": DraftSlot("조회 시간을 30% 줄였다.", [SHA_B])}, EVIDENCE, [], None)
    answered = check_draft({"R": DraftSlot("배포를 3배 빠르게 했다.", [SHA_A])}, EVIDENCE, ["배포가 3배 빨라졌어요"], None)
    body = check_draft({"R": DraftSlot("응답을 200ms 줄였다.", [SHA_A])}, EVIDENCE, [], "응답이 200ms 줄었습니다")

    assert [cited["R"].problems, answered["R"].problems, body["R"].problems] == [[], [], []]


def test_unknown_sha_is_listed_and_other_evidence_keeps_the_sentence() -> None:
    checks = check_draft({"A": DraftSlot("캐시를 도입했다.", [SHA_A, "deadbeef00"])}, EVIDENCE, [], None)

    assert checks["A"].cited == [EVIDENCE[SHA_A]]
    assert checks["A"].problems == [DraftProblem(
        "A", "UNKNOWN_SHA", "캐시를 도입했다.", "deadbeef00", "입력에 없는 커밋 번호(deadbeef00)를 근거로 적어 그 번호는 뺐습니다."
    )]


def test_only_unknown_shas_without_answers_is_also_no_evidence() -> None:
    checks = check_draft({"A": DraftSlot("캐시를 도입했다.", ["deadbeef00"])}, EVIDENCE, [], None)

    assert [problem.code for problem in checks["A"].problems] == ["UNKNOWN_SHA", "NO_EVIDENCE"]


def test_answer_only_sentence_is_not_a_problem() -> None:
    checks = check_draft({"T": DraftSlot("팀이 정한 일정에 맞춰야 했다.", [])}, EVIDENCE, ["일정이 빠듯했어요"], None)

    assert (checks["T"].cited, checks["T"].problems) == ([], [])


def _code_evidence() -> dict[str, DiffEvidence]:
    return {SHA_A: DiffEvidence(
        sha=SHA_A, message="fix: `main` 에 합치기 전 `userId` 로 내 정보를 찾게 고침", churn="+10/-2", summary="요약",
        summary_source="DIFF",
        changed_paths=["backend/src/main/java/com/gitory/MyPageResponse.java", "backend/src/main/resources/data.sql",
                       "backend/build.gradle"],
    )}


def test_names_of_changed_files_and_backticked_code_are_code_names() -> None:
    """근거 커밋에서 바뀐 파일 이름, 코드 이름 모양의 파일 이름, 커밋 메시지에 `` 로 감싼 코드 이름은 프로젝트 안 이름으로 걸린다."""
    checks = check_draft(
        {"A": DraftSlot("MyPageResponse 에 userId 를 담고 data.sql 을 고쳐 MyPageResponse 를 줄였다.", [SHA_A])},
        _code_evidence(), [], None,
    )

    problem = checks["A"].problem("CODE_NAME")
    assert problem.value == "MyPageResponse, userId, data.sql"
    assert problem.reason == CODE_NAME_REASON.format(names="MyPageResponse, userId, data.sql")


def test_tech_names_and_names_not_in_evidence_are_not_code_names() -> None:
    """Spring·PostgreSQL·HTTP 401 같은 기술 이름, 근거에 없는 이름, 코드 이름 모양이 아닌 파일 이름 앞부분·`` 안 낱말은 걸지 않는다."""
    checks = check_draft(
        {"A": DraftSlot("Spring 과 PostgreSQL 로 HTTP 401 을 막고 getUser 와 build 설정을 고쳐 main 에 합쳤다.", [SHA_A])},
        _code_evidence(), [], None,
    )

    assert checks["A"].problem("CODE_NAME") is None


def test_error_code_joined_by_underscores_is_a_code_name_even_without_evidence() -> None:
    """오류 코드처럼 대문자를 밑줄로 이은 이름은 근거에 없어도 프로젝트 안 이름으로 건다."""
    checks = check_draft({"A": DraftSlot("GITHUB_UNAUTHORIZED 오류를 막았다.", [SHA_A])}, EVIDENCE, [], None)

    assert checks["A"].problem("CODE_NAME").value == "GITHUB_UNAUTHORIZED"
