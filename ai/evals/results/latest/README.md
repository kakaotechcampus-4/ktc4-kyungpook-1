# 최신 STAR·질문 비교 결과 artifact

이 폴더는 `MODEL_SELECTION_REPORT.md` 4-5절의 재현 결과를 공유하는 위치다. PR에서 검토할 수 있는 집계 artifact는 `comparison_manifest.json`이다.

저장소 후보 1건의 모델별 운영 비용 집계와 구조화 출력 실패는
`repository_cost_manifest.json`에서 확인한다.

- 입력 fixture: `evals/fixtures/generated/star_draft.json`, `evals/fixtures/generated/question_gen.json`
- 결과 JSON 파일명: `luna_star_draft.json`, `luna_question_gen.json`, `claude_star_draft.json`, `claude_question_gen.json`
- 로컬 API 키·endpoint 설정 파일과 생성 결과에는 저장소·사용자 정보가 포함될 수 있으므로 **상세 실행 결과 JSON 자체는 커밋하지 않는다.** 팀의 제한된 공유 공간에 동일 파일명으로 보관한다.

공유 드라이브 접근 권한이 없는 경우, 아래 명령으로 동일 fixture와 모델 설정으로 결과를 다시 생성한다. 정확한 명령과 모델 파라미터는 보고서 9절을 따른다.
