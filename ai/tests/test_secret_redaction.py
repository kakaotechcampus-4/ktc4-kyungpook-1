"""PR 본문 이메일 가리기 규칙을 검증한다."""

from __future__ import annotations

import pytest

from services.secret_redaction import mask_emails


@pytest.mark.parametrize(
    ("text", "masked"),
    [
        ("문의는 dev@example.com 으로", "문의는 [이메일] 으로"),
        ("주소 https://user:pw@host.example.com/x", "주소 https://user:[이메일]/x"),
        ("작성자 a@b.com(이름)", "작성자 [이메일](이름)"),
        ("아이콘 icon@2x.png", "아이콘 icon@2x.png"),
        ("git@github.com:owner/repo 로 받는다", "git@github.com:owner/repo 로 받는다"),
        ('`app@router.get("/")` 를 썼다', '`app@router.get("/")` 를 썼다'),
        ("멘션 @octo · react@18.3.1", "멘션 @octo · react@18.3.1"),
    ],
)
def test_masks_email_addresses_but_keeps_files_ssh_and_code(text: str, masked: str) -> None:
    assert mask_emails(text) == masked
