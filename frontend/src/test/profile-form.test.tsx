import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfilePage } from '@/features/profile/ProfilePage';
import { resetDb } from '@/mock/store';
import { putProfile } from '@/mock/profile';
import { connectMockServer, jsonError, mountApp, page, sampleInput } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
// Radix 메뉴는 jsdom 에서 열리는 데만 10초 넘게 걸린다. 값·접근성 이름만 같은 네이티브 select 로 바꿔 폼 로직을 시험한다.
// Radix 래퍼 자체의 조작은 e2e(select-interaction · profile)가 실제 브라우저에서 확인한다.
vi.mock('@/components/ui/Select', () => ({
  Select: ({ value, onChange, options, label }: { value: string; onChange: (value: string) => void; options: { value: string; label: string; disabled?: boolean }[]; label: string }) => (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      {options.map((option) => <option key={option.value} value={option.value} disabled={option.disabled}>{option.label}</option>)}
    </select>
  ),
}));

const routes = () => [page(<ProfilePage />, '/settings/profile'), page(<p>마이페이지 화면</p>, '/settings'), page(<p>다른 화면</p>, '/elsewhere')];
async function open() {
  const app = mountApp(routes(), '/settings/profile');
  await screen.findByRole('button', { name: '학력 추가' });
  return app;
}
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const status = () => screen.getAllByRole('status').map((el) => el.textContent).join('|');
const save = async () => { click(/^(다시 )?저장$/); await act(async () => { await Promise.resolve(); }); };
const LABEL_TO_STATUS: Record<string, string> = { 재학: 'ENROLLED', 졸업: 'GRADUATED', 휴학: 'LEAVE', 중퇴: 'DROPOUT', 수료: 'COMPLETED' };
const choose = (label: string, option: string) => fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value: LABEL_TO_STATUS[option] } });
/** 학력 한 줄을 모두 채운다 */
async function fillEducation(n = 1, patch: Partial<Record<'school' | 'major' | 'start' | 'end', string>> = {}) {
  type(`학력 ${n} 학교`, patch.school ?? '샘플대학교');
  type(`학력 ${n} 전공`, patch.major ?? '컴퓨터공학');
  choose(`학력 ${n} 상태`, '졸업');
  type(`학력 ${n} 시작 월`, patch.start ?? '2018-03');
  type(`학력 ${n} 종료 월`, patch.end ?? '2022-02');
}

beforeEach(() => { resetDb(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.clearAllMocks(); });

describe('profile form — rows', () => {
  it('starts empty, adds a row with focus on its first field, and removes it with focus back on the add button', async () => {
    connectMockServer();
    await open();
    expect(screen.getByText('아직 입력한 학력이 없어요.')).toBeInTheDocument();
    click('학력 추가');
    expect(await screen.findByLabelText('학력 1 학교')).toHaveFocus();
    expect(screen.queryByText('아직 입력한 학력이 없어요.')).not.toBeInTheDocument();
    click('학력 추가');
    expect(screen.getByLabelText('학력 2 학교')).toBeInTheDocument();
    click('학력 1 삭제');
    expect(screen.queryByLabelText('학력 2 학교')).not.toBeInTheDocument();
    expect(screen.getByLabelText('학력 1 학교')).toHaveValue('');
    await waitFor(() => expect(screen.getByRole('button', { name: '학력 추가' })).toHaveFocus());
  });

  it('removing the first of two rows keeps the second row\'s text', async () => {
    connectMockServer();
    await open();
    click('경력 추가'); click('경력 추가');
    type('경력 1 회사', '첫째 회사'); type('경력 2 회사', '둘째 회사');
    click('경력 1 삭제');
    expect(screen.getByLabelText('경력 1 회사')).toHaveValue('둘째 회사');
  });

  it('stops at ten rows per list and says so', async () => {
    connectMockServer();
    await open();
    for (let i = 0; i < 10; i++) click('어학 추가');
    expect(screen.getByLabelText('어학 10 시험')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '어학 추가' })).toBeDisabled();
    expect(screen.getByText('최대 10개까지 입력할 수 있어요')).toBeInTheDocument();
    click('어학 10 삭제');
    expect(screen.getByRole('button', { name: '어학 추가' })).toBeEnabled();
  });

  it('gives every control an accessible name that includes its row', async () => {
    connectMockServer();
    await open();
    for (const name of ['학력 추가', '경력 추가', '어학 추가', '자격증 추가']) click(name);
    for (const label of ['학력 1 학교', '학력 1 전공', '학력 1 시작 월', '학력 1 종료 월', '경력 1 회사', '경력 1 직무', '경력 1 시작 월', '경력 1 종료 월', '경력 1 재직 중',
      '어학 1 시험', '어학 1 점수 또는 등급', '어학 1 취득 월', '자격증 1 이름', '자격증 1 발급 기관', '자격증 1 취득 월']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument();
    }
    expect(screen.getByRole('combobox', { name: '학력 1 상태' })).toBeInTheDocument();
    for (const name of ['학력 1 삭제', '경력 1 삭제', '어학 1 삭제', '자격증 1 삭제']) expect(screen.getByRole('button', { name })).toBeInTheDocument();
  });

  it('keeps personal text out of the DOM ids it generates', async () => {
    connectMockServer();
    await open();
    click('학력 추가');
    type('학력 1 학교', '비밀학교-ZXQ');
    expect([...document.querySelectorAll('[id]')].some((el) => el.id.includes('ZXQ'))).toBe(false);
  });
});

describe('profile form — validation', () => {
  it('does not shout while typing; on save it lists what is wrong, focuses the first field and sends nothing', async () => {
    const server = connectMockServer();
    await open();
    click('학력 추가');
    expect(screen.queryByText('학교 이름을 입력해 주세요')).not.toBeInTheDocument();
    server.fetcher.mockClear();
    await save();
    for (const message of ['학교 이름을 입력해 주세요', '전공을 입력해 주세요', '상태를 선택해 주세요', '시작 월을 입력해 주세요']) expect(screen.getByText(message)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('입력을 확인해 주세요');
    expect(screen.getByRole('alert')).toHaveTextContent('4곳');
    await waitFor(() => expect(screen.getByLabelText('학력 1 학교')).toHaveFocus());
    expect(screen.getByLabelText('학력 1 학교')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('학력 1 학교')).toHaveAccessibleDescription('학교 이름을 입력해 주세요');
    expect(server.profileCalls().filter((c) => c.method === 'PUT')).toEqual([]);
  });

  it('removes a message the moment its field is fixed', async () => {
    connectMockServer();
    await open();
    click('학력 추가');
    await save();
    type('학력 1 학교', '샘플대학교');
    expect(screen.queryByText('학교 이름을 입력해 주세요')).not.toBeInTheDocument();
    expect(screen.getByText('전공을 입력해 주세요')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('3곳');
  });

  it('explains an end month before the start month, and needs one for a finished status', async () => {
    connectMockServer();
    await open();
    click('학력 추가');
    await fillEducation(1, { start: '2022-03', end: '2022-02' });
    await save();
    expect(screen.getByText('종료 월은 시작 월과 같거나 뒤여야 해요')).toBeInTheDocument();
    type('학력 1 종료 월', '');
    await save();
    expect(screen.getByText('졸업·수료·중퇴한 월을 입력해 주세요')).toBeInTheDocument();
  });

  it('flags a career with neither an end month nor "still working"', async () => {
    connectMockServer();
    await open();
    click('경력 추가');
    type('경력 1 회사', '샘플컴퍼니'); type('경력 1 직무', '개발'); type('경력 1 시작 월', '2023-01');
    await save();
    expect(screen.getByText('종료 월을 입력하거나 재직 중을 선택해 주세요')).toBeInTheDocument();
  });

  it('reports a section-wide problem under the section', async () => {
    connectMockServer();
    await open();
    click('자격증 추가');
    await save();
    expect(screen.getByText('자격증 이름을 입력해 주세요')).toBeInTheDocument();
    expect(screen.queryByText('발급 기관을 입력해 주세요')).not.toBeInTheDocument(); // 발급 기관은 선택
  });
});

describe('profile form — saving', () => {
  it('sends the normalized document and then reads as saved and clean', async () => {
    const server = connectMockServer();
    await open();
    expect(status()).toContain('고친 곳이 없어요');
    click('학력 추가'); await fillEducation(1, { school: '  샘플대학교  ' });
    expect(status()).toContain('저장하지 않은 변경이 있어요');
    click('경력 추가');
    type('경력 1 회사', '샘플컴퍼니'); type('경력 1 직무', '백엔드 개발'); type('경력 1 시작 월', '2022-03');
    fireEvent.click(screen.getByLabelText('경력 1 재직 중'));
    click('어학 추가');
    type('어학 1 시험', 'TOEIC'); type('어학 1 점수 또는 등급', '900'); type('어학 1 취득 월', '2021-08');
    click('자격증 추가');
    type('자격증 1 이름', '정보처리기사'); type('자격증 1 발급 기관', '   '); type('자격증 1 취득 월', '2021-11');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    const put = server.profileCalls().find((c) => c.method === 'PUT')!;
    expect(put.body).toEqual(sampleInput());
    expect(screen.getByLabelText('학력 1 학교')).toHaveValue('샘플대학교'); // 서버가 돌려준 정리된 값
    expect(screen.getByRole('button', { name: '저장' })).toHaveAttribute('aria-disabled', 'true');
    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toHaveFocus());
    // 다시 눌러도 요청을 더 보내지 않는다
    const puts = () => server.profileCalls().filter((c) => c.method === 'PUT').length;
    click('저장');
    expect(puts()).toBe(1);
  });

  it('opens a saved profile with its rows filled in', async () => {
    connectMockServer();
    putProfile(sampleInput());
    await open();
    expect(screen.getByLabelText('학력 1 학교')).toHaveValue('샘플대학교');
    expect(screen.getByLabelText('학력 1 종료 월')).toHaveValue('2022-02');
    expect(screen.getByLabelText('경력 1 재직 중')).toBeChecked();
    expect(screen.getByLabelText('경력 1 종료 월')).toBeDisabled();
    expect(screen.getByLabelText('자격증 1 발급 기관')).toHaveValue('');
    expect(screen.getByRole('combobox', { name: '학력 1 상태' })).toHaveValue('GRADUATED');
    expect(status()).toContain('고친 곳이 없어요');
  });

  it('still working disables the end month and unchecking it asks for one again', async () => {
    connectMockServer();
    putProfile(sampleInput());
    await open();
    fireEvent.click(screen.getByLabelText('경력 1 재직 중'));
    expect(screen.getByLabelText('경력 1 종료 월')).toBeEnabled();
    await save();
    expect(screen.getByText('종료 월을 입력하거나 재직 중을 선택해 주세요')).toBeInTheDocument();
    type('경력 1 종료 월', '2024-05');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
  });

  it('clearing every row and saving sends four empty lists, with a warning first', async () => {
    const server = connectMockServer();
    putProfile(sampleInput());
    await open();
    for (const name of ['학력 1 삭제', '경력 1 삭제', '어학 1 삭제', '자격증 1 삭제']) click(name);
    expect(status()).toContain('모두 지운 상태예요. 저장하면 입력한 정보가 삭제돼요');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(server.profileCalls().find((c) => c.method === 'PUT')!.body).toEqual({ education: [], career: [], languages: [], certificates: [] });
  });
});

describe('profile form — failure keeps the input', () => {
  it('keeps everything typed after a server error and saves on retry', async () => {
    let failures = 1;
    const server = connectMockServer((call) => (call.method === 'PUT' && failures-- > 0 ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    await open();
    click('자격증 추가');
    type('자격증 1 이름', '정보처리기사'); type('자격증 1 취득 월', '2021-11');
    await save();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버에 연결하지 못했어요');
    expect(alert).toHaveTextContent('입력한 내용은 이 화면에 남아 있어요');
    expect(screen.getByLabelText('자격증 1 이름')).toHaveValue('정보처리기사');
    expect(screen.getByLabelText('자격증 1 취득 월')).toHaveValue('2021-11');
    expect(status()).toContain('저장하지 못했어요. 입력한 내용은 그대로예요');
    expect(screen.getByRole('button', { name: '다시 저장' })).toBeEnabled();
    await waitFor(() => expect(screen.getByRole('button', { name: '다시 저장' })).toHaveFocus());

    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(server.profileCalls().filter((c) => c.method === 'PUT')).toHaveLength(2);
    expect(server.profileCalls().filter((c) => c.method === 'PUT')[1].body).toEqual(server.profileCalls().filter((c) => c.method === 'PUT')[0].body);
  });

  it('keeps a lost response from marking the form saved', async () => {
    connectMockServer((call) => (call.method === 'PUT' ? Response.error() : undefined));
    await open();
    click('어학 추가');
    type('어학 1 시험', 'TOEIC'); type('어학 1 점수 또는 등급', '900'); type('어학 1 취득 월', '2021-08');
    await save();
    await screen.findByRole('alert');
    expect(status()).not.toContain('저장했어요');
    expect(screen.getByLabelText('어학 1 점수 또는 등급')).toHaveValue('900');
  });

  it('shows the server\'s per-field errors where they belong and clears one when that field is edited', async () => {
    connectMockServer((call) => (call.method === 'PUT'
      ? jsonError(400, 'INVALID_REQUEST', { fields: [{ path: 'career[0].role', code: 'TOO_LONG' }, { path: 'career[0].startMonth', code: 'OUT_OF_RANGE' }] })
      : undefined));
    await open();
    click('경력 추가');
    type('경력 1 회사', '샘플컴퍼니'); type('경력 1 직무', '개발'); type('경력 1 시작 월', '2023-01'); fireEvent.click(screen.getByLabelText('경력 1 재직 중'));
    await save();
    await screen.findByText('50자 이하로 입력해 주세요');
    expect(screen.getByText('1950년부터 이번 달 사이의 월을 입력해 주세요')).toBeInTheDocument();
    expect(screen.getByLabelText('경력 1 직무')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('2곳');
    await waitFor(() => expect(screen.getByLabelText('경력 1 직무')).toHaveFocus());
    type('경력 1 직무', '백엔드 개발');
    expect(screen.queryByText('50자 이하로 입력해 주세요')).not.toBeInTheDocument();
    expect(screen.getByText('1950년부터 이번 달 사이의 월을 입력해 주세요')).toBeInTheDocument();
  });

  it('falls back to a general message for a 400 without field details', async () => {
    connectMockServer((call) => (call.method === 'PUT' ? jsonError(400, 'INVALID_REQUEST') : undefined));
    await open();
    click('자격증 추가');
    type('자격증 1 이름', '자격증'); type('자격증 1 취득 월', '2021-11');
    await save();
    expect(await screen.findByRole('alert')).toHaveTextContent('요청을 처리하지 못했어요');
    expect(screen.getByLabelText('자격증 1 이름')).toHaveValue('자격증');
  });
});

describe('profile form — leaving with unsaved changes', () => {
  it('asks before leaving a dirty form; staying keeps the input, leaving drops it', async () => {
    connectMockServer();
    const { router } = await open();
    click('자격증 추가');
    type('자격증 1 이름', '정보처리기사');
    await act(async () => { void router.navigate('/elsewhere'); });
    const dialog = await screen.findByRole('dialog', { name: '아직 저장되지 않은 내용이 있어요' });
    fireEvent.click(within(dialog).getByRole('button', { name: '계속 작성' }));
    expect(router.state.location.pathname).toBe('/settings/profile');
    expect(screen.getByLabelText('자격증 1 이름')).toHaveValue('정보처리기사');
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하지 않고 나가기' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/elsewhere'));
  });

  it('"save and leave" saves first and then leaves', async () => {
    const server = connectMockServer();
    const { router } = await open();
    click('자격증 추가');
    type('자격증 1 이름', '정보처리기사'); type('자격증 1 취득 월', '2021-11');
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하고 이동' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/elsewhere'));
    expect(server.profileCalls().filter((c) => c.method === 'PUT')).toHaveLength(1);
  });

  it('"save and leave" with invalid input stays and explains why (not "check your connection")', async () => {
    const server = connectMockServer();
    const { router } = await open();
    click('자격증 추가');
    type('자격증 1 이름', '정보처리기사'); // 취득 월 없음
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하고 이동' }));
    expect(await screen.findByText(/입력을 확인해야 저장할 수 있어요/)).toBeInTheDocument();
    expect(screen.queryByText(/연결을 확인한 뒤/)).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/settings/profile');
    expect(server.profileCalls().filter((c) => c.method === 'PUT')).toEqual([]);
  });

  it('does not ask when nothing changed, or after a successful save', async () => {
    connectMockServer();
    const { router } = await open();
    await act(async () => { await router.navigate('/elsewhere'); });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/elsewhere');
  });
  it('does not ask after a successful save', async () => {
    connectMockServer();
    const { router } = await open();
    click('자격증 추가');
    type('자격증 1 이름', '정보처리기사'); type('자격증 1 취득 월', '2021-11');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    await act(async () => { await router.navigate('/elsewhere'); });
    expect(router.state.location.pathname).toBe('/elsewhere');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
