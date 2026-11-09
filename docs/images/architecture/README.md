# README 아키텍처 다이어그램

**2026-11-09**, `develop` 커밋 `28c79ac1`의 구현과 배포 설정 기준으로 갱신했다.
운영 서버의 개별 환경변수 값이나 가동 상태를 나타내는 문서가 아니다.

| 파일 | 내용 |
| --- | --- |
| [system-architecture.svg](system-architecture.svg) | Caddy·React → Spring Boot → FastAPI, PostgreSQL·Redis·ChromaDB·db-backup 역할, 외부 연결(GitHub·LLM·채용 사이트·Gmail·CloudWatch)과 GitHub Actions 배포 |
| [ai-pipeline.svg](ai-pipeline.svg) | 경험 카드 생성·검증·확정, 공고 조건 추출·색인·매칭, 문항별 자소서 생성·보강·첨삭 |

- SVG는 글자·도형·화살표를 직접 작성했다. 외부 이미지·폰트·스크립트를 불러오지 않는다.
- 시스템 구성도의 로고는 [Simple Icons](https://simpleicons.org) 16.37.0(CC0)의 경로를 SVG 안에 그대로 넣었다. Simple Icons 에 없는 EC2·CloudWatch·ChromaDB·LLM·채용 사이트·백업은 단순한 도형으로 그렸다.
- 시스템 구성도의 Redis·ChromaDB는 서로 역할이 다르다. Redis는 사용자별 호출 횟수, ChromaDB는 AI의 공고 조건 벡터 색인이다.
- DB 백업은 별도 `db-backup` 컨테이너, ChromaDB는 FastAPI가 사용하는 라이브러리·볼륨으로 표현했다.
- 파이프라인의 파란 칸은 사용자가 선택·확인·수정하는 단계다. 하나의 칸에 AI 처리와 사용자 행동이 함께 있을 수 있다.
- 보강 질문도 LLM을 사용한다. 사용자 답변의 출처를 보존하며, 자소서는 카드와 답변을 바탕으로 작성·점검·보강한다.
- 기본 생성 모델은 `gpt-5.6-luna`이며, 작업별 모델·추론 강도와 임베딩 제공자는 환경변수로 달라질 수 있다.

## 확인한 소스

| 대상 | 소스 |
| --- | --- |
| 컨테이너 · 네트워크 · 볼륨 · 로그 · 기능 스위치 | [compose.yaml](../../../deploy/compose.yaml) |
| HTTPS · 정적 파일 · API 프록시 | [Caddyfile](../../../deploy/Caddyfile) |
| 배포 · 롤백 · 경보 · 백업 | [배포 가이드](../../DEPLOYMENT.md) |
| 모델 기본값과 작업별 환경변수 | [llm_settings.py](../../../ai/services/llm_settings.py) |
| 공고 조건 임베딩 · 색인 | [embedding.py](../../../ai/rag/embedding.py) · [vector_store.py](../../../ai/rag/vector_store.py) |
| AI API와 파이프라인 | [AI README](../../../ai/README.md) |
| 근거 검증 | [가드레일](../../GUARDRAILS.md) |

구조나 기능이 바뀌면 SVG·루트 README·이 문서를 함께 갱신한다.
