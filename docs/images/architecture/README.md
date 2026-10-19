# README 아키텍처 다이어그램

2026-10-19, `develop` 커밋 `264ee671`의 배포 구성과 처리 흐름을 기준으로 작성했다. 실제 서버의 가동 상태를 의미하지 않는다.

| 파일 | 내용 |
| --- | --- |
| [system-architecture.svg](system-architecture.svg) | 단일 EC2·Docker Compose의 서비스 경계, Caddy 진입점, 내부 통신과 외부 API |
| [ai-pipeline.svg](ai-pipeline.svg) | 개발 기록 수집부터 경험 후보 선택, diff·STAR 분석과 카드 보강까지의 흐름 |

- SVG 안에 아이콘·글자·화살표를 직접 작성했다. 외부 이미지·폰트·스크립트를 불러오지 않는다.
- 모델 선택은 GPT-5.6 Luna 기준이다. 실제 호출 설정과 기능 스위치는 배포 환경에 따른다.
- 보강 질문은 템플릿 기반이며, 사용자 답변은 백엔드가 원문 그대로 새 버전으로 저장한다. 선택 설정의 답변 평가는 규칙 기반이다.

구조나 모델을 변경하면 아래 문서·코드와 대조해 SVG와 루트 README 설명을 함께 수정한다.

- [배포 가이드](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/264ee671/docs/DEPLOYMENT.md)
- [AI README](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/264ee671/ai/README.md)
- [AI AnalysisPipeline](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/264ee671/ai/services/analysis_pipeline.py)
