import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivitiesPage } from '@/features/activities/ActivitiesPage';
import { ActivityEditPage, ActivityNewPage } from '@/features/activities/ActivityFormPage';
import { ActivityFolderPage, DirectFolderPage, RepoFolderPage } from '@/features/cards/FolderPage';
import { CardPage } from '@/features/cards/CardPage';
import { CardsListPage } from '@/features/cards/CardsListPage';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, jsonError, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
// Radix 메뉴는 jsdom 에서 여는 데만 오래 걸린다. 값·접근성 이름이 같은 네이티브 select 로 바꿔 폼 로직만 시험한다(profile-form.test 와 같은 방식).
vi.mock('@/components/ui/Select', () => ({
  Select: ({ value, onChange, options, label, invalid, describedBy, disabled }: { value: string; onChange: (value: string) => void; options: { value: string; label: string }[]; label: string; invalid?: boolean; describedBy?: string; disabled?: boolean }) => (
    <select aria-label={label} aria-invalid={invalid || undefined} aria-describedby={describedBy} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      {options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
    </select>
  ),
}));

beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const routes = () => [
  page(<CardsListPage />, '/cards'), page(<DirectFolderPage />, '/cards/direct'), page(<RepoFolderPage />, '/cards/repo/:repoId'),
  page(<ActivityFolderPage />, '/cards/activity/:activityId'), page(<NewCardPage />, '/cards/new'), page(<p>카드 상세 화면</p>, '/cards/:cardId'),
  page(<ActivitiesPage />, '/activities'), page(<ActivityNewPage />, '/activities/new'), page(<ActivityEditPage />, '/activities/:activityId/edit'),
];
const FOLDERS = {
  auth: '레포 hong-dev, auth-service, 카드 4장, 기술 Java',
  fts: '레포 TaeHuiKKIM, free-tier-sleep, 카드 1장, 기술 카드만 있어요',
  club: '활동, 동아리, 교내 개발 동아리, 카드 2장',
  edu: '활동, 교육 과정, 카카오테크캠퍼스, 카드 1장',
  contest: '활동, 공모전과 해커톤, 해커톤 참가, 카드 0장, 아직 카드가 없어요 · 열어서 만들기',
};
const tiles = (root: ParentNode = document) => root.querySelectorAll('.experience-item');
const button = (name: string | RegExp) => screen.getByRole('button', { name });

describe('경험 카드 목록의 폴더 보기 (#522)', () => {
  it('기본 보기는 폴더다. 폴더 하나는 링크 하나이고 이름에 소유자·이름·장 수가 들어가며, 카드 타일은 폴더 안에만 있다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards');
    const auth = await screen.findByRole('link', { name: FOLDERS.auth });
    expect(auth).toHaveAttribute('href', '/cards/repo/r_auth');
    expect(screen.getByRole('link', { name: FOLDERS.fts })).toHaveAttribute('href', '/cards/repo/r_fts');
    expect(screen.getByRole('link', { name: FOLDERS.club })).toHaveAttribute('href', '/cards/activity/act_club');
    expect(screen.getByRole('link', { name: FOLDERS.edu })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: FOLDERS.contest })).toHaveAttribute('href', '/cards/activity/act_contest');
    expect(screen.getByRole('radio', { name: '폴더' })).toHaveAttribute('aria-checked', 'true');
    expect(tiles()).toHaveLength(0);
    // 앞판 글: 기술 카드만 있는 폴더와 빈 폴더에 안내 한 줄, 장식(뒤에 겹친 카드)은 접근성 트리에서 뺀다
    expect(screen.getByText('기술 카드만 있어요')).toBeInTheDocument();
    expect(screen.getByText('아직 카드가 없어요 · 열어서 만들기')).toBeInTheDocument();
    // 장 수는 종류 아이콘 옆 작은 글이고, 빈 폴더에는 없다(0장 대신 안내 한 줄이 말한다)
    expect(auth.querySelector('.folder__head .folder__count')).toHaveTextContent(/^4장$/);
    expect(screen.getByRole('link', { name: FOLDERS.contest }).querySelector('.folder__count')).toBeNull();
    // 이름 아래 미리 보기 한 줄: 최근 카드 두 장의 제목과 남은 장 수. 빈 폴더에는 없고, 링크 이름에도 들어가지 않는다(위 FOLDERS 그대로)
    expect(auth.querySelector('.folder__peek')).toHaveTextContent(/^[^·]+ · [^·]+ · \+2$/);
    expect(screen.getByRole('link', { name: FOLDERS.fts }).querySelector('.folder__peek')).toHaveTextContent(/^FreeTierSleep_Unity - 인트로 씬 연출 및 최적화 \(부분 기여\)$/);
    expect(screen.getByRole('link', { name: FOLDERS.contest }).querySelector('.folder__peek')).toBeNull();
    expect(auth.querySelector('.folder__back')).toHaveAttribute('aria-hidden', 'true');
    // 뒤로 겹쳐 보이는 카드는 장 수를 따르고(4장 두 겹, 2장 한 겹, 1장 없음), 카드가 없는 폴더만 점선(data-empty)이다
    expect(auth).toHaveAttribute('data-deck', '2');
    expect(screen.getByRole('link', { name: FOLDERS.club })).toHaveAttribute('data-deck', '1');
    expect(screen.getByRole('link', { name: FOLDERS.fts })).toHaveAttribute('data-deck', '0');
    expect(screen.getByRole('link', { name: FOLDERS.contest })).toHaveAttribute('data-empty', 'true');
    expect(auth).not.toHaveAttribute('data-empty');
    // 종류 아이콘도 장식이다
    expect(auth.querySelector('.folder__kind')).toHaveAttribute('aria-hidden', 'true');
    // 직접 쓴 카드가 없으니 그 폴더도 없다
    expect(screen.queryByRole('link', { name: /직접 쓴 카드/ })).not.toBeInTheDocument();
  });

  it('폴더 보기에는 상태 탭과 검색이 없고, 만들기는 폴더 위의 활동 만들기 하나뿐이다. 경험 카드는 폴더 안에서 만든다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards');
    await screen.findByRole('link', { name: FOLDERS.auth });
    expect(screen.queryByRole('textbox', { name: '카드 이름으로 검색' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^(전체|작성 중|확정됨|보완 필요)/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '활동 만들기' })).toHaveAttribute('href', '/activities/new');
    for (const gone of ['경험 카드 만들기', '활동 관리', '직접 작성', '레포에서 만들기']) expect(screen.queryByRole('link', { name: gone })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '레포에서 만들기' })).not.toBeInTheDocument();
  });

  it('예전에 고른 레포별 보기는 폴더로 읽고, 최근순을 고르면 카드 타일이 나온다(활동 카드 포함)', async () => {
    localStorage.setItem('gitory.cards.view', JSON.stringify('repo'));
    connectMockServer();
    mountApp(routes(), '/cards');
    await screen.findByRole('link', { name: FOLDERS.auth });
    fireEvent.click(screen.getByRole('radio', { name: '최근순' }));
    await waitFor(() => expect(tiles()).toHaveLength(8));
    expect(screen.getAllByText('교내 개발 동아리', { selector: '.experience-item__meta span' })).toHaveLength(2); // 활동 카드는 타일에서도 어느 활동인지 보인다
    expect(localStorage.getItem('gitory.cards.view')).toBe('"recent"');
  });

  it('직접 쓴 카드(레포도 활동도 없는 카드)는 한 폴더에 모은다', async () => {
    connectMockServer();
    handle('POST', '/cards/manual/draft', new URLSearchParams(), { title: '그냥 쓴 카드', period: null, repoId: null }, true);
    mountApp(routes(), '/cards');
    const direct = await screen.findByRole('link', { name: '레포와 활동 없이 쓴 카드, 직접 쓴 카드, 카드 1장' });
    expect(direct).toHaveAttribute('href', '/cards/direct');
    expect(direct.parentElement!.parentElement!.lastElementChild).toBe(direct.parentElement); // 맨 뒤
  });

  it('목록 보기에서 고른 검색어와 탭은 폴더 보기에 걸리지 않는다 — 폴더 보기에는 그것을 풀 칸이 없다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards');
    await screen.findByRole('link', { name: FOLDERS.auth });
    fireEvent.click(screen.getByRole('radio', { name: '최근순' }));
    fireEvent.change(await screen.findByRole('textbox', { name: '카드 이름으로 검색' }), { target: { value: 'no-such-card' } });
    fireEvent.click(screen.getByRole('button', { name: /^확정됨/ }));
    expect(await screen.findByText('조건에 맞는 카드가 없어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: '폴더' }));
    expect(await screen.findByRole('link', { name: FOLDERS.auth })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: FOLDERS.fts })).toBeInTheDocument();
    expect(screen.queryByText('조건에 맞는 카드가 없어요')).not.toBeInTheDocument();
    expect(screen.queryByText(/^\d+개 보기$/)).not.toBeInTheDocument();
  });

  it('서버가 레포 정보를 주지 않으면(이전 서버) 폴더 보기를 숨기고 최근순으로 본다', async () => {
    connectMockServer((call) => {
      if (call.method !== 'GET' || call.path !== '/cards') return undefined;
      const cards = (handle('GET', '/cards', new URLSearchParams(), {}, true).data as Array<Record<string, unknown>>).map(({ repo, activity, ...rest }) => { void repo; void activity; return rest; });
      return new Response(JSON.stringify({ data: cards, error: null }), { status: 200 });
    });
    mountApp(routes(), '/cards');
    await waitFor(() => expect(tiles()).toHaveLength(8));
    expect(screen.queryByRole('radio', { name: '폴더' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '최근순' })).toHaveAttribute('aria-checked', 'true');
  });

  it('활동 목록만 못 읽어도 카드가 있는 폴더는 보이고, 빠질 수 있는 폴더가 있다고 알린 뒤 다시 불러올 수 있다', async () => {
    let fail = true;
    connectMockServer((call) => (fail && call.path === '/activities' ? jsonError(500, 'INTERNAL_ERROR') : undefined));
    mountApp(routes(), '/cards');
    await screen.findByRole('link', { name: FOLDERS.auth });
    expect(await screen.findByText('활동 목록을 불러오지 못했어요. 카드가 없는 활동은 보이지 않을 수 있어요.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: FOLDERS.club })).toBeInTheDocument(); // 카드에 적힌 활동으로 만든 폴더
    expect(screen.queryByRole('link', { name: FOLDERS.contest })).not.toBeInTheDocument();
    fail = false;
    fireEvent.click(button('다시 불러오기'));
    expect(await screen.findByRole('link', { name: FOLDERS.contest })).toBeInTheDocument();
  });

  it('카드가 하나도 없으면 기존 빈 상태에 활동 만들기 길이 하나 더 붙는다(활동이 있으면 활동 보기)', async () => {
    db.cards = [];
    connectMockServer();
    mountApp(routes(), '/cards');
    expect(await screen.findByText('아직 카드가 없어요')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /만들어 둔 활동 3개 보기/ })).toHaveAttribute('href', '/activities');
    expect(document.querySelectorAll('.btn--primary')).toHaveLength(1);
  });
});

describe('폴더 안', () => {
  it('레포 폴더: 위에 소유자·이름·카드 수·후보 보드 하나, 아래 기술 카드와 경험 카드 두 구역', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/repo/r_auth');
    expect(await screen.findByRole('heading', { level: 1, name: 'auth-service' })).toBeInTheDocument();
    expect(screen.getByText('hong-dev')).toBeInTheDocument();
    expect(screen.getByText('기술 카드 2장 · 경험 카드 2장 · 확정 2장')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '후보 보드' })).toHaveAttribute('href', '/repos/r_auth/candidates');
    expect(document.querySelectorAll('.folder-head__actions .btn')).toHaveLength(1);
    const tech = screen.getByRole('region', { name: '기술 카드' });
    const experience = screen.getByRole('region', { name: '경험 카드' });
    expect(tiles(tech)).toHaveLength(2);
    expect(tiles(experience)).toHaveLength(2);
    expect(within(tech).getByRole('heading', { level: 2, name: '기술 카드' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'breadcrumb' })).toHaveTextContent('경험 카드');
  });

  it('경험 카드가 없는 레포: 경험 카드 구역에는 점선 타일 하나만 있다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/repo/r_fts');
    const experience = await screen.findByRole('region', { name: '경험 카드' });
    expect(tiles(experience)).toHaveLength(0);
    expect(within(experience).getByRole('button', { name: '경험 카드 만들기' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '경험 카드 만들기' })).toHaveLength(1);
    expect(tiles(screen.getByRole('region', { name: '기술 카드' }))).toHaveLength(1);
  });

  it('활동 폴더: 후보 보드는 없고 활동 고치기·활동 삭제가 있다. 경험 카드 만들기는 구역 끝의 점선 타일이고 기술 카드 구역은 없다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/activity/act_club');
    expect(await screen.findByRole('heading', { level: 1, name: '교내 개발 동아리' })).toBeInTheDocument();
    expect(screen.getByText('활동, 동아리')).toBeInTheDocument();
    expect(screen.getByText('카드 2장 · 확정 1장')).toBeInTheDocument();
    expect(screen.getByText('동아리, 2025년 3월부터 진행 중, 백엔드 파트장')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '후보 보드' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '기술 카드' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '경험 카드 만들기' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '활동 고치기' })).toHaveAttribute('href', '/activities/act_club/edit');
    const experience = screen.getByRole('region', { name: '경험 카드' });
    expect(tiles(experience)).toHaveLength(2);
    expect(within(experience).getByRole('button', { name: '경험 카드 만들기' })).toBeInTheDocument();
  });

  it('카드가 없는 활동과 없는 활동 주소를 각각 안내한다', async () => {
    connectMockServer();
    const empty = mountApp(routes(), '/cards/activity/act_contest');
    expect(await screen.findByRole('button', { name: '경험 카드 만들기' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: '해커톤 참가' })).toBeInTheDocument();
    expect(tiles()).toHaveLength(0);
    empty.unmount();
    mountApp(routes(), '/cards/activity/nope');
    expect(await screen.findByText('이미 삭제됐거나 볼 수 없는 활동이에요.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '경험 카드 목록' })).toHaveAttribute('href', '/cards');
  });
});

describe('활동 삭제', () => {
  it('폴더 안에서 지우면 카드 N장(확정 M장)을 말로 확인한 뒤 안의 카드까지 지우고 목록으로 돌아간다', async () => {
    const server = connectMockServer();
    mountApp(routes(), '/cards/activity/act_club');
    fireEvent.click(await screen.findByRole('button', { name: '활동 삭제' }));
    const dialog = await screen.findByRole('dialog', { name: '활동을 삭제할까요?' });
    expect(within(dialog).getByText('교내 개발 동아리')).toBeInTheDocument();
    expect(within(dialog).getByText('카드 2장(확정 1장)도 함께 지워져요.')).toBeInTheDocument();
    expect(within(dialog).getByText('확정한 카드는 공고 매칭과 자소서에 쓰였을 수 있어요.')).toBeInTheDocument();
    expect(within(dialog).getByText('삭제한 활동과 카드는 되돌릴 수 없어요')).toBeInTheDocument();
    // 취소하면 아무것도 지우지 않는다
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(server.calls.some((call) => call.method === 'DELETE')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '활동 삭제' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '삭제' }));
    expect(await screen.findByRole('heading', { level: 1, name: /^경험 카드/ })).toBeInTheDocument();
    expect(server.calls.filter((call) => call.method === 'DELETE').map((call) => call.path)).toEqual(['/activities/act_club']);
    expect(db.cards.some((card) => card.activityId === 'act_club')).toBe(false);
    expect(db.activities.some((activity) => activity.id === 'act_club')).toBe(false);
    expect(await screen.findByRole('link', { name: FOLDERS.auth })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /교내 개발 동아리/ })).not.toBeInTheDocument();
  });

  it('카드가 없는 활동은 활동만 지워진다고 말한다', async () => {
    connectMockServer();
    mountApp(routes(), '/activities');
    fireEvent.click(await screen.findByRole('button', { name: '해커톤 참가 삭제' }));
    const dialog = await screen.findByRole('dialog', { name: '활동을 삭제할까요?' });
    expect(within(dialog).getByText('안에 카드가 없어서 활동만 지워져요.')).toBeInTheDocument();
    expect(within(dialog).queryByText(/확정한 카드는/)).not.toBeInTheDocument();
  });

  it('목록을 연 뒤 카드가 늘었으면 대화상자가 열릴 때 다시 읽은 최신 장 수를 보여 준다', async () => {
    connectMockServer();
    mountApp(routes(), '/activities');
    const open = await screen.findByRole('button', { name: '해커톤 참가 삭제' });
    handle('POST', '/cards/manual/draft', new URLSearchParams(), { title: '다른 탭에서 만든 카드', period: null, repoId: null, activityId: 'act_contest' }, true);
    fireEvent.click(open);
    expect(await screen.findByText('카드 1장(확정 0장)도 함께 지워져요.')).toBeInTheDocument();
    expect(screen.queryByText('안에 카드가 없어서 활동만 지워져요.')).not.toBeInTheDocument();
  });

  it('이미 지워진 활동(404)은 지운 것으로 보고 실패로 말하지 않는다', async () => {
    connectMockServer((call) => (call.method === 'DELETE' ? jsonError(404, 'NOT_FOUND') : undefined));
    mountApp(routes(), '/activities');
    fireEvent.click(await screen.findByRole('button', { name: '해커톤 참가 삭제' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '삭제' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('서버가 실패하면 대화상자에 남아 이유를 보여 주고 활동은 그대로다', async () => {
    connectMockServer((call) => (call.method === 'DELETE' ? jsonError(503, 'UNAVAILABLE') : undefined));
    mountApp(routes(), '/activities');
    fireEvent.click(await screen.findByRole('button', { name: '해커톤 참가 삭제' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '삭제' }));
    expect(await within(await screen.findByRole('dialog')).findByRole('alert')).toBeInTheDocument();
    expect(db.activities.some((activity) => activity.id === 'act_contest')).toBe(true);
  });
});

describe('활동 목록', () => {
  it('활동마다 이름 링크(폴더 안), 종류·기간·역할, 카드 수와 고치기·삭제가 있다. 이름은 활동 이름이 아닌 값을 지어내지 않는다', async () => {
    connectMockServer();
    mountApp(routes(), '/activities');
    const club = await screen.findByRole('link', { name: '교내 개발 동아리, 동아리, 2025년 3월부터 진행 중, 백엔드 파트장, 카드 2장, 확정 1장' });
    expect(club).toHaveAttribute('href', '/cards/activity/act_club');
    expect(screen.getByRole('link', { name: '교내 개발 동아리 고치기' })).toHaveAttribute('href', '/activities/act_club/edit');
    expect(screen.getByRole('link', { name: /해커톤 참가, 공모전과 해커톤, 2026년 5월부터 2026년 6월까지, 카드 0장, 확정 0장/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('활동 3');
  });

  it('카드가 달라진 뒤 다시 열면 카드 수도 새로 읽는다(낡은 숫자를 보여 주지 않는다)', async () => {
    connectMockServer();
    // 운영처럼 읽은 값을 한동안 신선하게 두는 클라이언트 — 그래도 화면을 열면 다시 읽어야 한다
    const app = mountApp(routes(), '/activities', new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000 }, mutations: { retry: false } } }));
    expect(await screen.findByRole('link', { name: /^교내 개발 동아리, .*카드 2장, 확정 1장$/ })).toBeInTheDocument();
    handle('DELETE', '/cards/card_a2', new URLSearchParams(), {}, true); // 다른 곳에서 카드를 지웠다
    await act(async () => { await app.router.navigate('/cards'); });
    await screen.findByRole('link', { name: FOLDERS.auth });
    await act(async () => { await app.router.navigate('/activities'); });
    expect(await screen.findByRole('link', { name: /^교내 개발 동아리, .*카드 1장, 확정 1장$/ })).toBeInTheDocument();
  });

  it('활동이 없으면 안내와 활동 만들기 하나뿐이다', async () => {
    db.activities = [];
    connectMockServer();
    mountApp(routes(), '/activities');
    expect(await screen.findByText('아직 활동이 없어요')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: '활동 만들기' })).toHaveLength(1);
  });

  it('불러오기가 실패하면 다시 불러오기를 준다', async () => {
    connectMockServer((call) => (call.path === '/activities' ? jsonError(500, 'INTERNAL_ERROR') : undefined));
    mountApp(routes(), '/activities');
    expect(await screen.findByRole('alert')).toHaveTextContent(/서버에 연결하지 못했어요|서버에서 처리하지 못했어요/);
    expect(screen.getByRole('button', { name: '다시 불러오기' })).toBeInTheDocument();
  });
});

describe('활동 만들기와 고치기', () => {
  const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
  const posts = (server: ReturnType<typeof connectMockServer>) => server.fetcher.mock.calls.filter(([, init]) => init?.method === 'POST' && String(init.body).includes('"type"'));

  it('빈 채로 저장하면 칸마다 안내가 나오고 요청은 가지 않는다', async () => {
    const server = connectMockServer();
    mountApp(routes(), '/activities/new');
    fireEvent.click(await screen.findByRole('button', { name: '활동 만들기' }));
    expect(await screen.findByText('활동 이름을 적어 주세요.')).toBeInTheDocument();
    expect(screen.getByText('종류를 골라 주세요.')).toBeInTheDocument();
    expect(screen.getByText('시작한 달을 골라 주세요.')).toBeInTheDocument();
    expect(screen.getByText(/끝난 달을 골라 주세요/)).toBeInTheDocument();
    expect(screen.getByLabelText('활동 이름')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('활동 이름')).toHaveFocus();
    expect(posts(server)).toHaveLength(0);
    // 고치면 그 칸의 안내는 바로 사라진다
    type('활동 이름', '스터디');
    expect(screen.queryByText('활동 이름을 적어 주세요.')).not.toBeInTheDocument();
  });

  it('채워 저장하면 Idempotency-Key 와 함께 한 번 보내고 만든 활동의 폴더로 간다. 선택 칸이 비면 보내지 않는다', async () => {
    const server = connectMockServer();
    mountApp(routes(), '/activities/new');
    await screen.findByRole('button', { name: '활동 만들기' });
    type('활동 이름', '  알고리즘 스터디  ');
    fireEvent.change(screen.getByRole('combobox', { name: '종류' }), { target: { value: 'STUDY' } });
    type('시작한 달', '2026-03');
    fireEvent.click(screen.getByRole('checkbox', { name: '진행 중이에요' }));
    expect(screen.getByLabelText('끝난 달')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '활동 만들기' }));
    expect(await screen.findByRole('heading', { level: 1, name: '알고리즘 스터디' })).toBeInTheDocument();
    const [[, init]] = posts(server);
    expect(JSON.parse(String(init!.body))).toEqual({ name: '알고리즘 스터디', type: 'STUDY', startMonth: '2026-03' });
    expect((init!.headers as Record<string, string>)['Idempotency-Key']).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(db.activities.filter((activity) => activity.name === '알고리즘 스터디')).toHaveLength(1);
    expect(screen.getByRole('button', { name: '경험 카드 만들기' })).toBeInTheDocument();
  });

  it('응답을 못 받아 다시 저장해도 같은 키를 쓰고 활동은 하나만 생긴다', async () => {
    // 서버는 만들었는데 응답이 사라진 경우: 첫 요청은 목 서버가 처리하고 화면에는 실패로 돌려준다
    let lost = true;
    const keys: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost');
      const method = (init?.method ?? 'GET').toUpperCase();
      const headers = Object.fromEntries(Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]));
      const result = handle(method, url.pathname.replace(/^\/api/, ''), url.searchParams, typeof init?.body === 'string' ? JSON.parse(init.body) : {}, true, headers);
      if (method === 'POST' && url.pathname.endsWith('/activities')) {
        keys.push(headers['idempotency-key']);
        if (lost) { lost = false; return new Response('', { status: 503 }); }
      }
      return new Response(JSON.stringify({ data: result.data, error: result.error }), { status: result.status });
    }));
    mountApp(routes(), '/activities/new');
    await screen.findByRole('button', { name: '활동 만들기' });
    type('활동 이름', '캠프'); fireEvent.change(screen.getByRole('combobox', { name: '종류' }), { target: { value: 'EXTERNAL' } });
    type('시작한 달', '2026-01'); fireEvent.click(screen.getByRole('checkbox', { name: '진행 중이에요' }));
    fireEvent.click(screen.getByRole('button', { name: '활동 만들기' }));
    expect(await screen.findByText(/서버에 연결하지 못했어요|서버에서 처리하지 못했어요/)).toBeInTheDocument();
    expect(screen.getByLabelText('활동 이름')).toHaveValue('캠프'); // 입력은 그대로
    fireEvent.click(screen.getByRole('button', { name: '활동 만들기' }));
    expect(await screen.findByRole('heading', { level: 1, name: '캠프' })).toBeInTheDocument();
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
    expect(db.activities.filter((activity) => activity.name === '캠프')).toHaveLength(1);
  });

  it('고치기: 바뀐 칸만 보내고, 바뀐 게 없으면 저장 버튼이 잠긴다', async () => {
    const server = connectMockServer();
    mountApp(routes(), '/activities/act_edu/edit');
    const save = await screen.findByRole('button', { name: '고친 내용 저장' });
    expect(save).toBeDisabled();
    expect(screen.getByText('바뀐 내용이 없어요')).toBeInTheDocument();
    expect(screen.getByLabelText('활동 이름')).toHaveValue('카카오테크캠퍼스');
    expect(screen.getByRole('combobox', { name: '종류' })).toHaveValue('EDUCATION');
    type('맡은 역할 (선택)', '');
    expect(save).toBeEnabled();
    fireEvent.click(save);
    expect(await screen.findByRole('heading', { level: 1, name: '카카오테크캠퍼스' })).toBeInTheDocument();
    const patches = server.calls.filter((call) => call.method === 'PATCH');
    expect(patches).toEqual([{ method: 'PATCH', path: '/activities/act_edu', body: { role: '' } }]);
    expect(db.activities.find((activity) => activity.id === 'act_edu')!.role).toBeNull();
  });

  it('없는 활동을 고치려 하면 안내하고 목록으로 보낸다', async () => {
    connectMockServer();
    mountApp(routes(), '/activities/nope/edit');
    expect(await screen.findByText('이미 삭제됐거나 볼 수 없는 활동이에요.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '활동 목록' })).toHaveAttribute('href', '/activities');
  });

  it('서버가 입력을 거절해도 서버 문구는 보이지 않고 입력은 그대로 남는다', async () => {
    connectMockServer((call) => (call.method === 'PATCH' ? jsonError(400, 'INVALID_REQUEST') : undefined));
    mountApp(routes(), '/activities/act_edu/edit');
    await screen.findByRole('button', { name: '고친 내용 저장' });
    type('활동 이름', '바꾼 이름');
    fireEvent.click(screen.getByRole('button', { name: '고친 내용 저장' }));
    expect(await screen.findByText('입력을 확인해 주세요')).toBeInTheDocument();
    expect(screen.getByLabelText('활동 이름')).toHaveValue('바꾼 이름');
    expect(screen.queryByText('서버 응답')).not.toBeInTheDocument();
  });
});

describe('활동 안에서 경험 카드 만들기', () => {
  const settle = () => act(async () => { await Promise.resolve(); });

  it('?activity= 로 오면 활동이 골라져 있고, 첫 저장 요청에 activityId 를 싣는다(레포는 싣지 않는다)', async () => {
    const server = connectMockServer();
    mountApp(routes(), '/cards/new?activity=act_club&write=1');
    await waitFor(() => expect(screen.getByRole('combobox', { name: '활동' })).toHaveValue('act_club'));
    expect(screen.getByRole('combobox', { name: '관련 레포' })).toHaveValue('');
    fireEvent.change(screen.getByLabelText('카드 제목'), { target: { value: '동아리 발표 경험' } });
    fireEvent.click(screen.getByRole('button', { name: /임시 저장 시작|작성 시작/ }));
    await waitFor(() => expect(db.cards.some((card) => card.title === '동아리 발표 경험')).toBe(true));
    await settle();
    const created = db.cards.find((card) => card.title === '동아리 발표 경험')!;
    expect(created).toMatchObject({ activityId: 'act_club', repo: null, kind: 'QUALITATIVE' });
    const create = server.calls.find((call) => call.method === 'POST' && call.path === '/cards/manual/draft')!;
    expect(create.body).toEqual({ title: '동아리 발표 경험', period: null, repoId: null, activityId: 'act_club' });
    await waitFor(() => expect(screen.getByRole('combobox', { name: '활동' })).toBeDisabled());
    expect(screen.getByRole('combobox', { name: '활동' })).toHaveValue('act_club');
  });

  it('?repo= 가 있으면 레포가 먼저이고, 둘을 함께 보내지 않는다. 하나를 고르면 다른 하나는 비운다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/new?repo=r_auth&activity=act_club');
    await waitFor(() => expect(screen.getByRole('combobox', { name: '관련 레포' })).toHaveValue('r_auth'));
    expect(screen.getByRole('combobox', { name: '활동' })).toHaveValue('');
    fireEvent.change(screen.getByRole('combobox', { name: '활동' }), { target: { value: 'act_edu' } });
    expect(screen.getByRole('combobox', { name: '관련 레포' })).toHaveValue('');
    expect(screen.getByRole('combobox', { name: '활동' })).toHaveValue('act_edu');
  });

  it('없는 활동·레포 주소는 목록을 읽은 뒤 비운다', async () => {
    connectMockServer();
    mountApp(routes(), '/cards/new?activity=nope');
    await waitFor(() => expect(screen.getByRole('combobox', { name: '활동' })).toHaveValue(''));
    expect(screen.getByRole('combobox', { name: '활동' })).toBeEnabled();
  });
});

describe('카드 상세의 활동 표시', () => {
  const detailRoutes = () => [page(<CardPage />, '/cards/:cardId'), page(<ActivityFolderPage />, '/cards/activity/:activityId')];

  it('확정한 활동 카드: 경로에 경험 카드 다음 활동 이름이 오고, 출처에도 활동이 있다. 이름을 누르면 그 활동 폴더로 간다', async () => {
    connectMockServer();
    mountApp(detailRoutes(), '/cards/card_a1');
    const crumbs = await screen.findByRole('navigation', { name: 'breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: '경험 카드' })).toHaveAttribute('href', '/cards');
    expect(within(crumbs).getByRole('link', { name: '교내 개발 동아리' })).toHaveAttribute('href', '/cards/activity/act_club');
    expect(screen.getByText('경험 카드', { selector: '.badge' })).toBeInTheDocument(); // 종류 이름도 폴더 안 구역 이름과 같다
    const source = screen.getByText('출처').parentElement!;
    expect(within(source).getByRole('link', { name: '교내 개발 동아리' })).toHaveAttribute('href', '/cards/activity/act_club');
    expect(source).toHaveTextContent('활동 교내 개발 동아리, 직접 작성');
    fireEvent.click(within(crumbs).getByRole('link', { name: '교내 개발 동아리' }));
    expect(await screen.findByRole('heading', { level: 1, name: '교내 개발 동아리' })).toBeInTheDocument();
  });

  it('작성 중인 활동 카드도 경로에서 활동 폴더로 돌아갈 수 있다. 레포 카드의 경로는 그대로다', async () => {
    connectMockServer();
    const draft = mountApp(detailRoutes(), '/cards/card_a2');
    const crumbs = await screen.findByRole('navigation', { name: 'breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: '교내 개발 동아리' })).toHaveAttribute('href', '/cards/activity/act_club');
    draft.unmount();
    mountApp(detailRoutes(), '/cards/card_01');
    const repoCrumbs = await screen.findByRole('navigation', { name: 'breadcrumb' });
    expect(within(repoCrumbs).getByRole('link', { name: 'auth-service' })).toHaveAttribute('href', '/repos/r_auth/candidates');
    expect(within(repoCrumbs).queryByRole('link', { name: /동아리/ })).not.toBeInTheDocument();
  });
});
