"""공고 상세 본문에서 근거 인용이 있는 직무 조건만 추출한다."""

from __future__ import annotations

import hashlib
import logging
import re
import unicodedata
from typing import Protocol

from pydantic import BaseModel, ConfigDict, Field

from schemas.matching import (
    CriterionType,
    ExtractionOutcome,
    JobCriterion,
    JobPostingContentStatus,
    JobPostingDetailExtractionRequest,
    JobPostingDetailExtractionResult,
)
from services.llm_settings import EnvironmentLLMClient
from services.llm_structured_client import LLMStructuredCallError


SYSTEM_PROMPT = """당신은 채용공고 상세 본문을 구조화하는 추출기다.
입력 본문에 명시된 내용만 사용한다. 추론하거나 일반적인 직무 지식을 추가하지 않는다.
각 조건은 RESPONSIBILITY, REQUIRED, PREFERRED, SKILL 중 하나로 분류한다.
source_quote에는 판단 근거가 된 원문을 글자 하나 바꾸지 말고 그대로 복사한다.
필수인지 우대인지 본문에서 구분할 수 없으면 RESPONSIBILITY 또는 SKILL로 분류한다.
text에는 source_quote에 나온 말만 사용해 요약하고, 인용에 없는 기술·자격·수치를 덧붙이지 않는다.
광고 문구, 복지, 회사 소개, 채용 담당자 개인정보는 제외한다.
"""


logger = logging.getLogger(__name__)

# 스키마의 text 길이 상한과 같은 값이어야 인용을 text 로 옮겨 담을 수 있다.
_MAX_TEXT_CHARS = next(
    meta.max_length
    for meta in JobCriterion.model_fields["text"].metadata
    if hasattr(meta, "max_length")
)
_EDGE_PUNCTUATION = "()[]{}<>,.:;!?\"'`·•-–—*"


class _ExtractedJobCriteria(BaseModel):
    model_config = ConfigDict(extra="forbid")

    criteria: list[JobCriterion] = Field(default_factory=list, max_length=100)


class JobPostingExtractionModel(Protocol):
    """실제 Luna 호출과 테스트 대역을 분리하는 모델 포트."""

    def extract(self, request: JobPostingDetailExtractionRequest) -> list[JobCriterion]:
        """상세 본문에서 인용이 있는 직무 조건을 반환한다."""


class LunaJobPostingExtractionModel:
    """팀에서 확정한 Luna 구조화 출력 구현체.

    설정은 첫 호출 때 읽는다.
    """

    def __init__(self) -> None:
        self._client = EnvironmentLLMClient("JOB_POSTING")

    def extract(self, request: JobPostingDetailExtractionRequest) -> list[JobCriterion]:
        result = self._client.call(
            system_prompt=SYSTEM_PROMPT,
            payload={
                "job_title": request.job_title,
                "posting_text": request.raw_text,
            },
            output_model=_ExtractedJobCriteria,
            max_completion_tokens=2000,
        )
        return result.output.criteria


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

        criteria = self._validate_and_deduplicate(
            self._model.extract(request),
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
        )

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
                if criterion.criterion_type is CriterionType.REQUIRED:
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
    "JobPostingDetailExtractor",
    "JobPostingEvidenceMismatch",
    "JobPostingExtractionModel",
    "LLMStructuredCallError",
    "LunaJobPostingExtractionModel",
]
