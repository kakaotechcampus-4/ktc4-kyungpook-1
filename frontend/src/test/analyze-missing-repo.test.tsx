import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));

/** 브라우저 목 대신 fetch 를 목 라우터에 직접 잇는다. fail 이 주어지면 그 상태로 레포 조회만 실패시킨다. */
function stubApi(fail?: { status: number; code: string }) {
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    if (fail && /^\/repos\/[^/]+$/.test(path)) {
      return new Response(JSON.stringify({ data: null, error: { code: fail.code, message: 'x' } }), { status: fail.status });
    }
    const r = handle((init?.method ?? 'GET').toUpperCase(), path, url.searchParams, {}, true);
    return new Response(JSON.stringify({ data: r.data, error: r.error }), { status: r.status });
  }));
}

function mount(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <Routes><Route path="/repos/:repoId/run" element={<AnalyzePage />} /><Route path="/repos" element={<p>레포 목록 화면</p>} /></Routes>
  </MemoryRouter></QueryClientProvider>);
}

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('정리 시작 화면 — 레포를 못 찾거나 조회에 실패했을 때', () => {
  it('없는 레포 주소는 시작 화면이 아니라 못 찾았다고 알리고 목록으로 보낸다', async () => {
    stubApi();
    mount('/repos/zzz/run');
    expect(await screen.findByRole('heading', { name: '레포를 확인하지 못했어요' })).toBeInTheDocument();
    expect(screen.getByText('요청한 항목을 찾을 수 없어요')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '레포 목록으로' })).toHaveAttribute('href', '/repos');
    // 누르면 토스트로만 실패하던 버튼과 "진행 중인 작업이 없어요" 는 없다
    expect(screen.queryByRole('button', { name: '정리 시작' })).not.toBeInTheDocument();
    expect(screen.queryByText(/진행 중인 작업이 없어요/)).not.toBeInTheDocument();
    // 이름 자리에 '…' 가 영구히 남지 않는다
    expect(screen.getByRole('navigation').textContent).not.toContain('…');
    // 탭 제목도 진행 중이라고 하지 않는다
    expect(document.title).toBe('레포를 확인하지 못했어요 · Gitory');
  });

  it('서버 오류로 조회에 실패하면 다시 불러오기를 준다(찾을 수 없다고 단정하지 않는다)', async () => {
    stubApi({ status: 503, code: 'INTERNAL_ERROR' });
    mount('/repos/r1/run');
    expect(await screen.findByRole('heading', { name: '레포를 확인하지 못했어요' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 불러오기' })).toBeInTheDocument();
    expect(screen.queryByText('요청한 항목을 찾을 수 없어요')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '정리 시작' })).not.toBeInTheDocument();
  });

  it('있는 레포는 그대로 시작 화면을 보여 준다', async () => {
    stubApi();
    const repoId = (handle('GET', '/repos', new URLSearchParams(), {}, true).data as { id: string }[])[0].id;
    mount(`/repos/${repoId}/run`);
    expect(await screen.findByRole('heading', { name: '정리를 시작할까요' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: '정리 시작' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '레포를 확인하지 못했어요' })).not.toBeInTheDocument();
  });
});
