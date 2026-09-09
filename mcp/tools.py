"""MCP 도구(tool) 정의 모듈.

Agent가 되묻기/매칭 과정에서 호출할 수 있는 도구들을 정의한다.
"""


def search_commits(query: str) -> list[dict]:
    """커밋 기록을 검색하는 도구 뼈대.

    Args:
        query: 검색어.

    Returns:
        검색된 커밋 목록.
    """
    # TODO: 커밋 검색 로직 연결
    raise NotImplementedError


def search_talent_profile(company: str) -> str:
    """기업 인재상 정보를 조회하는 도구 뼈대.

    Args:
        company: 기업명.

    Returns:
        인재상 텍스트.
    """
    # TODO: 인재상 저장소 조회 로직 연결
    raise NotImplementedError


def register_tools(server) -> None:
    """MCP 서버에 도구들을 등록한다.

    Args:
        server: MCP 서버 인스턴스.
    """
    # TODO: server 에 위 도구들을 등록
    raise NotImplementedError
