import { beforeEach, describe, expect, it } from 'vitest';
import { Activity } from '@/api/activities';
import { CardSummary, Card, ManualDraftInput } from '@/api/schemas';
import { activityDeleteLines, activityMeta, activityOwnerLine, activityPeriodLabel } from '@/lib/activityLabels';
import { emptyActivityForm, formFromActivity, toCreateBody, toPatchBody, validateActivityForm, type ActivityFormValues } from '@/lib/activityForm';
import { handle } from '@/mock/router';
import { createScenario } from '@/mock/scenarios';
import { db, resetDb } from '@/mock/store';

const req = (method: string, path: string, body: Record<string, unknown> = {}, headers: Record<string, string> = {}) =>
  handle(method, path, new URLSearchParams(), body, true, headers);
const valid = (over: Partial<ActivityFormValues> = {}): ActivityFormValues => ({ ...emptyActivityForm(), name: '교내 개발 동아리', type: 'CLUB', startMonth: '2025-03', ongoing: true, ...over });

describe('활동 계약 모양 (#477, 계약 #475)', () => {
  it('활동 응답: 끝난 달·역할이 비면 null, 모르는 종류는 null 로 읽어 목록이 깨지지 않는다', () => {
    const read = Activity.parse({ id: 'a', name: '동아리', type: 'CLUB', startMonth: '2025-03', cardCount: 3, confirmedCount: 2, createdAt: '2026-10-01T00:00:00Z' });
    expect(read).toMatchObject({ endMonth: null, role: null, cardCount: 3, confirmedCount: 2 });
    expect(Activity.parse({ ...read, type: 'BRAND_NEW' }).type).toBeNull();
    expect(Activity.safeParse({ ...read, cardCount: -1 }).success).toBe(false);
  });

  it('카드의 activity: 생략은 모름, null 은 활동 밖. 카드 상세와 목록이 같은 모양이다', () => {
    const base = { id: 'c', kind: 'QUALITATIVE', status: 'DRAFT', title: 't', versionNo: 1, star: { S: 'EMPTY', T: 'EMPTY', A: 'EMPTY', R: 'EMPTY' }, updatedAt: '2026-10-01T00:00:00Z' };
    expect(CardSummary.parse(base).activity).toBeUndefined();
    expect(CardSummary.parse({ ...base, activity: null }).activity).toBeNull();
    expect(CardSummary.parse({ ...base, activity: { id: 'a', name: '동아리', type: 'CLUB' } }).activity).toEqual({ id: 'a', name: '동아리', type: 'CLUB' });
  });

  it('직접 작성 시작 요청: activityId 는 있을 때만 싣는다(생략한 요청은 예전과 같다)', () => {
    expect(ManualDraftInput.parse({ title: 't', period: null, repoId: null })).toEqual({ title: 't', period: null, repoId: null });
    expect(ManualDraftInput.parse({ title: 't', period: null, repoId: null, activityId: 'a' })).toMatchObject({ activityId: 'a' });
  });
});

describe('활동 입력 검사', () => {
  it('통과하면 안내가 없다', () => {
    expect(validateActivityForm(valid())).toEqual({});
    expect(validateActivityForm(valid({ ongoing: false, endMonth: '2025-12', role: '파트장' }))).toEqual({});
  });

  it('이름은 비면 안 되고 50자까지, 종류와 시작한 달은 꼭 필요하다', () => {
    expect(validateActivityForm(valid({ name: '   ' })).name).toBe('활동 이름을 적어 주세요.');
    expect(validateActivityForm(valid({ name: '가'.repeat(50) })).name).toBeUndefined();
    expect(validateActivityForm(valid({ name: '가'.repeat(51) })).name).toBe('활동 이름은 50자까지 쓸 수 있어요.');
    expect(validateActivityForm(valid({ type: '' })).type).toBe('종류를 골라 주세요.');
    expect(validateActivityForm(valid({ startMonth: '' })).startMonth).toBe('시작한 달을 골라 주세요.');
    expect(validateActivityForm(valid({ startMonth: '2025-13' })).startMonth).toBe('시작한 달은 2025-03 처럼 적어 주세요.');
  });

  it('진행 중이 아니면 끝난 달이 필요하고 시작한 달보다 앞설 수 없다(같은 달은 된다)', () => {
    expect(validateActivityForm(valid({ ongoing: false })).endMonth).toContain('끝난 달을 골라 주세요');
    expect(validateActivityForm(valid({ ongoing: false, endMonth: '2025-02' })).endMonth).toBe('끝난 달이 시작한 달보다 앞설 수 없어요.');
    expect(validateActivityForm(valid({ ongoing: false, endMonth: '2025-03' })).endMonth).toBeUndefined();
    expect(validateActivityForm(valid({ ongoing: false, endMonth: '2025/12' })).endMonth).toBe('끝난 달은 2025-12 처럼 적어 주세요.');
    // 진행 중이면 남아 있는 끝난 달 값은 보지 않는다
    expect(validateActivityForm(valid({ ongoing: true, endMonth: '1999-01' })).endMonth).toBeUndefined();
  });

  it('역할은 50자까지', () => {
    expect(validateActivityForm(valid({ role: '가'.repeat(51) })).role).toBe('역할은 50자까지 쓸 수 있어요.');
    expect(validateActivityForm(valid({ role: '   ' }))).toEqual({});
  });
});

describe('활동 요청 만들기', () => {
  it('만들 때: 진행 중이면 끝난 달을, 역할이 비면 역할을 보내지 않는다. 이름은 앞뒤 공백을 뺀다', () => {
    expect(toCreateBody(valid({ name: '  동아리  ' }))).toEqual({ name: '동아리', type: 'CLUB', startMonth: '2025-03' });
    expect(toCreateBody(valid({ ongoing: false, endMonth: '2025-12', role: ' 파트장 ' }))).toEqual({ name: '교내 개발 동아리', type: 'CLUB', startMonth: '2025-03', endMonth: '2025-12', role: '파트장' });
  });

  const original = Activity.parse({ id: 'a', name: '동아리', type: 'CLUB', startMonth: '2025-03', endMonth: '2025-12', role: '파트장', cardCount: 0, confirmedCount: 0, createdAt: '2026-10-01T00:00:00Z' });
  it('고칠 때: 바뀐 칸만 보내고, 아무것도 안 바뀌면 빈 요청이라 호출하지 않는다', () => {
    expect(toPatchBody(formFromActivity(original), original)).toEqual({});
    expect(toPatchBody({ ...formFromActivity(original), name: ' 새 이름 ', type: 'STUDY' }, original)).toEqual({ name: '새 이름', type: 'STUDY' });
    expect(toPatchBody({ ...formFromActivity(original), startMonth: '2025-04', endMonth: '2026-01' }, original)).toEqual({ startMonth: '2025-04', endMonth: '2026-01' });
  });

  it('고칠 때: 진행 중으로 바꾸거나 역할을 지우면 빈 글을 보낸다(생략은 그대로 두라는 뜻이다)', () => {
    expect(toPatchBody({ ...formFromActivity(original), ongoing: true }, original)).toEqual({ endMonth: '' });
    expect(toPatchBody({ ...formFromActivity(original), role: '  ' }, original)).toEqual({ role: '' });
    const ongoing = { ...original, endMonth: null };
    expect(toPatchBody({ ...formFromActivity(ongoing), ongoing: true }, ongoing)).toEqual({});
    expect(toPatchBody({ ...formFromActivity(ongoing), ongoing: false, endMonth: '2026-01' }, ongoing)).toEqual({ endMonth: '2026-01' });
  });
});

describe('활동 문구', () => {
  it('삭제 확인: 카드 N장(확정 M장)을 말하고, 확정 카드가 있으면 따로 알린다', () => {
    expect(activityDeleteLines({ cardCount: 3, confirmedCount: 2 })).toEqual(['카드 3장(확정 2장)도 함께 지워져요.', '확정한 카드는 공고 매칭과 자소서에 쓰였을 수 있어요.']);
    expect(activityDeleteLines({ cardCount: 2, confirmedCount: 0 })).toEqual(['카드 2장(확정 0장)도 함께 지워져요.']);
    expect(activityDeleteLines({ cardCount: 0, confirmedCount: 0 })).toEqual(['안에 카드가 없어서 활동만 지워져요.']);
  });

  it('기간은 끝난 달이 없으면 진행 중이고, 종류가 없으면 종류를 쓰지 않는다', () => {
    expect(activityPeriodLabel('2025-03', null)).toBe('2025년 3월부터 진행 중');
    expect(activityPeriodLabel('2025-03', '2025-12')).toBe('2025년 3월부터 2025년 12월까지');
    expect(activityMeta({ type: 'CLUB', startMonth: '2025-03', endMonth: null, role: '파트장' })).toBe('동아리, 2025년 3월부터 진행 중, 파트장');
    expect(activityMeta({ type: null, startMonth: '2025-03', endMonth: null, role: null })).toBe('2025년 3월부터 진행 중');
    expect(activityOwnerLine('EDUCATION')).toBe('활동, 교육 과정');
    expect(activityOwnerLine(null)).toBe('활동');
  });
});

describe('목 서버의 활동 (#477 계약)', () => {
  beforeEach(resetDb);
  const list = () => req('GET', '/activities').data as Array<Record<string, any>>;

  it('시드에는 활동 셋과 활동 카드가 있고, 개수는 지금 카드에서 센다. 카드가 0장인 활동도 온다', () => {
    const byId = Object.fromEntries(list().map((a) => [a.id, a]));
    expect(byId.act_club).toMatchObject({ cardCount: 2, confirmedCount: 1 });
    expect(byId.act_edu).toMatchObject({ cardCount: 1, confirmedCount: 1 });
    expect(byId.act_contest).toMatchObject({ cardCount: 0, confirmedCount: 0 });
    expect(list().map((a) => a.startMonth)).toEqual(['2026-05', '2025-09', '2025-03']); // 시작한 달이 최근인 순
    expect(list().every((a) => Activity.safeParse(a).success)).toBe(true);
  });

  it('카드 목록과 상세가 activity 를 싣고, 활동을 고치면 카드에도 바로 보인다. 레포 카드는 null', () => {
    const summaries = req('GET', '/cards').data as Array<Record<string, any>>;
    const inClub = summaries.filter((c) => c.activity?.id === 'act_club');
    expect(inClub).toHaveLength(2);
    expect(inClub.every((c) => c.repo === null && c.kind === 'QUALITATIVE')).toBe(true);
    expect(summaries.find((c) => c.id === 'card_01')!.activity).toBeNull();
    expect(CardSummary.parse(inClub[0]).activity).toEqual({ id: 'act_club', name: '교내 개발 동아리', type: 'CLUB' });
    expect(Card.parse(req('GET', '/cards/card_a1').data).activity?.id).toBe('act_club');
    expect(req('PATCH', '/activities/act_club', { name: '동아리 새 이름' }).status).toBe(200);
    expect((req('GET', '/cards/card_a1').data as any).activity.name).toBe('동아리 새 이름');
  });

  it('만들기: 같은 Idempotency-Key 는 처음 만든 활동을 돌려주고 다시 보낸 값은 쓰지 않는다', () => {
    const body = { name: '스터디', type: 'STUDY', startMonth: '2026-01' };
    const first = req('POST', '/activities', body, { 'idempotency-key': 'key-1' });
    expect(first).toMatchObject({ status: 200, data: { name: '스터디', type: 'STUDY', endMonth: null, role: null, cardCount: 0, confirmedCount: 0 } });
    const again = req('POST', '/activities', { ...body, name: '다른 이름' }, { 'idempotency-key': 'key-1' });
    expect((again.data as any).id).toBe((first.data as any).id);
    expect((again.data as any).name).toBe('스터디');
    expect(list()).toHaveLength(4);
    expect(req('POST', '/activities', body, { 'idempotency-key': 'key-2' }).status).toBe(200);
    expect(list()).toHaveLength(5);
  });

  it('만들기 입력 규칙: 이름·종류·달 형식·끝난 달 순서·역할 길이가 틀리면 400 INVALID_REQUEST', () => {
    const ok = { name: '활동', type: 'CLUB', startMonth: '2026-01' };
    for (const bad of [{ name: '  ' }, { name: '가'.repeat(51) }, { type: 'NOPE' }, { startMonth: '2026-1' }, { endMonth: '2025-12' }, { role: '가'.repeat(51) }]) {
      expect(req('POST', '/activities', { ...ok, ...bad })).toMatchObject({ status: 400, error: { code: 'INVALID_REQUEST' } });
    }
    expect(req('POST', '/activities', { ...ok, endMonth: '', role: '' }).status).toBe(200); // 빈 글은 진행 중·역할 없음
    expect(list()).toHaveLength(4);
  });

  it('고치기: 보낸 칸만 바뀌고, 끝난 달·역할은 빈 글이 비우기, 바꿀 값이 없으면 400, 남의 활동·없는 활동은 404', () => {
    expect((req('PATCH', '/activities/act_edu', { endMonth: '' }).data as any).endMonth).toBeNull();
    expect((req('PATCH', '/activities/act_edu', { role: '' }).data as any).role).toBeNull();
    expect(req('PATCH', '/activities/act_edu', { name: '새 이름' }).data).toMatchObject({ name: '새 이름', type: 'EDUCATION', startMonth: '2025-09' });
    expect(req('PATCH', '/activities/act_edu', { name: null, role: null })).toMatchObject({ status: 400, error: { code: 'INVALID_REQUEST' } });
    expect(req('PATCH', '/activities/act_edu', {}).status).toBe(400);
    expect(req('PATCH', '/activities/act_contest', { startMonth: '2026-07' }).status).toBe(400); // 끝난 달(2026-06)이 시작한 달보다 앞선다
    expect(req('PATCH', '/activities/nope', { name: 'x' }).status).toBe(404);
  });

  it('지우기: 안의 카드도 모두 지운다(확정 카드 포함). 다른 활동·레포 카드는 그대로, 두 번 지우면 404', () => {
    const before = (req('GET', '/cards').data as unknown[]).length;
    db.interview.card_a2 = [{ turnNo: 1 }];
    expect(req('DELETE', '/activities/act_club')).toMatchObject({ status: 200, data: { ok: true } });
    expect((req('GET', '/cards').data as unknown[]).length).toBe(before - 2);
    expect(req('GET', '/cards/card_a1').status).toBe(404);
    expect(req('GET', '/cards/card_a2').status).toBe(404);
    expect(db.interview.card_a2).toBeUndefined();
    expect(req('GET', '/cards/card_a3').status).toBe(200);
    expect(req('GET', '/cards/card_01').status).toBe(200);
    expect(list().map((a) => a.id)).not.toContain('act_club');
    expect(req('DELETE', '/activities/act_club').status).toBe(404);
  });

  it('활동 안에서 카드 시작: activityId 를 싣고 카드에 activity 가 붙는다. 레포와 함께면 400, 없는 활동은 404', () => {
    const made = req('POST', '/cards/manual/draft', { title: '활동 카드', period: null, repoId: null, activityId: 'act_contest' });
    expect(made.status).toBe(200);
    expect(Card.parse(made.data)).toMatchObject({ kind: 'QUALITATIVE', repo: null, activity: { id: 'act_contest', name: '해커톤 참가', type: 'CONTEST' } });
    expect(list().find((a) => a.id === 'act_contest')).toMatchObject({ cardCount: 1, confirmedCount: 0 });
    expect(req('POST', '/cards/manual/draft', { title: 'x', period: null, repoId: 'r_auth', activityId: 'act_contest' })).toMatchObject({ status: 400, error: { code: 'INVALID_REQUEST' } });
    expect(req('POST', '/cards/manual/draft', { title: 'x', period: null, repoId: null, activityId: 'nope' }).status).toBe(404);
    // 활동을 싣지 않은 요청은 예전처럼 활동 밖 카드다
    expect((req('POST', '/cards/manual/draft', { title: '그냥 카드', period: null, repoId: null }).data as any).activity).toBeNull();
  });
});

describe('데모 시나리오의 활동', () => {
  beforeEach(resetDb);
  it('빈 목록 시나리오는 활동도 비워 목록이 정말 비어 보이게 한다. 다른 시나리오는 활동을 그대로 둔다', () => {
    const result = req('GET', '/activities');
    expect(createScenario('empty').after('GET', '/activities', result).data).toEqual([]);
    expect(createScenario('normal').after('GET', '/activities', result).data).toBe(result.data);
    // 조회 실패 시나리오는 활동 목록도 실패시킨다(카드 목록과 같이)
    expect(createScenario('read-error').before('GET', '/activities')).toMatchObject({ status: 503 });
  });
});
