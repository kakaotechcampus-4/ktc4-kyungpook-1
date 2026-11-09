import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfileSetupModal } from '@/features/profile/ProfileSetupModal';
import { putProfile } from '@/mock/profile';
import { db, resetDb } from '@/mock/store';
import { track } from '@/lib/track';
import { connectMockServer, jsonError, minimalInput, mountApp, page, sampleInput } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
// Radix 메뉴는 jsdom 에서 열리는 데만 10초 넘게 걸린다 — 값·접근성 이름만 같은 네이티브 select 로 바꿔 창의 흐름을 시험한다
vi.mock('@/components/ui/Select', () => ({
  Select: ({ value, onChange, options, label, invalid, describedBy }: { value: string; onChange: (value: string) => void; options: { value: string; label: string; disabled?: boolean }[]; label: string; invalid?: boolean; describedBy?: string }) => (
    <select aria-label={label} aria-invalid={invalid || undefined} aria-describedby={describedBy} value={value} onChange={(e) => onChange(e.target.value)}>
      {options.map((option) => <option key={option.value} value={option.value} disabled={option.disabled}>{option.label}</option>)}
    </select>
  ),
}));

const mount = () => mountApp([page(<><h1>홈 화면</h1><ProfileSetupModal /></>, '/')], '/');
const dialog = () => screen.queryByRole('dialog');
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const pick = (label: string) => fireEvent.click(screen.getByRole('checkbox', { name: label }));
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const choose = (label: string, value: string) => fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value } });
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 30)); });
const puts = (server: ReturnType<typeof connectMockServer>) => server.profileCalls().filter((c) => c.method === 'PUT');
const pickRadio = (label: string) => fireEvent.click(screen.getByRole('radio', { name: label }));
/** 창의 학력은 타일 두 줄 — 학력 구분(대학교면 4년제가 먼저 골라진다)과 현재 상태 */
function fillEducation() {
  pickRadio('대학교'); pickRadio('재학');
}

beforeEach(() => { resetDb(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.clearAllMocks(); });

describe('first-entry dialog — 메일 알림', () => {
  it('F1: the last step shows the 메일 알림 section under 자격증', async () => {
    connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음');
    click('경력 없음');
    fillEducation(); click('다음');
    click('어학 없음');
    const region = screen.getByRole('region', { name: '메일 알림' });
    expect(region).toBeInTheDocument();
    expect(within(region).getByLabelText('메일 주소')).toHaveAttribute('type', 'email');
    expect(within(region).getByRole('checkbox', { name: '내 경험에 맞는 공고를 메일로 받을게요' })).not.toBeChecked();
  });
});

describe('first-entry dialog (shown while no interest is saved)', () => {
  it('opens over the page for a never-saved profile, starting at 관심 분야 (title is the step name, no extra step text)', async () => {
    connectMockServer();
    mount();
    const modal = await screen.findByRole('dialog');
    expect(modal).toHaveAccessibleName('관심 분야');
    expect(within(modal).queryByText(/1\/5 단계|다 입력해야/)).not.toBeInTheDocument();
    expect(within(modal).getByRole('listitem', { current: 'step' })).toHaveTextContent('관심 분야');
    expect(within(modal).getByRole('checkbox', { name: '백엔드' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '홈 화면' })).toBeInTheDocument(); // 페이지는 뒤에 그대로 있다
  });

  it('also opens for a profile saved earlier without interests (skipped before), with its rows filled in', async () => {
    connectMockServer();
    db.profile = { ...sampleInput(), email: null, mailOptIn: false, interests: [], savedAt: '2026-10-30T03:00:00.000Z' };
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음');
    expect(screen.getByLabelText('경력 1 회사')).toHaveValue('샘플컴퍼니');
  });

  it('stays closed once an interest is saved, and sends no PUT by itself', async () => {
    const server = connectMockServer();
    putProfile(minimalInput());
    mount();
    await waitFor(() => expect(server.profileCalls().length).toBeGreaterThan(0));
    await settle();
    expect(dialog()).not.toBeInTheDocument();
    expect(puts(server)).toHaveLength(0);
  });

  it('cannot be closed without saving: no ✕, and Escape or clicking outside do nothing', async () => {
    connectMockServer();
    mount();
    const modal = await screen.findByRole('dialog');
    expect(within(modal).queryByRole('button', { name: '닫기' })).not.toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.dim')!);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('step 1 needs one to three interests: 다음 without a pick says so, and a fourth tile is greyed out', async () => {
    connectMockServer();
    mount();
    await screen.findByRole('dialog');
    click('다음');
    expect(screen.getByText('관심 분야를 1개 이상 골라 주세요')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toHaveAccessibleName(/관심 분야/);
    pick('백엔드'); pick('프론트엔드'); pick('데이터');
    expect(screen.queryByText('관심 분야를 1개 이상 골라 주세요')).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '보안' })).toBeDisabled();
    click('다음');
    expect(screen.getByRole('dialog')).toHaveAccessibleName(/경력/);
  });

  it('경력·어학·자격증 can be passed with "없음"; a started row must be completed before 다음', async () => {
    connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음');
    expect(screen.getByRole('button', { name: '경력 없음' })).toBeInTheDocument();
    click('경력 추가');
    expect(screen.queryByRole('button', { name: '경력 없음' })).not.toBeInTheDocument();
    click('다음');
    expect(screen.getByText('회사 이름을 입력해 주세요')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toHaveAccessibleName(/경력/);
    click('경력 1 삭제');
    click('경력 없음');
    expect(screen.getByRole('dialog')).toHaveAccessibleName(/학력/);
  });

  it('학력 has no "없음": 다음 with nothing picked says one is needed, a level alone asks for the status, and both move on', async () => {
    connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음'); click('경력 없음');
    expect(screen.queryByRole('button', { name: '학력 없음' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '학력 추가' })).not.toBeInTheDocument(); // 창에서는 줄 입력이 아니라 타일
    click('다음');
    expect(screen.getByText('학력을 1개 이상 입력해 주세요')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toHaveAccessibleName(/학력/);
    pickRadio('대학교');
    expect(screen.queryByText('학력을 1개 이상 입력해 주세요')).not.toBeInTheDocument();
    click('다음');
    expect(screen.getByText('상태를 선택해 주세요')).toBeInTheDocument();
    pickRadio('졸업 예정');
    click('다음');
    expect(screen.getByRole('dialog')).toHaveAccessibleName(/어학/);
    click('이전');
    expect(screen.getByRole('radio', { name: '대학교' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '4년제' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '졸업 예정' })).toBeChecked();
  });

  it('어학: a score test opens a score box, a grade test a grade list; empty ones block 다음, and unticking removes the row', async () => {
    const server = connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음'); click('경력 없음'); fillEducation(); click('다음');
    expect(screen.getByRole('button', { name: '어학 없음' })).toBeInTheDocument();
    pick('토익');
    expect(screen.getByRole('textbox', { name: '토익 점수' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '어학 없음' })).not.toBeInTheDocument();
    pick('오픽');
    expect(screen.getByRole('combobox', { name: '오픽 등급' })).toBeInTheDocument();
    click('다음');
    expect(screen.getAllByText('점수나 등급을 입력해 주세요')).toHaveLength(2);
    expect(screen.getByRole('dialog')).toHaveAccessibleName(/어학/);
    type('토익 점수', '900');
    choose('오픽 등급', 'IH');
    pick('토익'); // 해제하면 그 줄이 사라진다
    expect(screen.queryByRole('textbox', { name: '토익 점수' })).not.toBeInTheDocument();
    click('다음');
    click('자격증 없음');
    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect((puts(server)[0].body as { languages: unknown[] }).languages).toEqual([{ test: 'OPIC', testName: null, score: 'IH', acquiredMonth: null }]);
  });

  it('자격증: common ones are tiles, others are typed and added as chips, and unticking removes', async () => {
    const server = connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음'); click('경력 없음'); fillEducation(); click('다음'); click('어학 없음');
    expect(screen.getByRole('button', { name: '자격증 없음' })).toBeInTheDocument();
    pick('정보처리기사'); pick('SQLD');
    type('자격증 직접 입력', ' AWS Developer Associate ');
    click('추가');
    expect(screen.getByRole('list', { name: '직접 적은 자격증' })).toHaveTextContent('AWS Developer Associate');
    pick('SQLD'); // 해제
    click('저장하고 시작하기');
    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect((puts(server)[0].body as { certifications: { name: string }[] }).certifications.map((c) => c.name)).toEqual(['정보처리기사', 'AWS Developer Associate']);
  });

  it('이전 goes back without losing what was entered', async () => {
    connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음');
    click('경력 추가'); type('경력 1 회사', '샘플컴퍼니');
    click('이전');
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeChecked();
    click('다음');
    expect(screen.getByLabelText('경력 1 회사')).toHaveValue('샘플컴퍼니');
  });

  it('학력 구분은 고등학교, 대학교, 대학원 셋이고, 대학교와 대학원만 한 줄 더 물어 저장 값은 그대로 보낸다', async () => {
    const server = connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음'); click('경력 없음');
    expect(screen.getAllByRole('radio').map((r) => r.getAttribute('name') === 'p-edu-level' ? (r.closest('label')?.textContent ?? '') : null).filter(Boolean)).toEqual(['고등학교', '대학교', '대학원']);
    expect(screen.getByText('현재 상태')).toBeInTheDocument();
    pickRadio('고등학교');
    expect(screen.queryByRole('radiogroup', { name: /구분$/ })).not.toBeInTheDocument();
    pickRadio('대학원');
    expect(screen.getByRole('radio', { name: '석사' })).toBeChecked(); // 대학원을 고르면 석사가 먼저
    pickRadio('박사'); pickRadio('졸업');
    click('다음'); click('어학 없음'); click('자격증 없음');
    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect((puts(server)[0].body as { educations: unknown[] }).educations[0]).toMatchObject({ level: 'DOCTOR', status: 'GRADUATED' });
  });

  it('이전은 테두리 있는 보조 버튼이고, 경력이 비었을 때 넘기는 안내 줄 없이 "경력 없음" 버튼만 둔다', async () => {
    connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음');
    expect(screen.getByRole('button', { name: '이전' })).toHaveClass('btn--outline');
    expect(screen.getByRole('button', { name: '경력 없음' })).toHaveClass('btn--primary');
    expect(screen.queryByText(/해당 사항이 없으면|회사에서 한 일과 기간을/)).not.toBeInTheDocument();
  });

  it('the last step saves everything in one PUT, closes the dialog and reports only a count', async () => {
    const server = connectMockServer();
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); pick('인프라, DevOps'); click('다음');
    click('경력 없음');
    fillEducation(); click('다음');
    click('어학 없음');
    expect(screen.getByRole('dialog')).toHaveAccessibleName(/자격증/);
    click('자격증 없음');
    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(puts(server)).toHaveLength(1);
    expect(puts(server)[0].body).toEqual({
      educations: [{ level: 'BACHELOR', school: null, major: null, status: 'ENROLLED', startMonth: null, endMonth: null, gpa: null, gpaMax: null }],
      careers: [], languages: [], certifications: [], interests: ['BACKEND', 'DEVOPS'], email: '', mailOptIn: false,
    });
    expect(db.profile.interests).toEqual(['BACKEND', 'DEVOPS']);
    expect(vi.mocked(track)).toHaveBeenCalledWith('profile_setup_completed', { interestCount: 2 });
  });

  it('keeps the dialog and the input when the save fails, and saves on retry', async () => {
    let failures = 1;
    const server = connectMockServer((call) => (call.method === 'PUT' && failures-- > 0 ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    mount();
    await screen.findByRole('dialog');
    pick('백엔드'); click('다음'); click('경력 없음');
    fillEducation(); click('다음'); click('어학 없음'); click('자격증 없음');
    expect(await screen.findByText(/서버에 연결하지 못했어요|서버에서 처리하지 못했어요/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(vi.mocked(track)).not.toHaveBeenCalledWith('profile_setup_completed', expect.anything());
    click('자격증 없음');
    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(puts(server)).toHaveLength(2);
    expect(puts(server)[1].body).toEqual(puts(server)[0].body);
  });

  it('is absent in real-server mode until the profile flag is on, and sends no request', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    const server = connectMockServer();
    mount();
    await settle();
    expect(dialog()).not.toBeInTheDocument();
    expect(server.profileCalls()).toHaveLength(0);
  });
});
