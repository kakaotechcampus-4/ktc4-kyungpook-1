"""작업별 LLM 환경 설정을 검증한다."""

from __future__ import annotations

import pytest

from services.llm_settings import EnvironmentLLMClient, LLMConfigError, load_llm_settings

_ENV_NAMES = (
    "GITORY_LLM_BASE_URL", "GITORY_LLM_API_KEY", "GITORY_LLM_TIMEOUT_SECONDS",
    "GITORY_DIFF_MODEL", "GITORY_DIFF_REASONING_EFFORT",
)


@pytest.fixture(autouse=True)
def clean_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in _ENV_NAMES:
        monkeypatch.delenv(name, raising=False)


def _set_connection(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GITORY_LLM_BASE_URL", "http://127.0.0.1:1/v1")
    monkeypatch.setenv("GITORY_LLM_API_KEY", "test-key")


def test_uses_defaults_with_shared_connection(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_connection(monkeypatch)

    settings = load_llm_settings("DIFF")

    assert (settings.model, settings.reasoning_effort, settings.timeout_seconds) == ("gpt-5.6-luna", "medium", 120.0)


def test_reads_task_specific_model_and_effort(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_connection(monkeypatch)
    monkeypatch.setenv("GITORY_DIFF_MODEL", "gpt-5.6-terra")
    monkeypatch.setenv("GITORY_DIFF_REASONING_EFFORT", "high")
    monkeypatch.setenv("GITORY_LLM_TIMEOUT_SECONDS", "60")

    settings = load_llm_settings("DIFF")

    assert (settings.model, settings.reasoning_effort, settings.timeout_seconds) == ("gpt-5.6-terra", "high", 60.0)


@pytest.mark.parametrize(
    ("name", "value"),
    [("GITORY_DIFF_REASONING_EFFORT", "max"), ("GITORY_LLM_TIMEOUT_SECONDS", "abc"), ("GITORY_LLM_TIMEOUT_SECONDS", "0")],
)
def test_rejects_invalid_values(monkeypatch: pytest.MonkeyPatch, name: str, value: str) -> None:
    _set_connection(monkeypatch)
    monkeypatch.setenv(name, value)

    with pytest.raises(LLMConfigError):
        load_llm_settings("DIFF")


def test_missing_connection_fails_only_when_called() -> None:
    client = EnvironmentLLMClient("DIFF")  # 앱 시작 시점에는 실패하지 않는다.

    with pytest.raises(LLMConfigError, match="GITORY_LLM_BASE_URL, GITORY_LLM_API_KEY"):
        client.call("system", {}, object, 100)


def test_grouping_client_uses_same_settings_rules() -> None:
    from services.grouping_llm import EnvironmentGroupingModelClient, GroupingLlmConfigError

    with pytest.raises(GroupingLlmConfigError, match="GITORY_LLM_BASE_URL"):
        EnvironmentGroupingModelClient._build_client()
