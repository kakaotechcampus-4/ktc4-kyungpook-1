"""공고 상세 본문에서 근거 인용이 있는 직무 조건만 추출한다."""

from __future__ import annotations

import hashlib
import logging
import re
import unicodedata
from typing import Optional, Protocol

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from schemas.matching import (
    ExperienceLevel,
    ExtractionOutcome,
    JobCategory,
    JobCriterion,
    JobCriterionType,
    JobExperience,
    JobPostingContentStatus,
    JobPostingDetailExtractionRequest,
    JobPostingDetailExtractionResult,
)
from services.llm_settings import EnvironmentLLMClient
from services.llm_structured_client import LLMStructuredCallError


SYSTEM_PROMPT = """당신은 채용공고 상세 본문을 구조화하는 추출기다.
입력 본문에 명시된 내용만 사용한다. 추론하거나 일반적인 직무 지식을 추가하지 않는다.

criteria: 각 조건은 RESPONSIBILITY(담당 업무), REQUIRED(자격 요건), PREFERRED(우대 사항) 중 하나로 분류한다.
기술 항목은 자격 요건 문단 안이면 REQUIRED, 우대 사항 문단 안이면 PREFERRED로 넣는다.
자격 요건·우대 사항 문단 밖의 기술 항목(기술 스택 목록 등)과 어느 문단인지 확인할 수 없는 기술은 조건에 넣지 않는다.
source_quote에는 판단 근거가 된 원문을 글자 하나 바꾸지 말고 그대로 복사한다.
text에는 source_quote에 나온 말만 사용해 요약하고, 인용에 없는 기술·자격·수치를 덧붙이지 않는다.
text에 '필수', '우대', '자격 요건' 같은 문단 표시어를 넣지 않는다. 종류는 criterion_type으로만 나타낸다.
경력 요건은 criteria에 넣지 않고 experience에만 적는다.
광고 문구, 복지, 회사 소개, 채용 담당자 개인정보는 제외한다.

experience: 공고마다 하나다. 본문에 적힌 경력만 쓴다.
신입이 지원할 수 있다고 적은 표현(신입 지원 가능·경력 무관·인턴·최소 0년)이 하나라도 있으면 level은 ENTRY이고 min_years는 비운다.
'신입 멘토링 제공'처럼 신입의 지원을 허용한다는 뜻이 아닌 문장은 근거로 쓰지 않는다.
그 밖에 'N년 이상'·'N+ years'·'at least N years'가 적혀 있으면 EXPERIENCED이고, 여러 개면 가장 작은 N을 min_years에 쓴다.
'3~8년'·'8–10 years' 같은 범위는 아래 끝 값을 min_years에 쓴다.
'1년 미만'·'3년 이하'·'up to 2 years'처럼 상한만 적힌 경력은 최소가 0년이라 ENTRY이고 min_years는 비운다.
경력 이야기가 없으면 UNSPECIFIED이고 min_years와 source_quote는 비운다.
ENTRY와 EXPERIENCED의 source_quote에는 판단 근거가 된 원문을 그대로 복사한다.

job_category: BACKEND, FRONTEND, MOBILE, DEVOPS, DATA, AI_ML, SECURITY, QA, ETC 중 하나다.
풀스택은 담당 업무가 더 많은 쪽(BACKEND 또는 FRONTEND)을 고른다.
게임 클라이언트·임베디드는 1차에 따로 분류하지 않고 ETC를 쓴다.
어디에도 맞지 않으면 ETC를 쓴다.
"""


logger = logging.getLogger(__name__)

# 스키마의 text 길이 상한과 같은 값이어야 인용을 text 로 옮겨 담을 수 있다.
_MAX_TEXT_CHARS = next(
    meta.max_length
    for meta in JobCriterion.model_fields["text"].metadata
    if hasattr(meta, "max_length")
)
_EDGE_PUNCTUATION = "()[]{}<>,.:;!?\"'`·•-–—*"
_UPPER_BOUND = re.compile(r"미만|이하|\bunder\b|\bup to\b|\bless than\b", re.IGNORECASE)
_LOWER_BOUND = re.compile(r"이상|\d\s*(?:년|years?)?\s*\+|or more|at least|more than", re.IGNORECASE)


class ExtractedExperience(BaseModel):
    """모델이 낸 경력.

    원문 인용을 확인한 뒤에야 JobExperience가 된다.
    """

    model_config = ConfigDict(extra="forbid")

    level: ExperienceLevel
    min_years: Optional[int] = None
    source_quote: Optional[str] = None


class ExtractedPosting(BaseModel):
    """모델이 돌려주는 조건·경력·분류.

    값 검증은 이 모델과 추출기가 나눠 맡는다.
    """

    model_config = ConfigDict(extra="forbid")

    criteria: list[JobCriterion] = Field(default_factory=list, max_length=100)
    experience: ExtractedExperience
    job_category: JobCategory


class JobPostingExtractionModel(Protocol):
    """실제 Luna 호출과 테스트 대역을 분리하는 모델 포트."""

    def extract(self, request: JobPostingDetailExtractionRequest) -> ExtractedPosting:
        """상세 본문에서 인용이 있는 직무 조건과 경력·직무 분류를 반환한다."""


class LunaJobPostingExtractionModel:
    """팀에서 확정한 Luna 구조화 출력 구현체.

    설정은 첫 호출 때 읽는다.
    """

    def __init__(self) -> None:
        self._client = EnvironmentLLMClient("JOB_POSTING")

    def extract(self, request: JobPostingDetailExtractionRequest) -> ExtractedPosting:
        result = self._client.call(
            system_prompt=SYSTEM_PROMPT,
            payload={
                "job_title": request.job_title,
                "posting_text": request.raw_text,
            },
            output_model=ExtractedPosting,
            max_completion_tokens=2000,
        )
        return result.output


class JobPostingEvidenceMismatch(ValueError):
    """모델이 반환한 조건의 근거가 원문 인용으로 확인되지 않는 경우."""


class JobPostingDetailExtractor:
    """해시 비교와 원문 인용 검증을 모델 밖에서 강제한다."""

    def __init__(self, model: JobPostingExtractionModel) -> None:
        self._model = model

    def extract(
        self,
        request: JobPostingDetailExtractionRequest,
    ) -> JobPostingDetailExtractionResult:
        raw_text = request.raw_text.strip()
        content_hash = hashlib.sha256(raw_text.encode("utf-8")).hexdigest()
        if request.previous_content_hash == content_hash:
            return JobPostingDetailExtractionResult(
                source=request.source,
                external_posting_id=request.external_posting_id,
                source_url=request.source_url,
                content_hash=content_hash,
                outcome=ExtractionOutcome.UNCHANGED,
                content_status=request.previous_content_status,
                criteria=[],
            )

        extracted = self._model.extract(request)
        criteria = self._validate_and_deduplicate(
            extracted.criteria,
            raw_text,
            request.external_posting_id,
        )
        return JobPostingDetailExtractionResult(
            source=request.source,
            external_posting_id=request.external_posting_id,
            source_url=request.source_url,
            content_hash=content_hash,
            outcome=ExtractionOutcome.UPDATED,
            content_status=(
                JobPostingContentStatus.READY
                if criteria
                else JobPostingContentStatus.METADATA_ONLY
            ),
            criteria=criteria,
            job_category=extracted.job_category,
            experience=self._validate_experience(
                extracted.experience, raw_text, request.external_posting_id
            ),
        )

    @staticmethod
    def _validate_experience(
        experience: ExtractedExperience,
        raw_text: str,
        posting_id: str,
    ) -> JobExperience:
        """경력은 조건과 달리 근거를 못 믿어도 공고를 거절하지 않고 '표시 없음'으로 낮춘다.

        경력은 등급에 들어가지 않아 잘못 믿을 때의 피해가 필터 하나에 그친다.
        """
        if experience.level != ExperienceLevel.UNSPECIFIED:
            checked = _checked_experience(experience, raw_text)
            if checked is not None:
                try:
                    return JobExperience(**checked)
                except ValidationError:
                    pass
            logger.warning("공고 경력 정보 제외: posting=%s", posting_id)
        return JobExperience(level=ExperienceLevel.UNSPECIFIED)

    @staticmethod
    def _validate_and_deduplicate(
        criteria: list[JobCriterion],
        raw_text: str,
        posting_id: str,
    ) -> list[JobCriterion]:
        result: list[JobCriterion] = []
        seen: set[tuple[str, str]] = set()
        dropped: dict[str, int] = {}
        rewritten = 0
        for criterion in criteria:
            quote = criterion.source_quote
            text = criterion.text
            usable = quote in raw_text
            if usable and not _is_derived_from_quote(text, quote):
                # 요약이 인용을 벗어나면 요약을 믿지 않고 원문 인용을 조건 문장으로 쓴다.
                # text 길이 상한을 넘는 인용은 옮겨 담을 수 없어 근거 없는 조건과 같게 다룬다.
                usable = len(quote) <= _MAX_TEXT_CHARS
                text = quote
                rewritten += int(usable)
            if not usable:
                # 필수 조건 하나가 틀리면 등급이 부풀려질 수 있어 공고 전체를 믿지 않는다.
                if criterion.criterion_type is JobCriterionType.REQUIRED:
                    raise JobPostingEvidenceMismatch(
                        "필수 조건의 근거를 공고 원문에서 확인할 수 없습니다."
                    )
                kind = criterion.criterion_type.value
                dropped[kind] = dropped.get(kind, 0) + 1
                continue
            key = (criterion.criterion_type.value, text.casefold())
            if key not in seen:
                seen.add(key)
                result.append(criterion.model_copy(update={"text": text}))
        if dropped:
            logger.warning(
                "공고 조건 제외: posting=%s count=%d types=%s",
                posting_id,
                sum(dropped.values()),
                dict(sorted(dropped.items())),
            )
        if rewritten:
            logger.warning(
                "공고 조건 문장을 인용으로 교체: posting=%s count=%d",
                posting_id,
                rewritten,
            )
        if criteria and not result:
            # 모델이 조건을 냈는데 전부 빠진 결과를 저장하면 해시가 남아 다음 수집에서 다시 뽑지 않는다.
            raise JobPostingEvidenceMismatch("추출된 조건이 모두 근거 검증에서 빠졌습니다.")
        return result


def _checked_experience(experience: ExtractedExperience, raw_text: str) -> Optional[dict]:
    """스키마가 못 보는 근거만 확인한다. 길이·연차 범위는 JobExperience가 거른다."""
    quote = (experience.source_quote or "").strip()
    if not quote or quote not in raw_text:
        return None
    years = experience.min_years
    if experience.level == ExperienceLevel.ENTRY:
        return {"level": ExperienceLevel.ENTRY, "source_quote": quote}
    if years == 0 and re.search(r"(?<!\d)0(?!\d)", quote):
        return {"level": ExperienceLevel.ENTRY, "source_quote": quote}
    # 상한만 있는 경력('1년 미만')은 최소가 0년이라 신입도 지원할 수 있다.
    if _UPPER_BOUND.search(quote) and not _LOWER_BOUND.search(quote):
        return {"level": ExperienceLevel.ENTRY, "source_quote": quote}
    if years is None or re.search(rf"(?<!\d){years}(?!\d)", quote) is None:
        return None
    return {"level": ExperienceLevel.EXPERIENCED, "min_years": years, "source_quote": quote}


def _normalize(value: str) -> str:
    """대소문자·유니코드 표기·공백 차이를 없앤 비교용 문자열."""
    return " ".join(unicodedata.normalize("NFC", value).casefold().split())


def _is_derived_from_quote(text: str, quote: str) -> bool:
    """text의 모든 낱말이 인용 안에 있어야 한다.

    한글 낱말은 조사가 붙은 인용(개발 ↔ 개발합니다)에 들어 있어도 되고, 영문 낱말은 더 긴 낱말의 일부(Go와 Google)면 안 된다.
    """
    normalized_quote = _normalize(quote)
    words = (word.strip(_EDGE_PUNCTUATION) for word in _normalize(text).split())
    return all(_appears_in(word, normalized_quote) for word in words if word)


def _appears_in(word: str, quote: str) -> bool:
    if not word.isascii():
        return word in quote
    return re.search(rf"(?<![a-z0-9]){re.escape(word)}(?![a-z0-9])", quote) is not None


__all__ = [
    "ExtractedExperience",
    "ExtractedPosting",
    "JobPostingDetailExtractor",
    "JobPostingEvidenceMismatch",
    "JobPostingExtractionModel",
    "LLMStructuredCallError",
    "LunaJobPostingExtractionModel",
]
