import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfilePage } from '@/features/profile/ProfilePage';
import { resetDb } from '@/mock/store';
import { putProfile } from '@/mock/profile';
import { connectMockServer, jsonError, mountApp, page, sampleInput } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const routes = () => [page(<ProfilePage />, '/settings/profile'), page(<p>마이페이지 화면</p>, '/settings'), page(<p>다른 화면</p>, '/elsewhere')];
async function open() {
  const app = mountApp(routes(), '/settings/profile');
  await screen.findByRole('checkbox', { name: '백엔드' });
  return app;
}
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const pick = (label: string) => fireEvent.click(screen.getByRole('checkbox', { name: label }));
const status = () => screen.getAllByRole('status').map((el) => el.textContent).join('|');
const save = async () => { click(/^(다시 )?저장$/); await act(async () => { await Promise.resolve(); }); };
const puts = (server: ReturnType<typeof connectMockServer>) => server.profileCalls().filter((c) => c.method === 'PUT');

beforeEach(() => { resetDb(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.clearAllMocks(); });

describe('profile form — what the screen shows', () => {
  it('마이페이지에는 관심 분야 구역만 있다 — 학력, 경력, 어학, 자격증은 묻지 않는다', async () => {
    connectMockServer();
    await open();
    expect(screen.getAllByRole('region').map((region) => region.getAttribute('aria-label'))).toEqual(['관심 분야', '메일 알림']);
    for (const noun of ['학력', '경력', '어학', '자격증']) expect(screen.queryByRole('button', { name: `${noun} 추가` })).not.toBeInTheDocument();
    expect(screen.getByRole('main').textContent).not.toMatch(/학력|경력|어학|자격증/);
  });

  it('says what the data is for and that no other personal data is taken, without claiming a single use', async () => {
    connectMockServer();
    await open();
    const note = screen.getByText('이렇게 쓰여요').parentElement!;
    expect(note).toHaveTextContent('관심 분야는 공고 매칭이 공고를 고를 때 써요. 다른 개인 정보는 받지 않아요.');
    expect(screen.getByRole('main').textContent).not.toMatch(/데만|만 쓰|만 사용|오직|뿐/);
  });
});

describe('profile form — validation', () => {
  it('needs one to three interests: says so on save, and greys out the rest once three are chosen', async () => {
    const server = connectMockServer();
    await open();
    expect(status()).toContain('고친 곳이 없어요');
    type('메일 주소', 'me@example.com'); // 고친 곳이 있어야 저장 단추가 눌린다
    await save();
    expect(screen.getByText('관심 분야를 1개 이상 골라 주세요')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('1곳');
    expect(puts(server)).toEqual([]);
    pick('백엔드'); pick('프론트엔드'); pick('데이터');
    expect(screen.queryByText('관심 분야를 1개 이상 골라 주세요')).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '보안' })).toBeDisabled();
    pick('프론트엔드');
    expect(screen.getByRole('checkbox', { name: '보안' })).toBeEnabled();
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect((puts(server)[0].body as { interests: string[] }).interests).toEqual(['BACKEND', 'DATA']);
  });
});

describe('profile form — saving', () => {
  it('sends the interests with four empty lists, then reads as saved and clean', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드'); pick('인프라, DevOps');
    expect(status()).toContain('저장하지 않은 변경이 있어요');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(puts(server)[0].body).toEqual({ educations: [], careers: [], languages: [], certifications: [], interests: ['BACKEND', 'DEVOPS'], email: '', mailOptIn: false });
    expect(screen.getByRole('button', { name: '저장' })).toHaveAttribute('aria-disabled', 'true');
    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toHaveFocus());
    click('저장'); // 다시 눌러도 요청을 더 보내지 않는다
    expect(puts(server)).toHaveLength(1);
  });

  it('opens a saved profile with its interests ticked, and saving sends the four lists empty even if the server still holds rows', async () => {
    const server = connectMockServer();
    putProfile(sampleInput());
    await open();
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: '인프라, DevOps' })).toBeChecked();
    pick('데이터');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(puts(server)[0].body).toEqual({ educations: [], careers: [], languages: [], certifications: [], interests: ['BACKEND', 'DEVOPS', 'DATA'], email: '', mailOptIn: false });
  });

  it('opens an empty, never-saved profile with nothing ticked', async () => {
    connectMockServer();
    await open();
    expect(screen.getAllByRole('checkbox').filter((box) => (box as HTMLInputElement).checked)).toEqual([]);
    expect(status()).toContain('고친 곳이 없어요');
  });
});

describe('profile form — failure keeps the input', () => {
  it('keeps what was ticked after a server error and saves on retry', async () => {
    let failures = 1;
    const server = connectMockServer((call) => (call.method === 'PUT' && failures-- > 0 ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    await open();
    pick('백엔드');
    await save();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버에서 처리하지 못했어요');
    expect(alert).toHaveTextContent('입력한 내용은 이 화면에 남아 있어요');
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeChecked();
    expect(status()).toContain('저장하지 못했어요. 입력한 내용은 그대로예요');
    expect(screen.getByRole('button', { name: '다시 저장' })).toBeEnabled();
    await waitFor(() => expect(screen.getByRole('button', { name: '다시 저장' })).toHaveFocus());

    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(puts(server)).toHaveLength(2);
    expect(puts(server)[1].body).toEqual(puts(server)[0].body);
  });

  it('keeps a lost response from marking the form saved', async () => {
    connectMockServer((call) => (call.method === 'PUT' ? Response.error() : undefined));
    await open();
    pick('백엔드');
    await save();
    await screen.findByRole('alert');
    expect(status()).not.toContain('저장했어요');
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeChecked();
  });

  it('puts a server error about the interests on the interest tiles, and clears it when they are edited', async () => {
    connectMockServer((call) => (call.method === 'PUT' ? jsonError(400, 'INVALID_REQUEST', { fields: [{ path: 'interests', code: 'TOO_MANY' }] }) : undefined));
    await open();
    pick('백엔드');
    await save();
    await screen.findByText('관심 분야는 3개까지 고를 수 있어요');
    expect(screen.getByRole('alert')).toHaveTextContent('1곳');
    pick('데이터');
    expect(screen.queryByText('관심 분야는 3개까지 고를 수 있어요')).not.toBeInTheDocument();
  });

  it('a server error about a box that is not on screen is not counted or shown — only the general message appears', async () => {
    connectMockServer((call) => (call.method === 'PUT'
      ? jsonError(400, 'INVALID_REQUEST', { fields: [{ path: 'educations[0].degree', code: 'REQUIRED' }, { path: 'educations[7].school', code: 'REQUIRED' }] })
      : undefined));
    await open();
    pick('백엔드');
    await save();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버가 입력을 받지 못했어요');
    expect(screen.queryByText(/\d+곳을 고치면/)).not.toBeInTheDocument();
  });

  it('falls back to a general message for a 400 that is only a sentence, and never shows that sentence', async () => {
    const sentence = '학력 1번째 학교 이름이 50자를 넘어요';
    connectMockServer((call) => (call.method === 'PUT'
      ? new Response(JSON.stringify({ data: null, error: { code: 'INVALID_REQUEST', message: sentence } }), { status: 400 })
      : undefined));
    await open();
    pick('백엔드');
    await save();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버가 입력을 받지 못했어요');
    expect(alert).toHaveTextContent('입력한 내용은 이 화면에 그대로 남아 있어요');
    expect(document.body.textContent).not.toContain(sentence);
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeChecked();
    expect(screen.queryByRole('button', { name: '저장' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 저장' })).toBeEnabled();
  });
});

describe('profile form — leaving with unsaved changes', () => {
  it('asks before leaving a dirty form; staying keeps the input, leaving drops it', async () => {
    connectMockServer();
    const { router } = await open();
    pick('백엔드');
    await act(async () => { void router.navigate('/elsewhere'); });
    const dialog = await screen.findByRole('dialog', { name: '아직 저장되지 않은 내용이 있어요' });
    fireEvent.click(within(dialog).getByRole('button', { name: '계속 작성' }));
    expect(router.state.location.pathname).toBe('/settings/profile');
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeChecked();
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하지 않고 나가기' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/elsewhere'));
  });

  it('"save and leave" saves first and then leaves', async () => {
    const server = connectMockServer();
    const { router } = await open();
    pick('백엔드');
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하고 이동' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/elsewhere'));
    expect(puts(server)).toHaveLength(1);
  });

  it('"save and leave" with invalid input stays and explains why (not "check your connection")', async () => {
    const server = connectMockServer();
    const { router } = await open();
    type('메일 주소', 'sunjun@'); // 관심 분야 없이 잘못된 주소
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하고 이동' }));
    const why = await screen.findByText(/입력을 확인해야 저장할 수 있어요/);
    expect(why).toBeInTheDocument();
    expect(why.textContent).not.toMatch(/빨간|붉은|초록|파란|색/); // 색을 못 보는 사람에게도 통하는 안내
    expect(screen.queryByText(/연결을 확인한 뒤/)).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/settings/profile');
    expect(puts(server)).toEqual([]);
  });

  it('does not ask when nothing changed', async () => {
    connectMockServer();
    const { router } = await open();
    await act(async () => { await router.navigate('/elsewhere'); });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/elsewhere');
  });
  it('does not ask after a successful save', async () => {
    connectMockServer();
    const { router } = await open();
    pick('백엔드');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    await act(async () => { await router.navigate('/elsewhere'); });
    expect(router.state.location.pathname).toBe('/elsewhere');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('profile form — 메일 알림', () => {
  const OPT_IN = '내 경험에 맞는 공고를 메일로 받을게요';
  const mailBox = () => screen.getByLabelText('메일 주소');
  const mailRegion = () => screen.getByRole('region', { name: '메일 알림' });

  it('F1: shows the 메일 알림 section at the very bottom of the form with its notice, address box and checkbox', async () => {
    connectMockServer();
    await open();
    const regions = screen.getAllByRole('region');
    expect(regions[regions.length - 1]).toBe(mailRegion());
    expect(within(mailRegion()).getByText('새로 올라온 공고 중 내 카드와 잘 맞는 곳을 찾으면 이 주소로 알려 드려요. 다른 용도로는 쓰지 않고, 언제든 끌 수 있어요.')).toBeInTheDocument();
    expect(mailBox()).toHaveAttribute('type', 'email');
    expect(mailBox()).toHaveAttribute('autocomplete', 'email');
    expect(screen.getByRole('checkbox', { name: OPT_IN })).not.toBeChecked();
  });

  it('F2: fills in the saved address and checkbox', async () => {
    putProfile({ ...sampleInput(), email: 'me@example.com', mailOptIn: true });
    connectMockServer();
    await open();
    expect(mailBox()).toHaveValue('me@example.com');
    expect(screen.getByRole('checkbox', { name: OPT_IN })).toBeChecked();
  });

  it('F3: refuses to save a checked box with an empty address and says to write one', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드'); pick(OPT_IN);
    await save();
    expect(within(mailRegion()).getByRole('alert')).toHaveTextContent('메일 주소를 적어 주세요');
    expect(puts(server)).toHaveLength(0);
  });

  it('F4: refuses to save an address with the wrong shape and says to check it', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드'); type('메일 주소', 'sunjun@'); pick(OPT_IN);
    await save();
    expect(within(mailRegion()).getByRole('alert')).toHaveTextContent('메일 주소를 다시 확인해 주세요');
    expect(puts(server)).toHaveLength(0);
  });

  it('F6: the save request carries the typed address and the checkbox, and an empty string once the address is cleared', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드'); type('메일 주소', ' me@example.com '); pick(OPT_IN);
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(puts(server)[0].body).toMatchObject({ email: 'me@example.com', mailOptIn: true });
    pick(OPT_IN); type('메일 주소', '');
    await save();
    await waitFor(() => expect(puts(server)).toHaveLength(2));
    expect(puts(server)[1].body).toMatchObject({ email: '', mailOptIn: false });
  });

  it('F5: saves with the address and the checkbox both left empty', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(puts(server)).toHaveLength(1);
    expect(within(mailRegion()).queryByRole('alert')).not.toBeInTheDocument();
  });
});
