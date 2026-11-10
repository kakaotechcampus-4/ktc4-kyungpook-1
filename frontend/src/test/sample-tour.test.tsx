import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { assistantSupported } from '@/api/capabilities';
import { SampleBand } from '@/components/layout/SampleBand';
import { SampleTourLink } from '@/features/auth/SampleTourLink';
import { installDemoApi } from '@/mock/browser';
import { SAMPLE_KEY, demoEnabled } from '@/mock/sampleTour';

const assign = vi.fn();
const flagged = () => ({ getItem: (k: string) => (k === SAMPLE_KEY ? '1' : null), setItem: vi.fn(), removeItem: vi.fn() });
const empty = () => ({ getItem: () => null, setItem: vi.fn(), removeItem: vi.fn() });

beforeEach(() => {
  sessionStorage.clear(); assign.mockClear();
  vi.stubGlobal('location', { ...window.location, assign, origin: window.location.origin, search: '' });
});
afterEach(() => { cleanup(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

it('T1 turns the demo on for a production build only while the sample flag is set', () => {
  expect(demoEnabled({ VITE_API_MOCK: 'false' }, flagged())).toBe(true);
  expect(demoEnabled({ VITE_API_MOCK: 'false' }, empty())).toBe(false);
  expect(demoEnabled({}, empty())).toBe(true);
});

it('T2 starts the tour from the landing link: flag and session set, db dropped, reload at /', () => {
  sessionStorage.setItem('gitory.demo.db', '{}');
  render(<SampleTourLink />);
  expect(screen.getByText('샘플 데이터로 먼저 써 보기')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '둘러보기' }));
  expect(sessionStorage.getItem(SAMPLE_KEY)).toBe('1');
  expect(sessionStorage.getItem('gitory.demo.session')).toBe('1');
  expect(sessionStorage.getItem('gitory.demo.db')).toBeNull();
  expect(assign).toHaveBeenCalledWith('/');
});

it('T3 shows the band only during the tour', () => {
  const { container } = render(<SampleBand />);
  expect(container).toBeEmptyDOMElement();
  sessionStorage.setItem(SAMPLE_KEY, '1');
  render(<SampleBand />);
  expect(screen.getByText('샘플 데이터로 둘러보는 중이에요 · 실제 분석이 아니에요')).toBeInTheDocument();
});

it('T3 clears the flag, db and session when leaving and lands on /login', () => {
  for (const k of [SAMPLE_KEY, 'gitory.demo.session', 'gitory.demo.db']) sessionStorage.setItem(k, '1');
  render(<SampleBand />);
  fireEvent.click(screen.getByRole('button', { name: '나가기' }));
  expect([SAMPLE_KEY, 'gitory.demo.session', 'gitory.demo.db'].map((k) => sessionStorage.getItem(k))).toEqual([null, null, null]);
  expect(assign).toHaveBeenCalledWith('/login');
});

it('T3 the primary button also ends the tour', () => {
  sessionStorage.setItem(SAMPLE_KEY, '1');
  render(<SampleBand />);
  fireEvent.click(screen.getByRole('button', { name: '내 GitHub 로 시작' }));
  expect(sessionStorage.getItem(SAMPLE_KEY)).toBeNull();
  expect(assign).toHaveBeenCalledWith('/login');
});

it('T4 opens the home one-line command during the tour even when the env is off', () => {
  expect(assistantSupported({}, empty())).toBe(false);
  expect(assistantSupported({}, flagged())).toBe(true);
  expect(assistantSupported({ VITE_ASSISTANT_ENABLED: 'true' }, empty())).toBe(true);
});

it('T5 installs the browser fetch shim on a production build when the flag is set', () => {
  vi.stubEnv('VITE_API_MOCK', 'false');
  const original = window.fetch;
  installDemoApi();
  expect(window.fetch).toBe(original);
  sessionStorage.setItem(SAMPLE_KEY, '1');
  installDemoApi();
  expect(window.fetch).not.toBe(original);
  window.fetch = original;
});
