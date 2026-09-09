/**
 * <b>카드</b> — 근거와 답변을 STAR 로 묶고, 되묻기로 채우고, 사용자 확인을 거쳐 확정한다.
 *
 * <p>소유 테이블: {@code card} · {@code card_statement} · {@code statement_evidence}
 * · {@code interview_turn} · {@code interview_option}
 *
 * <h2>왜 인터뷰와 근거가 이 안에 있나</h2>
 * 처음에는 {@code interview}·{@code evidence} 를 최상위 모듈로 뒀다가 합쳤다.
 * <ul>
 *   <li>{@code interview_turn.card_id → card.id} 로 카드에 종속되는데,
 *       거꾸로 {@code card_statement.source_turn_id → interview_turn.id} 로 되돌아온다.
 *       <b>모듈로 가르면 양방향 의존이 되어 순환 참조 금지 규칙을 스스로 위반한다.</b></li>
 *   <li>{@code evidence} 는 소유할 테이블이 없었다. {@code git_commit} 은 {@code ingest} 것,
 *       {@code candidate_commit} 은 {@code recommend} 것이고 {@code statement_evidence} 만
 *       여기 것이다. <b>가진 게 없는 모듈은 모듈이 아니라 계층이다.</b></li>
 * </ul>
 *
 * <h2>안쪽 세 갈래 — 이 구분이 경계다</h2>
 * <table>
 *   <tr><td>{@code domain.compose}</td>
 *       <td>카드 초안 조립. {@code agent} 를 부른다</td></tr>
 *   <tr><td>{@code domain.interview}</td>
 *       <td>되묻기 턴. {@code agent} 를 부른다</td></tr>
 *   <tr><td>{@code domain.evidence}</td>
 *       <td><b>sha 실재 검증 · 문장↔커밋 연결. {@code agent} 를 부를 수 없다</b></td></tr>
 * </table>
 *
 * <p>마지막 줄이 핵심이다. 근거 sha 가 안 맞을 때 <b>"모델한테 다시 물어보자"가
 * 자연스러운 수정이 되면 안 된다.</b> 실측에서 유령 sha·오귀속이 나온 경로가 그 모양이다.
 * sha 검증은 입력에 대한 결정적 재조회이고, 모델 판단이 끼면 안 된다.
 * {@code ModuleBoundaryTest} 가 이걸 강제한다.
 *
 * <h2>그 밖의 규칙</h2>
 * <ul>
 *   <li><b>AI 생성 문장은 초안이다.</b> 사용자 확인 없이 확정 카드로 저장하지 않는다(ADR-0004).
 *       {@code DRAFT → CONFIRMED} 전이는 사용자 행동으로만 일어난다.</li>
 *   <li><b>문장마다 출처 분류가 붙는다.</b> {@code card_statement.evidence_type} 이
 *       {@code NOT NULL} 이라 분류 없는 문장은 저장할 수 없다(ADR-0001).</li>
 *   <li>사용자가 모른다고 하거나 건너뛰면 <b>추정으로 채우지 않는다.</b>
 *       {@code interview_turn.outcome} 에 {@code later}·{@code skipped} 를 남긴다.</li>
 *   <li>버전은 append-only 다. 고치면 덮어쓰지 않고 {@code version_no} +1 로 새 행을 넣는다.</li>
 *   <li><b>기여율 수치를 카드 문장에 넣지 않는다.</b> "92% 기여했습니다"는 수치화된 자기 주장이다.
 *       기여율은 후보 화면의 경고 배지로만 쓴다.</li>
 * </ul>
 *
 * <h2>실측: T(과제) 칸은 기본적으로 비는 칸이다</h2>
 * 행동(A)과 결과(R)는 코드와 커밋에 남아 8/8·8/8 로 채워졌지만, T — "그 중 내가 맡기로 한
 * 범위" — 는 저장소에 남지 않아 snap 8개 카드 전부에서 비었다.
 * <b>입력에 없는 것을 비워 둔 정상 동작이다.</b> 채우려 들면 지어내기 시작한다.
 * 문장 단위 모델에서는 빈 칸 = 그 {@code star_slot} 에 행이 없음이고, 그 자리를
 * {@code domain.interview} 가 질문으로 받는다.
 */
package com.gitory.backend.card;
