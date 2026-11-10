import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfilePage } from '@/features/profile/ProfilePage';
import { resetDb } from '@/mock/store';
import { track } from '@/lib/track';
import { connectMockServer, jsonError, mountApp, page } from './profileHarness';

// 호출 기록은 남기되 실제 track() 도 그대로 돌려서, 정말로 전송되는 본문까지 확인한다
vi.mock('@/lib/track', async (importOriginal) => ({ track: vi.fn((await importOriginal<typeof import('@/lib/track')>()).track) }));
vi.mock('@/components/ui/Select', () => ({
  Select: ({ value, onChange, options, label }: { value: string; onChange: (value: string) => void; options: { value: string; label: string }[]; label: string }) => (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>{options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</select>
  ),
}));

/** 화면에 쓸 법하지 않은 값 — 어디든 새어 나가면 이 조각으로 찾는다 */
const SECRETS = ['secretZXQ@example.com'];

beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks(); localStorage.clear(); });
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

async function fillEverythingAndSave() {
  mountApp([page(<ProfilePage />, '/settings/profile')], '/settings/profile');
  await screen.findByRole('checkbox', { name: '백엔드' });
  fireEvent.click(screen.getByRole('checkbox', { name: '백엔드' }));
  type('메일 주소', SECRETS[0]);
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  await waitFor(() => expect(screen.getAllByRole('status').map((el) => el.textContent).join()).toContain('저장했어요'));
}
const serialized = (value: unknown) => JSON.stringify(value);
const readBlob = (blob: Blob) => new Promise<string>((resolve) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.readAsText(blob); });

describe('personal data stays out of analytics, logs, URLs and storage', () => {
  it('track() only ever receives event names and counts', async () => {
    const consoles = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    const sent: Blob[] = [];
    const hadBeacon = 'sendBeacon' in navigator;
    Object.defineProperty(navigator, 'sendBeacon', { value: (_url: string, body: Blob) => { sent.push(body); return true; }, configurable: true, writable: true });
    const server = connectMockServer();

    await fillEverythingAndSave();

    const calls = vi.mocked(track).mock.calls;
    expect(calls.map(([name]) => name)).toContain('profile_saved');
    for (const [name, props = {}] of calls) {
      expect(name).toMatch(/^profile_/);
      for (const value of Object.values(props)) expect(['number', 'boolean']).toContain(typeof value); // 문자열 · null 도 허용하지 않는다
    }
    expect(vi.mocked(track)).toHaveBeenCalledWith('profile_saved', { educationCount: 0, careerCount: 0, languageCount: 0, certificateCount: 0, interestCount: 1 });
    for (const secret of SECRETS) expect(serialized(calls)).not.toContain(secret);

    // 요청 주소·쿼리스트링에 값이 없다 — 값은 PUT 본문에만 있다
    for (const call of server.calls) for (const secret of SECRETS) expect(call.path).not.toContain(secret);
    expect(serialized(server.calls.filter((c) => c.method === 'PUT'))).toContain('ZXQ');
    for (const call of server.fetcher.mock.calls) {
      const init = call[1] as RequestInit | undefined;
      for (const secret of SECRETS) expect(String(call[0])).not.toContain(secret);
      if (init?.method !== 'PUT') expect(String(init?.body ?? '')).not.toContain('ZXQ');
    }
    // 실제로 전송된 지표 본문(sendBeacon)에도, 콘솔에도 없다
    const beaconTexts = await Promise.all(sent.map(readBlob));
    expect(beaconTexts.length).toBeGreaterThan(0);
    expect(beaconTexts.join()).toContain('profile_saved');
    expect(beaconTexts.join()).not.toContain('ZXQ');
    for (const text of beaconTexts) expect(JSON.parse(text).path).not.toContain('ZXQ');
    if (!hadBeacon) delete (navigator as unknown as Record<string, unknown>).sendBeacon;
    for (const spy of consoles) for (const secret of SECRETS) expect(serialized(spy.mock.calls)).not.toContain(secret);
    // 브라우저 저장소에는 아무것도 남기지 않는다(건너뛰기도 서버가 기억한다)
    expect(serialized({ ...localStorage })).not.toContain('ZXQ');
    expect(serialized({ ...sessionStorage })).not.toContain('ZXQ');
    // 주소창
    expect(location.href).not.toContain('ZXQ');
  }, 20000); // 전체 시험이 한꺼번에 돌 때는 5초가 모자랄 수 있다

  it('a failed save logs and reports nothing with the values either', async () => {
    const consoles = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    connectMockServer((call) => (call.method === 'PUT' ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    mountApp([page(<ProfilePage />, '/settings/profile')], '/settings/profile');
    await screen.findByRole('checkbox', { name: '백엔드' });
    fireEvent.click(screen.getByRole('checkbox', { name: '백엔드' }));
    type('메일 주소', SECRETS[0]);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await screen.findByRole('alert');
    await act(async () => { await Promise.resolve(); });
    expect(vi.mocked(track)).not.toHaveBeenCalledWith('profile_saved', expect.anything());
    for (const [, props = {}] of vi.mocked(track).mock.calls) expect(serialized(props)).not.toContain('ZXQ');
    for (const spy of consoles) expect(serialized(spy.mock.calls)).not.toContain('ZXQ');
  });
});

/** `track(` 뒤 괄호가 닫힐 때까지의 인자 문자열 */
function trackCalls(source: string): string[] {
  const out: string[] = [];
  for (const match of source.matchAll(/(?<![\w.])track\(/g)) {
    let depth = 1, i = match.index! + match[0].length;
    const start = i;
    for (; i < source.length && depth > 0; i++) depth += source[i] === '(' ? 1 : source[i] === ')' ? -1 : 0;
    out.push(source.slice(start, i - 1).trim());
  }
  return out;
}

describe('source guard: profile code cannot call track() with anything but counts', () => {
  const root = join(__dirname, '..');
  const files = [
    ...readdirSync(join(root, 'features/profile')).map((name) => join(root, 'features/profile', name)),
    join(root, 'api/profile.ts'), join(root, 'lib/profileForm.ts'), join(root, 'mock/profile.ts'),
  ].filter((file) => /\.(ts|tsx)$/.test(file));

  it('finds the profile sources', () => { expect(files.length).toBeGreaterThanOrEqual(7); });
  it('every track() call names a profile_* event and passes only *Count values', () => {
    const calls = files.flatMap((file) => trackCalls(readFileSync(file, 'utf8')).map((args) => ({ file, args })));
    expect(calls.length).toBeGreaterThan(0);
    for (const { file, args } of calls) {
      const ok = /^'profile_saved', \{ educationCount: saved\.educations\.length, careerCount: saved\.careers\.length, languageCount: saved\.languages\.length, certificateCount: saved\.certifications\.length, interestCount: saved\.interests\.length \}$/.test(args)
        || /^'profile_setup_completed', \{ interestCount: saved\.interests\.length \}$/.test(args);
      expect(ok, `${file}: track(${args})`).toBe(true);
    }
  });
  it('profile code does not log, and does not put values into URLs', () => {
    for (const file of files) {
      const source = readFileSync(file, 'utf8');
      expect(source, file).not.toMatch(/console\.(log|info|warn|error|debug)/);
      expect(source, file).not.toMatch(/URLSearchParams|location\.(search|hash)|navigate\(`|\?\w+=/);
      expect(source, file).not.toMatch(/localStorage|sessionStorage/); // 인적사항 코드는 브라우저 저장소를 쓰지 않는다(데모 DB 는 mock/store.ts 가 맡는다)
    }
  });
});
