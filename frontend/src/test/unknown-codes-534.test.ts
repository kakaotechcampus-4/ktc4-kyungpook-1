import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ContractError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { errorView } from '@/api/errorView';
import { matchingApi, MatchCategory, MatchDetail, MatchTarget } from '@/api/matching';
import { profileApi } from '@/api/profile';
import {
  ActiveJob, Card, CardSummary, CardSummaryList, InterviewTurn, Job, JobErrorCode, JobResult, Me, RepoSummary, lenientList, unknownAs,
} from '@/api/schemas';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleDetail from '../../docs/examples/matching/detail.json';
import sampleList from '../../docs/examples/matching/list.json';

/**
 * #534 — 서버가 화면이 모르는 코드를 하나 보내도 응답 전체를 버리지 않는다.
 * 다만 기본값은 사실을 꾸미지 않는 중립 값일 때만 둔다. 둘 중 어느 쪽인지 칸마다 아래에 적었다.
 */
beforeEach(resetDb);
afterEach(() => vi.restoreAllMocks());

const raw = (path: string, method = 'GET', body: Record<string, unknown> = {}) => {
  const [p, qs] = path.split('?');
  return handle(method, p, new URLSearchParams(qs ?? ''), body, true).data;
};
type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
const clone = <T>(value: T): T => structuredClone(value);
const job = (extra: Json = {}): Json => ({
  jobId: 'job_x', type: 'ANALYZE', state: 'FAILED', partial: false, steps: [], errorCode: 'GITHUB_UNAVAILABLE', retryable: true, retryAfterSec: null,
  startedAt: '2026-10-30T00:00:00Z', updatedAt: '2026-10-30T00:00:00Z', finishedAt: '2026-10-30T00:00:08Z', result: null, pollAfterMs: 2000, ...extra,
});
const step = (extra: Json = {}): Json => ({ key: 'COMMITS', state: 'DONE', done: 1, total: 1, ...extra });
const turn = (): Json => (raw('/cards/card_01/interview') as Json[])[0] ?? raw('/cards/card_01/interview', 'POST', { field: 'T' });
const serve = (body: unknown) => vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ data: body, error: null }), { status: 200 }));

describe('기본값을 둔 칸 — 모르는 코드가 와도 응답을 버리지 않는다', () => {
  const cardWith = (extra: Json) => ({ ...(raw('/cards/card_01') as Json), ...extra });
  const rows: [string, () => unknown, unknown][] = [
    ['Job.errorCode 는 일반 오류', () => Job.parse(job({ errorCode: 'GITHUB_QUOTA_EXCEEDED' })).errorCode, 'INTERNAL_ERROR'],
    ['Card.draftFailure 는 일반 오류', () => Card.parse(cardWith({ draftFailure: 'AI_QUOTA_EXCEEDED' })).draftFailure, 'INTERNAL_ERROR'],
    ['Me.plan 은 모름(null)', () => Me.parse({ ...(raw('/me') as Json), plan: 'PRO' }).plan, null],
    ['Contribution.level 은 모름(null) — 기여 정도를 지어내지 않는다', () => RepoSummary.parse({ ...(raw('/repos') as Json[])[0], contribution: { mine: 1, team: 9, ratio: 0.1, level: 'TRIVIAL' } }).contribution.level, null],
    ['ActiveJob.type 은 종류 없음', () => ActiveJob.parse({ jobId: 'j', state: 'RUNNING', type: 'REPORT', userRepositoryId: null, repoName: null, startedAt: '2026-10-30T00:00:00Z', pollAfterMs: 1000 }).type, undefined],
    ['JobResult.verdict 는 칸 없음', () => JobResult.parse({ verdict: 'MAYBE' }).verdict, undefined],
    ['CardSummary.sourceType 은 모름 — 직접 작성으로 읽지 않는다', () => CardSummary.parse({ ...(raw('/cards') as Json[])[0], sourceType: 'DOCS' }).sourceType, undefined],
    ['InterviewTurn.questionType 은 모름(null)', () => InterviewTurn.parse({ ...turn(), questionType: 'WARM_UP' }).questionType, null],
    ['InterviewTurn.sourceType 은 모름(null)', () => InterviewTurn.parse({ ...turn(), sourceType: 'WIKI' }).sourceType, null],
    ['InterviewTurn.askedBy 는 모름(null)', () => InterviewTurn.parse({ ...turn(), askedBy: 'SYSTEM' }).askedBy, null],
    ['MatchTarget.category 는 기타', () => MatchTarget.parse({ ...sampleList.data[0], category: 'LEGAL' }).category, 'ETC'],
    ['MatchTarget.experience.level 은 경력 표시 없음', () => MatchTarget.parse({ ...sampleList.data[0], experience: { level: 'ANY', minYears: 3 } }).experience, { level: 'UNSPECIFIED', minYears: 3 }],
  ];
  it.each(rows)('%s', (_name, read, expected) => {
    expect(read()).toEqual(expected);
  });

  it('아는 코드와 null 은 그대로 읽는다 — 기본값이 아는 값을 덮지 않는다', () => {
    expect(Job.parse(job({ errorCode: 'GITHUB_RATE_LIMITED' })).errorCode).toBe('GITHUB_RATE_LIMITED');
    expect(Job.parse(job({ errorCode: null })).errorCode).toBeNull();
    expect(Card.parse(cardWith({ draftFailure: 'GITHUB_UNAUTHORIZED' })).draftFailure).toBe('GITHUB_UNAUTHORIZED');
    expect(Card.parse(cardWith({ draftFailure: null })).draftFailure).toBeNull();
    expect(Me.parse({ ...(raw('/me') as Json), plan: 'FREE' }).plan).toBe('FREE');
    expect(MatchTarget.parse(sampleList.data[0]).category).toBe('BACKEND');
    expect(MatchTarget.parse(sampleList.data[0]).experience.level).toBe('ENTRY');
  });

  it('칸이 빠졌거나 모양이 틀린 값은 모르는 코드가 아니라 계약 위반이다 — 오류 없는 작업에 오류 문구가 붙지 않는다', () => {
    const { errorCode: _omitted, ...withoutCode } = job({ state: 'RUNNING' });
    void _omitted;
    expect(Job.safeParse(withoutCode).success).toBe(false);
    expect(Job.safeParse(job({ errorCode: 7 })).success).toBe(false);
    expect(Job.safeParse(job({ errorCode: {} })).success).toBe(false);
    const { plan: _plan, ...withoutPlan } = raw('/me') as Json;
    void _plan;
    expect(Me.safeParse(withoutPlan).success).toBe(false);
    expect(MatchTarget.safeParse({ ...sampleList.data[0], category: undefined }).success).toBe(false);
    expect(MatchTarget.safeParse({ ...sampleList.data[0], category: 3 }).success).toBe(false);
  });

  it('내보낸 enum 은 그대로 엄격하다 — 요청 검사와 "이 코드를 아는가" 확인에 쓴다', () => {
    expect(JobErrorCode.safeParse('GITHUB_QUOTA_EXCEEDED').success).toBe(false);
    expect(MatchCategory.safeParse('LEGAL').success).toBe(false);
  });

  it('unknownAs 는 문자열인데 목록에 없는 값만 바꾼다', () => {
    const schema = unknownAs(MatchCategory, 'ETC');
    expect(schema.parse('QA')).toBe('QA');
    expect(schema.parse('LEGAL')).toBe('ETC');
    expect(schema.safeParse(undefined).success).toBe(false);
    expect(schema.safeParse(null).success).toBe(false);
  });
});

describe('남긴 칸 — 모르는 값은 검증 실패이고, 화면은 "응답을 확인하지 못했어요"로 떨어진다', () => {
  const card = () => raw('/cards/card_01') as Json;
  const board = () => raw('/repos/r_auth/candidates') as Json;
  const detail = () => clone(sampleDetail.data) as Json;
  const supported = (d: Json) => d.conditions.find((c: Json) => c.supports.length).supports[0];
  const profile = (extra: Json = {}, row: Json = {}) => ({
    educations: [{ level: 'BACHELOR', school: '가온대', major: null, status: 'GRADUATED', startMonth: null, endMonth: null, gpa: null, gpaMax: null, ...row }],
    careers: [], languages: [], certifications: [], savedAt: null, ...extra,
  });
  const set = (base: Json, patch: (draft: Json) => void) => { const draft = clone(base); patch(draft); return draft; };
  // [이유, 호출, 모르는 값을 하나 넣은 응답]. 이유는 PR 본문 표와 같다.
  const rows: [string, () => Promise<unknown>, Json][] = [
    ['Job.state: 완료·실패·진행 중 어느 쪽으로 읽어도 폴링·전환·알림이 달라진다', () => endpoints.job('j'), job({ state: 'PAUSED' })],
    ['Job.type: 레포 정리인지 카드 초안인지에 따라 화면과 이동 링크가 갈린다', () => endpoints.job('j'), job({ type: 'REPORT' })],
    ['JobStep.key: 단계 이름을 지어낼 수 없다', () => endpoints.job('j'), job({ steps: [step({ key: 'UPLOAD' })] })],
    ['JobStep.state: 완료로 읽으면 안 읽은 것을 읽었다고 한다', () => endpoints.job('j'), job({ steps: [step({ state: 'FAILED' })] })],
    ['ActiveJob.state: 진행 중으로 읽으면 끝난 작업을 계속 기다린다', () => endpoints.activeJobs(), { jobs: [{ jobId: 'j', state: 'PAUSED', type: 'ANALYZE', userRepositoryId: null, repoName: null, startedAt: '2026-10-30T00:00:00Z', pollAfterMs: 1000 }] }],
    ['Candidate.status: NEW 로 읽으면 이미 쓴 후보가 다시 고를 수 있게 된다', () => endpoints.candidates('r_auth'), set(board(), (d) => { d.candidates[0].status = 'ARCHIVED'; })],
    ['Candidate.type: 후보 이름표와 커밋 묶음 동작이 갈린다', () => endpoints.candidates('r_auth'), set(board(), (d) => { d.candidates[0].type = 'DISCUSSION'; })],
    ['CandidateBoard.verdict: EMPTY 로 읽으면 후보가 있어도 없다고 하고, 아니면 이유 없이 빈 보드가 된다', () => endpoints.candidates('r_auth'), set(board(), (d) => { d.verdict = 'MAYBE'; })],
    ['Card.kind: 기술·정성 어느 쪽으로 읽어도 틀린 이름표가 붙는다', () => endpoints.card('card_01'), set(card(), (d) => { d.kind = 'HYBRID'; })],
    ['Card.status: CONFIRMED 로 읽으면 확정하지 않은 카드가 근거로 쓰인다', () => endpoints.card('card_01'), set(card(), (d) => { d.status = 'ARCHIVED'; })],
    ['CardVersion.source: AI_DRAFT 가 아니면 "고쳤다"고 읽힌다', () => endpoints.card('card_01'), set(card(), (d) => { d.version.source = 'IMPORT'; })],
    ['Evidence.type: COMMIT 으로 읽으면 가짜 커밋 링크, 아니면 "내가 말한 것"으로 센다', () => endpoints.card('card_01'), set(card(), (d) => { d.evidence[0].type = 'WIKI'; })],
    ['Evidence.authoredBy: 누가 쓴 문장인지를 지어낼 수 없다', () => endpoints.card('card_01'), set(card(), (d) => { d.evidence[0].authoredBy = 'TEAM'; })],
    ['Evidence.field: 어느 칸의 근거인지 알 수 없다', () => endpoints.card('card_01'), set(card(), (d) => { d.evidence[0].field = 'X'; })],
    ['DroppedField.reason: 빈 이유 문구·다시 만들기 버튼·"결과 수치 없음" 안내가 이유에 따라 갈린다', () => endpoints.card('card_01'), set(card(), (d) => { d.droppedFields[0].reason = 'LOW_QUALITY'; })],
    ['Card.candidate.type: "PR #42" 같은 출처 이름과 링크가 종류에 따라 갈린다', () => endpoints.card('card_01'), set(card(), (d) => { d.candidate.type = 'DISCUSSION'; })],
    ['VersionListItem.source: 이력의 출처 문구를 지어낼 수 없다', () => endpoints.versions('card_01'), [{ versionNo: 1, source: 'IMPORT', createdAt: '2026-10-30T00:00:00Z', summary: '요약', current: true }]],
    ['InterviewTurn.field: 어느 칸을 묻는 질문인지 알 수 없다', () => endpoints.interviewTurns('card_01'), [{ ...turn(), field: 'X' }]],
    ['ConfirmResult.status: 확정됐는지 알 수 없다', () => endpoints.confirm('card_01', { edited: false, maskedFields: [] }), { cardId: 'card_01', status: 'ARCHIVED', versionNo: 1, usedCandidateIds: [], remainingCandidates: 0, repoId: null }],
    ['MatchTarget.fit: null("카드를 확정하면 등급이 나와요")로 읽으면 확정한 카드가 있어도 틀린 이유를 댄다', () => matchingApi.list(), [{ ...sampleList.data[0], fit: 'E' }]],
    ['MatchDetail.conditions[].type: 필수·우대 칸에 못 들어가 조건이 사라지고 N/M 이 어긋난다', () => matchingApi.detail(sampleDetail.data.id), set(detail(), (d) => { d.conditions[0].type = 'BONUS'; })],
    ['MatchDetail.supports[].field: 어느 문장이 조건을 받치는지 알 수 없다', () => matchingApi.detail(sampleDetail.data.id), set(detail(), (d) => { supported(d).field = 'S'; })],
    ['MatchDetail.supports[].cardKind: 카드 종류 이름표를 지어낼 수 없다', () => matchingApi.detail(sampleDetail.data.id), set(detail(), (d) => { supported(d).cardKind = 'HYBRID'; })],
    ['Profile 학력 수준: 모르는 값을 다른 값으로 읽고 저장하면 서버 값을 덮어쓴다', () => profileApi.get(), profile({}, { level: 'POST_DOCTOR' })],
    ['Profile 경력 형태: 같은 이유로 덮어쓸 수 있다', () => profileApi.get(), profile({ careers: [{ company: '가온', role: null, type: 'DISPATCH', startMonth: '2024-01', endMonth: null }] })],
    ['Profile 어학 시험: 같은 이유로 덮어쓸 수 있다', () => profileApi.get(), profile({ languages: [{ test: 'DELF', testName: null, score: '800', acquiredMonth: null }] })],
  ];

  it.each(rows)('%s', async (_reason, call, body) => {
    serve(body);
    const failure = await call().then(() => null, (error: unknown) => error);
    expect(failure).toBeInstanceOf(ContractError);
    // 실패한 이유가 그 모르는 값 하나뿐이다(응답의 다른 칸이 틀려서가 아니다)
    const issues = (failure as ContractError).issues as { code: string }[];
    expect(issues.length).toBeGreaterThan(0);
    expect(issues.every((issue) => issue.code === 'invalid_enum_value')).toBe(true);
    // 화면이 쓰는 한 가지 문구 — 원인을 서버 응답에 있다고만 말하고 값을 지어내지 않는다
    expect(errorView(failure, 'read')).toMatchObject({ title: '응답을 확인하지 못했어요', canRetry: false });
  });
});

describe('목록은 원소 하나씩 읽는다 (카드 목록만)', () => {
  const cards = () => raw('/cards') as Json[];

  it('읽을 수 없는 카드 한 장만 빼고 나머지를 순서 그대로 돌려주며 몇 장을 뺐는지 센다', () => {
    const list = cards();
    const broken = [list[0], { ...list[1], kind: 'HYBRID' }, list[2], { ...list[3], status: 'ARCHIVED' }, list[4]];
    const { items, skipped } = CardSummaryList.parse(broken);
    expect(items.map((c) => c.id)).toEqual([list[0].id, list[2].id, list[4].id]);
    expect(skipped).toBe(2);
  });

  it('모르는 값이 없으면 하나도 빼지 않는다', () => {
    expect(CardSummaryList.parse(cards())).toMatchObject({ skipped: 0 });
    expect(CardSummaryList.parse(cards()).items).toHaveLength(cards().length);
  });

  it('카드가 없는 것은 정상이다 — 빈 목록 그대로', () => {
    expect(CardSummaryList.parse([])).toEqual({ items: [], skipped: 0 });
  });

  it('카드가 있었는데 한 장도 못 읽으면 "카드 없음"으로 오인하지 않게 실패시킨다', () => {
    expect(CardSummaryList.safeParse(cards().map((c) => ({ ...c, kind: 'HYBRID' }))).success).toBe(false);
    expect(CardSummaryList.safeParse([null]).success).toBe(false);
  });

  it('배열이 아니면 그대로 실패한다', () => {
    expect(CardSummaryList.safeParse({ cards: [] }).success).toBe(false);
    expect(CardSummaryList.safeParse(null).success).toBe(false);
  });

  it('칸이 빠졌거나 모양이 틀린 카드도 같은 규칙으로 그 장만 뺀다', () => {
    const [first, ...rest] = cards();
    const { title: _title, ...untitled } = first;
    void _title;
    const { items, skipped } = CardSummaryList.parse([untitled, ...rest]);
    expect(items).toHaveLength(rest.length);
    expect(skipped).toBe(1);
  });

  it('lenientList 는 어떤 원소 스키마에도 쓸 수 있다', () => {
    const list = lenientList(MatchTarget);
    expect(list.parse([sampleList.data[0], { ...sampleList.data[1], fit: 'E' }])).toMatchObject({ skipped: 1 });
  });

  it('endpoints.cards 는 못 읽은 장을 뺀 배열을 돌려준다 — 기존 호출부는 배열 그대로 쓴다', async () => {
    const list = cards();
    serve([list[0], { ...list[1], kind: 'HYBRID' }, list[2]]);
    const got = await endpoints.cards();
    expect(Array.isArray(got)).toBe(true);
    expect(got.map((c) => c.id)).toEqual([list[0].id, list[2].id]);
  });

  it('MatchDetail 은 목록과 같은 칸 기본값을 이어받는다', () => {
    const parsed = MatchDetail.parse({ ...clone(sampleDetail.data), category: 'LEGAL', experience: { ...sampleDetail.data.experience, level: 'ANY' } });
    expect(parsed.category).toBe('ETC');
    expect(parsed.experience.level).toBe('UNSPECIFIED');
  });
});
