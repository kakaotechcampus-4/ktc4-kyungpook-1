"""자소서 초안이 근거를 벗어났거나 글자 수를 넘는지 코드로 찾는다(검사기). 문항에 꼭 있어야 하는 재료도 여기서 정한다.

자소서에는 '5명·6개월·1위' 처럼 단위가 제각각인 수치가 많아, 카드 초안 검사(evidence_checks)의 성능 단위 수치와 함께
따로 선 숫자가 근거에도 따로 선 숫자로 있는지 본다. 한글로 쓴 숫자·이름(다섯 명·레디스)은 코드로 잡지 못해 전체 검토가 본다.
같은 뜻의 다른 표기(k8s·Kubernetes)도 걸리므로, 고칠지 둘지는 다음 행동 판단이 정한다.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Optional

from schemas.cover_letter import CardDetail, DraftProblemCode, KnownAnswer, MaterialItem
from services.cover_letter_evidence import PLAIN_LATIN_WORDS
from services.evidence_checks import unsupported_metrics

#: 문항을 쓰려면 꼭 있어야 하는 항목으로, 지원 동기 문항은 지원 이유다.
#: 성장·실패 문항은 배운 점이 없으면 결과에서 끝나거나 지어낸 깨달음이 들어가서 배운 점까지 본다.
REQUIRED_ITEMS: dict[str, tuple[MaterialItem, ...]] = {
    "EXPERIENCE": ("SITUATION", "ACTION", "RESULT"),
    "LESSON": ("SITUATION", "ACTION", "RESULT", "LEARNING"),
    "MOTIVATION": ("REASON",),
}
_LESSON_THEMES = ("GROWTH", "FAILURE")
ITEM_OF_SLOT: dict[str, MaterialItem] = {"S": "SITUATION", "A": "ACTION", "R": "RESULT"}

ITEM_LABELS: dict[str, str] = {
    "SITUATION": "상황", "ACTION": "행동", "RESULT": "결과", "LEARNING": "배운 점",
    "TRIGGER": "계기", "JOB_LINK": "직무 연결", "REASON": "지원 이유",
}
_BLANK_HINTS: dict[str, str] = {
    "SITUATION": "언제, 어디서, 무슨 일이었는지 적어 주세요",
    "ACTION": "직접 한 일을 적어 주세요",
    "RESULT": "바뀐 점이나 숫자를 적어 주세요",
    "LEARNING": "배운 점을 적어 주세요",
    "TRIGGER": "시작한 계기를 적어 주세요",
    "JOB_LINK": "지원 직무와 이어지는 점을 적어 주세요",
    "REASON": "이 회사를 고른 이유를 적어 주세요",
}
#: 강점 코드를 모델에 넘길 때의 말로, 영문 코드를 넘기면 글에 그대로 옮겨 근거 없는 영문 낱말로 걸린다
STRENGTH_LABELS: dict[str, str] = {
    "METICULOUS": "꼼꼼함", "PERSISTENT": "끈기", "PROACTIVE": "주도성", "FAST_LEARNER": "빠른 학습",
    "RESPONSIBLE": "책임감", "PROBLEM_SOLVING": "문제 해결", "COMMUNICATION": "소통", "COLLABORATION": "협업",
    "PERSUASION": "설득", "CONSIDERATE": "배려", "USER_FOCUS": "사용자 관점", "DOCUMENTING": "기록과 공유",
}
#: 지원 동기가 아닌데도 고른 강점을 이어 쓸 문항(역량·강점을 묻는 문항)을 알아보는 말.
_STRENGTH_QUESTION_WORDS = ("역량", "강점", "장점")
#: 사용자 글 가운데 LLM 에 보내지 않고 돌려보낼 지시문 모양. 갈등 이야기의 '팀원 의견을 무시하고' 같은 말은 잡지 않게 대상을 붙여 본다
_INSTRUCTION_LIKE = re.compile(
    r"(?:이전|앞|위|기존|모든)\s*(?:의\s*)?(?:지시|명령|규칙|설정)"
    r"|(?:지시|명령|규칙|프롬프트)\S{0,3}\s*무시"
    r"|(?:시스템|system)\s*(?:프롬프트|prompt|메시지|message)"
    r"|프롬프트\S{0,2}\s*(?:알려|보여|출력|공개)"
    r"|너는\s*이제|지금부터\s*너는|역할을\s*(?:바꿔|바꾸|맡아)"
    r"|ignore\s+(?:all\s+|any\s+|the\s+)?(?:previous|prior|above|earlier)"
    r"|disregard\s+(?:all\s+|any\s+|the\s+)?(?:previous|prior|above|earlier)"
    r"|you\s+are\s+now|jailbreak|developer\s+mode",
    re.IGNORECASE,
)
#: 카드 고르기 답을 못 받아 유형을 모를 때 지원 동기 문항을 알아보는 말.
_MOTIVATION_WORDS = ("지원 동기", "지원한 동기", "지원 이유", "지원한 이유", "지원하게 된", "입사 후", "포부")
_LESSON_WORDS = ("성장", "실패", "배운")
#: 재료가 모자란 자리에 모델이 넣는 빈칸으로, 무엇을 더 쓰면 되는지를 사용자가 화면에서 보고 채운다.
_MORE_BLANK = re.compile(r"\[더 쓸 거리: [^\[\]\n]{1,60}\]")

#: 영문으로 시작하는 낱말(React, Node.js, C++, k8s). 한 글자는 낱말로 보지 않는다.
_LATIN_TERM = re.compile(r"(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9+#.\-]*[A-Za-z0-9+#]")
#: 한 칸씩 띄어 이어진 영문 낱말들로, 'Spring Boot'·'GitHub Actions' 를 한 이름으로 센다
_LATIN_RUN = re.compile(r"(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9+#.\-]*[A-Za-z0-9+#](?: [A-Za-z][A-Za-z0-9+#.\-]*[A-Za-z0-9+#])*")
#: 영문·숫자에 붙지 않은 숫자(v2·k8s·OAuth2 의 숫자는 제외). 뒤에 붙은 단위는 화면에 보일 값으로만 쓴다.
_NUMBER = re.compile(r"(?<![A-Za-z0-9_.,])(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)(?![0-9A-Za-z])(%p|%|[가-힣]{1,2})?")
#: 문장 끝(. ! ? …) 뒤 공백이나 줄바꿈에서 나눈다. 40.5% 의 점은 뒤에 공백이 없어 나누지 않는다.
_SENTENCE_BREAK = re.compile(r"(?<=[.!?…])\s+|\n+")
#: 코드에 쓰는 이름(백틱·@애너테이션·Page<Order>·foo.bar(·camelCase·snake_case·'Menu 도메인' 같은 코드 구조 이름).
#: 인사 담당자가 읽지 못하는 말이라 카드에 있어도 걸고, 한글 조사가 붙어도 이름만 잡게 \w 대신 영문·숫자만 쓴다
_CODE_NAME = re.compile(
    r"`[^`\n]+`"
    r"|@[A-Za-z][A-Za-z0-9]*"
    r"|(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9]*<[A-Za-z][A-Za-z0-9, ]*>"
    r"|(?<![A-Za-z0-9.])[A-Za-z][A-Za-z0-9]*\.[A-Za-z][A-Za-z0-9]*\("
    r"|(?<![A-Za-z0-9])[a-z]{2,}[A-Z][a-z][A-Za-z0-9]*"
    r"|(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+"
    r"|(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9]*(?: [가-힣]{2,})? (?:도메인|엔티티|클래스|모듈|DTO|규격)"
)
#: 저장소 작업 말. 모든 문항이 같은 저장소 이야기로 읽히게 만드는 말이라 수를 센다('나머지'·'머지않아'·'레포트' 는 빼고 센다)
_REPO_WORD = re.compile(
    r"풀 ?리퀘스트|(?<![A-Za-z])PR(?![A-Za-z])|(?<!나)머지(?!않)"
    r"|(?<![A-Za-z])develop(?: ?브랜치)?(?![A-Za-z])|(?<![A-Za-z])main ?브랜치|브랜치"
    r"|커밋|리뷰 ?(?:지적|코멘트)|이슈|[레리]포지토리|레포(?!트)|저장소|(?<![A-Za-z])CI ?체크"
)
#: 지원 동기 글이 입사 후 하고 싶은 일로 맺었는지 보는 말
_AMBITION_WORDS = ("겠습니다", "싶습니다", "기여")


@dataclass(frozen=True)
class DraftIssue:
    code: DraftProblemCode
    value: Optional[str]
    sentence_seq: Optional[int]
    #: 같은 code 로 거는 검사기 규칙을 가르는 AI 안의 이름으로, 고칠 점 문구만 고르고 Spring 에는 보내지 않는다
    rule: Optional[str] = None


def item_group(theme: Optional[str], question_text: str) -> str:
    """유형을 모르면(카드 고르기 실패) 문항 문장으로 지원 동기·성장과 실패 문항인지 보고, 아니면 경험 문항으로 본다."""
    if theme is not None:
        if theme == "MOTIVATION":
            return "MOTIVATION"
        return "LESSON" if theme in _LESSON_THEMES else "EXPERIENCE"
    if any(word in question_text for word in _MOTIVATION_WORDS):
        return "MOTIVATION"
    return "LESSON" if any(word in question_text for word in _LESSON_WORDS) else "EXPERIENCE"


def strength_question(theme: Optional[str], question_text: str) -> bool:
    """고른 강점을 한 문장으로 이어 쓸 문항으로, 지원 동기 문항과 역량·강점을 묻는 문항이다."""
    return item_group(theme, question_text) == "MOTIVATION" or any(word in question_text for word in _STRENGTH_QUESTION_WORDS)


def instruction_like(text: str) -> bool:
    return _INSTRUCTION_LIKE.search(text) is not None


def answers_about_primary(answers: list[KnownAnswer], primary: Optional[CardDetail]) -> list[KnownAnswer]:
    """중심 경험의 재료로 칠 답. 보조 카드에 대한 답은 중심 경험의 결과가 아니라서 빼고, 카드와 상관없는 답은 넣는다."""
    primary_id = primary.card_id if primary is not None else None
    return [answer for answer in answers if answer.card_id is None or answer.card_id == primary_id]


def answer_about(card_id: Optional[int], primary: Optional[CardDetail], supporting: Optional[CardDetail]) -> Optional[str]:
    """답이 중심·보조 카드 중 어느 경험에 대한 것인지 모델에 알린다. 카드와 상관없거나 이번에 안 쓰는 카드의 답은 None."""
    for role, card in (("PRIMARY", primary), ("SUPPORTING", supporting)):
        if card is not None and card.card_id == card_id:
            return role
    return None


def required_missing(
    group: str, primary: Optional[CardDetail], answers: list[KnownAnswer], memo: Optional[str],
) -> list[MaterialItem]:
    """꼭 있어야 하는데 중심 카드 문장·중심 경험의 답·메모 어디에도 없는 항목."""
    present: set[str] = {answer.item for answer in answers_about_primary(answers, primary)}
    if primary is not None:
        present.update(ITEM_OF_SLOT[s.star_slot] for s in primary.statements if s.star_slot in ITEM_OF_SLOT)
    if group == "MOTIVATION" and memo and memo.strip():
        present.add("REASON")
    return [item for item in REQUIRED_ITEMS[group] if item not in present]


def blank(item: MaterialItem) -> str:
    """재료가 없는 자리에 넣는 빈칸으로, 사용자가 화면에서 채운다."""
    return f"[{ITEM_LABELS[item]}: {_BLANK_HINTS[item]}]"


def has_blank(text: str) -> bool:
    return has_more_blank(text) or any(blank(item) in text for item in ITEM_LABELS)  # type: ignore[arg-type]


def has_more_blank(text: str) -> bool:
    return _MORE_BLANK.search(text) is not None


def target_length(char_limit: int, ratio: float) -> int:
    return math.ceil(char_limit * ratio)


def countable_length(text: str) -> int:
    """공백을 포함한 글자 수로, 사용자가 채울 빈칸은 세지 않는다."""
    text = _MORE_BLANK.sub("", text)
    for item in ITEM_LABELS:
        text = text.replace(blank(item), "")  # type: ignore[arg-type]
    return len(text)


def split_sentences(text: str) -> list[str]:
    return [part.strip() for part in _SENTENCE_BREAK.split(text) if part.strip()]


def unsourced_terms(text: str, source: str) -> list[str]:
    known = {term.casefold() for term in _LATIN_TERM.findall(source)}
    found: list[str] = []
    for term in _LATIN_TERM.findall(text):
        if term.casefold() not in known and term not in found:
            found.append(term)
    return found


def unsourced_numbers(text: str, source: str) -> list[str]:
    """근거에 따로 선 숫자로 없는 숫자를 단위와 함께 돌려준다. 단위는 보지 않는다."""
    known = {_normalize(match.group(1)) for match in _NUMBER.finditer(source)}
    found: list[str] = []
    for match in _NUMBER.finditer(text):
        value = match.group(0)
        if _normalize(match.group(1)) not in known and value not in found:
            found.append(value)
    return found


def invented_numbers(text: str, source: str) -> list[str]:
    """근거에 없는 수치. 성능 단위(ms·%·배 등)는 같은 단위로, 그 밖의 숫자는 숫자만 근거에 있는지 본다."""
    numbers = unsupported_metrics(text, source)
    flagged = {_normalize(match.group(1)) for metric in numbers if (match := _NUMBER.search(metric))}
    numbers += [value for value in unsourced_numbers(text, source)
                if _normalize(_NUMBER.match(value).group(1)) not in flagged]
    return numbers


def check_draft(
    text: str, *, source: str, char_limit: int, missing_items: list[MaterialItem],
    tech_limit: Optional[int] = None, known_names: str = "", repo_word_limit: Optional[int] = None,
    ambition: bool = False,
) -> list[DraftIssue]:
    """초안에서 걸린 곳을 찾고, 수치·낱말은 그 값이 처음 나온 문장 번호를 함께 돌려준다.

    tech_limit 를 주면 기술 이름이 그보다 많은 글을 OFF_TOPIC 으로 걸고, 기술 이름이 가장 많은 문장을 가리킨다.
    코드 이름·저장소 작업 말 상한·포부 문장도 OFF_TOPIC 으로 걸어 Spring 이 아는 code 만 쓰고, 규칙은 rule 로 가른다.
    """
    issues = [DraftIssue("UNSOURCED_NUMBER", value, sentence_of(value, text)) for value in invented_numbers(text, source)]
    issues += [DraftIssue("UNSOURCED_TERM", term, sentence_of(term, text)) for term in unsourced_terms(text, source)]
    issues += [DraftIssue("MISSING_BLANK", item, None) for item in missing_items if blank(item) not in text]
    over = countable_length(text) - char_limit
    if over > 0:
        issues.append(DraftIssue("OVER_LIMIT", str(over), None))
    names = tech_names(text, known_names)
    if tech_limit is not None and len(names) > tech_limit:
        issues.append(DraftIssue("OFF_TOPIC", f"기술 이름 {len(names)}개 (상한 {tech_limit})",
                                 _densest(text, known_names), "TECH_NAMES"))
    issues += [DraftIssue("OFF_TOPIC", name, sentence_of(name, text), "CODE_NAME") for name in code_names(text, known_names)]
    words = repo_words(text)
    if repo_word_limit is not None and len(words) > repo_word_limit:
        counts = [len(repo_words(sentence)) for sentence in split_sentences(text)]
        issues.append(DraftIssue("OFF_TOPIC", f"저장소 작업 말 {len(words)}번 (상한 {repo_word_limit})",
                                 counts.index(max(counts)) + 1, "REPO_WORDS"))
    if ambition and not ends_with_ambition(text):
        issues.append(DraftIssue("OFF_TOPIC", "지원 동기 문항의 포부 문장 없음", None, "NO_AMBITION"))
    return issues


def code_names(text: str, known_names: str = "") -> list[str]:
    """글에 쓴 코드 이름을 하나씩. 카드 출처·회사 이름이거나 'API 규격' 처럼 줄임말을 부르는 말은 코드 이름이 아니다."""
    known = known_names.casefold()
    found: list[str] = []
    for match in _CODE_NAME.finditer(text):
        name = match.group(0)
        if name.casefold() in known or name.split(" ")[0].casefold() in PLAIN_LATIN_WORDS:
            continue
        if name not in found:
            found.append(name)
    return found


def repo_words(text: str) -> list[str]:
    # 사용자가 채울 빈칸 속 말은 글이 아니라서 세지 않는다
    return _REPO_WORD.findall(_MORE_BLANK.sub("", text))


def ends_with_ambition(text: str) -> bool:
    """빈칸 줄을 뺀 마지막 두 문장에 입사 후 하고 싶은 일을 말하는 말이 있는지 본다."""
    sentences = [sentence for sentence in split_sentences(text) if not has_blank(sentence)]
    return any(word in sentence for sentence in sentences[-2:] for word in _AMBITION_WORDS)


def tech_names(text: str, known_names: str) -> list[str]:
    """글에 쓴 기술 이름을 하나씩. 띄어 쓴 영문 낱말이 이어지면('Spring Boot') 한 이름이다.

    카드 출처·회사·문항에 있는 낱말과 인사 담당자도 아는 줄임말(AI·PR 등)은 세지 않는다.
    """
    found: list[str] = []
    for name in _names_in(text, known_names):
        if name not in found:
            found.append(name)
    return found


def _names_in(text: str, known_names: str) -> list[str]:
    skip = {term.casefold() for term in _LATIN_TERM.findall(known_names)} | PLAIN_LATIN_WORDS
    names: list[str] = []
    for run in _LATIN_RUN.finditer(text):
        kept = [term for term in run.group(0).split(" ") if term.casefold() not in skip]
        if kept:
            names.append(" ".join(kept).casefold())
    return names


def _densest(text: str, known_names: str) -> Optional[int]:
    counts = [len(_names_in(sentence, known_names)) for sentence in split_sentences(text)]
    return counts.index(max(counts)) + 1 if counts and max(counts) > 0 else None


def sentence_of(value: str, text: str) -> Optional[int]:
    """값이 낱말·숫자로 따로 선 첫 자리의 문장 번호. 'JavaScript' 안의 'Java', '130%' 안의 '30%' 는 건너뛴다."""
    match = re.search(rf"(?<![0-9A-Za-z.,]){re.escape(value)}(?![0-9A-Za-z])", text)
    if match is None:
        return None
    seq, start = 0, 0
    for end, next_start in [(brk.start(), brk.end()) for brk in _SENTENCE_BREAK.finditer(text)] + [(len(text), len(text))]:
        if text[start:end].strip():
            seq += 1
            if start <= match.start() < end:
                return seq
        start = next_start
    return None


def _normalize(number: str) -> str:
    return number.replace(",", "")
