import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PROFILE_LIMITS } from '@/api/profile';
import { ProfilePage } from '@/features/profile/ProfilePage';
import { resetDb } from '@/mock/store';
import { putProfile } from '@/mock/profile';
import { connectMockServer, jsonError, mountApp, page, sampleInput } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
// Radix 메뉴는 jsdom 에서 열리는 데만 10초 넘게 걸린다. 값·접근성 이름만 같은 네이티브 select 로 바꿔 폼 로직을 시험한다.
// Radix 래퍼 자체의 조작은 e2e(select-interaction · profile)가 실제 브라우저에서 확인한다.
vi.mock('@/components/ui/Select', () => ({
  Select: ({ value, onChange, options, label, invalid, describedBy }: { value: string; onChange: (value: string) => void; options: { value: string; label: string; disabled?: boolean }[]; label: string; invalid?: boolean; describedBy?: string }) => (
    <select aria-label={label} aria-invalid={invalid || undefined} aria-describedby={describedBy} value={value} onChange={(e) => onChange(e.target.value)}>
      {options.map((option) => <option key={option.value} value={option.value} disabled={option.disabled}>{option.label}</option>)}
    </select>
  ),
}));

// 칸 이름 = "<영역> <번호> <화면에 보이는 이름>" (선택 칸은 이름 뒤에 "(선택)")
const edu = { level: (n = 1) => `학력 ${n} 학력 구분`, school: (n = 1) => `학력 ${n} 학교 (선택)`, major: (n = 1) => `학력 ${n} 전공 (선택)`, status: (n = 1) => `학력 ${n} 상태`,
  start: (n = 1) => `학력 ${n} 입학 월 (선택)`, end: (n = 1) => `학력 ${n} 졸업(예정) 월 (선택)`, gpa: (n = 1) => `학력 ${n} 학점 (선택)`, gpaMax: (n = 1) => `학력 ${n} 학점 만점 (선택)` };
const car = { company: (n = 1) => `경력 ${n} 회사`, type: (n = 1) => `경력 ${n} 고용 형태`, role: (n = 1) => `경력 ${n} 직무 (선택)`, start: (n = 1) => `경력 ${n} 입사 월`, end: (n = 1) => `경력 ${n} 퇴사 월`, current: (n = 1) => `경력 ${n} 재직 중` };
const lang = { test: (n = 1) => `어학 ${n} 시험`, testName: (n = 1) => `어학 ${n} 시험 이름`, score: (n = 1) => `어학 ${n} 점수 또는 등급`, month: (n = 1) => `어학 ${n} 취득 월 (선택)` };
const cert = { name: (n = 1) => `자격증 ${n} 이름`, issuer: (n = 1) => `자격증 ${n} 발급 기관 (선택)`, month: (n = 1) => `자격증 ${n} 취득 월 (선택)` };

const routes = () => [page(<ProfilePage />, '/settings/profile'), page(<p>마이페이지 화면</p>, '/settings'), page(<p>다른 화면</p>, '/elsewhere')];
async function open() {
  const app = mountApp(routes(), '/settings/profile');
  await screen.findByRole('button', { name: '학력 추가' });
  return app;
}
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const choose = (label: string, value: string) => fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value } });
const pick = (label: string) => fireEvent.click(screen.getByRole('checkbox', { name: label }));
const status = () => screen.getAllByRole('status').map((el) => el.textContent).join('|');
const save = async () => { click(/^(다시 )?저장$/); await act(async () => { await Promise.resolve(); }); };
const puts = (server: ReturnType<typeof connectMockServer>) => server.profileCalls().filter((c) => c.method === 'PUT');
/** 학력 한 줄을 모두 채운다 */
function fillEducation(n = 1, patch: Partial<Record<'school' | 'major' | 'start' | 'end', string>> = {}) {
  choose(edu.level(n), 'BACHELOR');
  type(edu.school(n), patch.school ?? '샘플대학교');
  type(edu.major(n), patch.major ?? '컴퓨터공학');
  choose(edu.status(n), 'GRADUATED');
  type(edu.start(n), patch.start ?? '2018-03');
  type(edu.end(n), patch.end ?? '2022-02');
}
/** 필수 칸만 채운 학력 한 줄 */
function fillEducationRequired(n = 1) {
  choose(edu.level(n), 'BACHELOR');
  type(edu.school(n), '샘플대학교');
  choose(edu.status(n), 'ENROLLED');
}
/** 필수 칸만 채운 경력 한 줄(재직 중) */
function fillCareerRequired(n = 1) {
  type(car.company(n), '샘플컴퍼니'); choose(car.type(n), 'FULL_TIME'); type(car.start(n), '2022-03');
  fireEvent.click(screen.getByLabelText(car.current(n)));
}

beforeEach(() => { resetDb(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.clearAllMocks(); });

describe('profile form — rows', () => {
  it('starts empty, adds a row with focus on its first field, and removes it with focus back on the add button', async () => {
    connectMockServer();
    await open();
    expect(screen.getByText('아직 입력한 학력이 없어요.')).toBeInTheDocument();
    click('학력 추가');
    expect(await screen.findByRole('combobox', { name: edu.level() })).toHaveFocus();
    expect(screen.queryByText('아직 입력한 학력이 없어요.')).not.toBeInTheDocument();
    click('학력 추가');
    expect(screen.getByLabelText(edu.school(2))).toBeInTheDocument();
    click('학력 1 삭제');
    expect(screen.queryByLabelText(edu.school(2))).not.toBeInTheDocument();
    expect(screen.getByLabelText(edu.school())).toHaveValue('');
    await waitFor(() => expect(screen.getByRole('button', { name: '학력 추가' })).toHaveFocus());
  });

  it('removing the first of two rows keeps the second row\'s text', async () => {
    connectMockServer();
    await open();
    click('경력 추가'); click('경력 추가');
    type(car.company(1), '첫째 회사'); type(car.company(2), '둘째 회사');
    click('경력 1 삭제');
    expect(screen.getByLabelText(car.company(1))).toHaveValue('둘째 회사');
  });

  it.each([
    ['학력', 5, '학력 5'], ['경력', 10, '경력 10'], ['어학', 10, '어학 10'], ['자격증', 20, '자격증 20'],
  ] as const)('%s stops at %i rows and says so', async (noun, max, lastTitle) => {
    connectMockServer();
    await open();
    for (let i = 0; i < max; i++) click(`${noun} 추가`);
    expect(screen.getByRole('heading', { name: lastTitle, level: 3 })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: `${noun} ${max + 1}`, level: 3 })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: `${noun} 추가` })).toBeDisabled();
    const section = screen.getByRole('region', { name: noun });
    expect(within(section).getAllByText(`최대 ${max}개`).length).toBeGreaterThan(0);
    expect(within(section).getByText(`최대 ${max}개까지 입력할 수 있어요`)).toBeInTheDocument();
    click(`${lastTitle} 삭제`);
    expect(screen.getByRole('button', { name: `${noun} 추가` })).toBeEnabled();
  }, 20000);

  it('every control is named "<section> <row> <its visible label>", so voice control can say what it sees (WCAG 2.5.3)', async () => {
    connectMockServer();
    await open();
    for (const name of ['학력 추가', '경력 추가', '어학 추가', '자격증 추가']) click(name);
    choose(lang.test(), 'OTHER'); // 시험 이름 칸까지 나오게
    const fields = [...document.querySelectorAll('label.field')];
    expect(fields.length).toBe(8 + 5 + 4 + 3);
    for (const field of fields) {
      const visible = field.querySelector('.field__label')!.textContent!.trim();
      const control = field.querySelector('input, select')!;
      expect(control.getAttribute('aria-label'), `visible label "${visible}"`).toMatch(new RegExp(`^(학력|경력|어학|자격증) 1 ${visible.replace(/[()]/g, '\\$&')}$`));
    }
    expect(screen.getByLabelText(car.current())).toBeInTheDocument();
    for (const name of ['학력 1 삭제', '경력 1 삭제', '어학 1 삭제', '자격증 1 삭제']) expect(screen.getByRole('button', { name })).toBeInTheDocument();
  });

  it('asks for a test name only when the test is "기타"', async () => {
    connectMockServer();
    await open();
    click('어학 추가');
    expect(screen.queryByLabelText(lang.testName())).not.toBeInTheDocument();
    choose(lang.test(), 'OTHER');
    expect(screen.getByLabelText(lang.testName())).toBeInTheDocument();
    choose(lang.test(), 'TOEIC');
    expect(screen.queryByLabelText(lang.testName())).not.toBeInTheDocument();
  });

  it('keeps personal text out of the DOM ids it generates', async () => {
    connectMockServer();
    await open();
    click('학력 추가');
    type(edu.school(), '비밀학교-ZXQ');
    expect([...document.querySelectorAll('[id]')].some((el) => el.id.includes('ZXQ'))).toBe(false);
  });
});

describe('profile form — what the screen tells the user', () => {
  it('says what the data is for, including that the cover-letter AI may read it, without claiming a single use (#516, #531, #552)', async () => {
    connectMockServer();
    await open();
    const note = screen.getByText('이렇게 쓰여요').parentElement!;
    expect(note).toHaveTextContent('공고 매칭');
    expect(note).toHaveTextContent('자소서를 AI 로 쓸 때도 이 정보를 읽을 수 있어요');
    expect(note).toHaveTextContent('경험 카드 문장은 이 정보로 만들지 않아요');
    // BE 가 매칭과 자소서 AI 가 읽도록 열어 두었다 — "매칭에만 쓴다"로 부인하지 않는다
    expect(screen.getByRole('main').textContent).not.toMatch(/데만|만 쓰|만 사용|오직|뿐/);
  });

  it('shows Korean words for every enum, never the English names', async () => {
    connectMockServer();
    await open();
    for (const name of ['학력 추가', '경력 추가', '어학 추가']) click(name);
    const labels = (name: string) => within(screen.getByRole('combobox', { name })).getAllByRole('option').map((o) => o.textContent);
    expect(labels(edu.level())).toEqual(['선택', '고등학교', '전문대학', '학사', '석사', '박사']);
    expect(labels(edu.status())).toEqual(['선택', '재학', '휴학', '졸업 예정', '졸업', '중퇴']);
    expect(labels(edu.gpaMax())).toEqual(['선택 안 함', '4.0', '4.3', '4.5', '100']);
    expect(labels(car.type())).toEqual(['선택', '정규직', '계약직', '인턴', '파트타임', '프리랜서']);
    expect(labels(lang.test())).toEqual(['선택', '토익', '토익 스피킹', '오픽', '토플', '아이엘츠', '텝스', 'JLPT', 'JPT', 'HSK', '기타']);
    expect(screen.getByRole('main').textContent).not.toMatch(/ON_LEAVE|GRADUATION_EXPECTED|DROPPED_OUT|HIGH_SCHOOL|FULL_TIME|PART_TIME|TOEIC_SPEAKING/);
  });

  it('the score box gives an example that fits the chosen test', async () => {
    connectMockServer();
    await open();
    click('어학 추가');
    expect(screen.getByLabelText(lang.score())).toHaveAttribute('placeholder', '점수나 등급');
    choose(lang.test(), 'OPIC');
    expect(screen.getByLabelText(lang.score())).toHaveAttribute('placeholder', '예) IH');
    choose(lang.test(), 'JLPT');
    expect(screen.getByLabelText(lang.score())).toHaveAttribute('placeholder', '예) N1');
  });

  it('does not limit months to a range BE does not check (no min or max on the month boxes)', async () => {
    connectMockServer();
    await open();
    click('학력 추가'); click('경력 추가');
    for (const label of [edu.start(), edu.end(), car.start(), car.end()]) {
      expect(screen.getByLabelText(label)).not.toHaveAttribute('min');
      expect(screen.getByLabelText(label)).not.toHaveAttribute('max');
    }
  });
});

describe('profile form — validation (same rules as BE, caught before sending)', () => {
  it('does not shout while typing; on save it lists what is wrong, focuses the first field and sends nothing', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드');
    click('학력 추가');
    expect(screen.queryByText('학교 이름을 입력해 주세요')).not.toBeInTheDocument();
    server.fetcher.mockClear();
    await save();
    for (const message of ['학력 구분을 선택해 주세요', '상태를 선택해 주세요']) expect(screen.getByText(message)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('입력을 확인해 주세요');
    expect(screen.getByRole('alert')).toHaveTextContent('2곳');
    await waitFor(() => expect(screen.getByRole('combobox', { name: edu.level() })).toHaveFocus());
    expect(screen.getByLabelText(edu.school())).not.toHaveAttribute('aria-invalid'); // 학교 이름은 비워도 된다 — 처음 입력 창이 받지 않는다
    expect(screen.getByRole('combobox', { name: edu.status() })).toHaveAccessibleDescription('상태를 선택해 주세요');
    expect(puts(server)).toEqual([]);
  });

  it('needs one to three interests: says so on save, and greys out the rest once three are chosen', async () => {
    const server = connectMockServer();
    await open();
    click('학력 추가'); fillEducationRequired();
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

  it('never complains about an optional box left empty', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드');
    click('학력 추가'); fillEducationRequired();
    click('경력 추가'); fillCareerRequired();
    click('어학 추가'); choose(lang.test(), 'TOEIC'); type(lang.score(), '900');
    click('자격증 추가'); type(cert.name(), '정보처리기사');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(puts(server)[0].body).toEqual({
      educations: [{ level: 'BACHELOR', school: '샘플대학교', major: null, status: 'ENROLLED', startMonth: null, endMonth: null, gpa: null, gpaMax: null }],
      careers: [{ company: '샘플컴퍼니', role: null, type: 'FULL_TIME', startMonth: '2022-03', endMonth: null }],
      languages: [{ test: 'TOEIC', testName: null, score: '900', acquiredMonth: null }],
      certifications: [{ name: '정보처리기사', issuer: null, acquiredMonth: null }],
      interests: ['BACKEND'],
    });
  });

  it('ties the select errors to their selects the way every other field is tied', async () => {
    connectMockServer();
    await open();
    pick('백엔드');
    click('학력 추가');
    await save();
    for (const [name, message] of [[edu.level(), '학력 구분을 선택해 주세요'], [edu.status(), '상태를 선택해 주세요']] as const) {
      const select = screen.getByRole('combobox', { name });
      expect(select).toHaveAttribute('aria-invalid', 'true');
      expect(select).toHaveAccessibleDescription(message);
    }
    choose(edu.status(), 'GRADUATED');
    const fixed = screen.getByRole('combobox', { name: edu.status() });
    expect(fixed).not.toHaveAttribute('aria-invalid');
    expect(fixed).not.toHaveAttribute('aria-describedby');
  });

  it('removes a message the moment its field is fixed', async () => {
    connectMockServer();
    await open();
    pick('백엔드');
    click('학력 추가');
    await save();
    type(edu.school(), '샘플대학교');
    expect(screen.queryByText('학교 이름을 입력해 주세요')).not.toBeInTheDocument();
    expect(screen.getByText('상태를 선택해 주세요')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('2곳');
  });

  it('GPA goes with its maximum: asks for the missing half, and keeps the score between zero and the maximum', async () => {
    connectMockServer();
    await open();
    pick('백엔드');
    click('학력 추가'); fillEducationRequired();
    type(edu.gpa(), '3.8');
    await save();
    expect(screen.getByText('학점을 적었다면 만점도 골라 주세요')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: edu.gpaMax() })).toHaveAccessibleDescription('학점을 적었다면 만점도 골라 주세요');
    type(edu.gpa(), '');
    choose(edu.gpaMax(), '4.5');
    await save();
    expect(screen.getByText('만점을 골랐다면 학점도 입력해 주세요')).toBeInTheDocument();
    type(edu.gpa(), '4.6');
    await save();
    expect(screen.getByText('학점은 0 부터 고른 만점까지 입력할 수 있어요')).toBeInTheDocument();
    type(edu.gpa(), '3,8');
    await save();
    expect(screen.getByText('학점은 3.8 처럼 숫자로 입력해 주세요')).toBeInTheDocument();
    type(edu.gpa(), '3.85');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    choose(edu.gpaMax(), ''); // 만점만 도로 비우면 학점이 남아 다시 짝이 안 맞는다
    expect(screen.getByLabelText(edu.gpa())).toHaveValue('3.85');
    await save();
    expect(screen.getByText('학점을 적었다면 만점도 골라 주세요')).toBeInTheDocument();
  });

  it('explains an end month before the start month, for school and for a job', async () => {
    connectMockServer();
    await open();
    click('학력 추가');
    fillEducation(1, { start: '2022-03', end: '2022-02' });
    click('경력 추가');
    fillCareerRequired();
    fireEvent.click(screen.getByLabelText(car.current())); // 재직 중 해제
    type(car.end(), '2022-01');
    await save();
    expect(screen.getByText('졸업(예정) 월은 입학 월과 같거나 뒤여야 해요')).toBeInTheDocument();
    expect(screen.getByText('퇴사 월은 입사 월과 같거나 뒤여야 해요')).toBeInTheDocument();
  });

  it('a graduated school needs no end month and no other optional box', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드');
    click('학력 추가'); fillEducationRequired();
    choose(edu.status(), 'GRADUATED');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(puts(server)).toHaveLength(1);
  });

  it('flags a career with neither an end month nor "still working" — blank must not be saved as still working', async () => {
    const server = connectMockServer();
    await open();
    click('경력 추가');
    type(car.company(), '샘플컴퍼니'); choose(car.type(), 'INTERN'); type(car.start(), '2023-01');
    await save();
    expect(screen.getByText('퇴사 월을 입력하거나 재직 중을 선택해 주세요')).toBeInTheDocument();
    expect(puts(server)).toEqual([]);
  });

  it('asks for a test name once "기타" is chosen, and a job needs its employment type and start month', async () => {
    connectMockServer();
    await open();
    click('어학 추가'); click('경력 추가');
    choose(lang.test(), 'OTHER'); type(lang.score(), '합격');
    await save();
    expect(screen.getByText('시험 이름을 입력해 주세요')).toBeInTheDocument();
    for (const message of ['회사 이름을 입력해 주세요', '고용 형태를 선택해 주세요', '입사 월을 입력해 주세요']) expect(screen.getByText(message)).toBeInTheDocument();
  });

  it('reports a section-wide problem under the section, and an optional issuer is not one', async () => {
    connectMockServer();
    await open();
    click('자격증 추가');
    await save();
    expect(screen.getByText('자격증 이름을 입력해 주세요')).toBeInTheDocument();
    expect(screen.queryByText('발급 기관을 입력해 주세요')).not.toBeInTheDocument();
  });

  it('too-long text says the limit of that box (50, score 20)', async () => {
    connectMockServer();
    await open();
    click('어학 추가'); click('학력 추가');
    choose(lang.test(), 'TOEIC');
    // maxLength 가 입력은 막지만 붙여넣기·자동 채움은 값을 그대로 넣을 수 있다
    fireEvent.change(screen.getByLabelText(lang.score()), { target: { value: '9'.repeat(PROFILE_LIMITS.SCORE + 1) } });
    fireEvent.change(screen.getByLabelText(edu.school()), { target: { value: '가'.repeat(PROFILE_LIMITS.TEXT + 1) } });
    await save();
    expect(screen.getByText('20자 이하로 입력해 주세요')).toBeInTheDocument();
    expect(screen.getByText('50자 이하로 입력해 주세요')).toBeInTheDocument();
  });
});

describe('profile form — saving', () => {
  it('sends the normalized document and then reads as saved and clean', async () => {
    const server = connectMockServer();
    await open();
    expect(status()).toContain('고친 곳이 없어요');
    pick('백엔드'); pick('인프라, DevOps');
    click('학력 추가'); fillEducation(1, { school: '  샘플대학교  ' });
    type(edu.gpa(), '3.8'); choose(edu.gpaMax(), '4.5');
    expect(status()).toContain('저장하지 않은 변경이 있어요');
    click('경력 추가');
    type(car.company(), '샘플컴퍼니'); choose(car.type(), 'FULL_TIME'); type(car.role(), '백엔드 개발'); type(car.start(), '2022-03');
    fireEvent.click(screen.getByLabelText(car.current()));
    click('어학 추가');
    choose(lang.test(), 'TOEIC'); type(lang.score(), '900'); type(lang.month(), '2021-08');
    click('자격증 추가');
    type(cert.name(), '정보처리기사'); type(cert.issuer(), '   '); type(cert.month(), '2021-11');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(puts(server)[0].body).toEqual(sampleInput());
    expect(screen.getByLabelText(edu.school())).toHaveValue('샘플대학교'); // 서버가 돌려준 정리된 값
    expect(screen.getByRole('button', { name: '저장' })).toHaveAttribute('aria-disabled', 'true');
    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toHaveFocus());
    // 다시 눌러도 요청을 더 보내지 않는다
    click('저장');
    expect(puts(server)).toHaveLength(1);
  });

  it('always sends all four lists and the interests, even when only one list has rows (a missing list would be a 400)', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드');
    click('자격증 추가'); type(cert.name(), '정보처리기사');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    const body = puts(server)[0].body as Record<string, unknown>;
    expect(Object.keys(body)).toEqual(['educations', 'careers', 'languages', 'certifications', 'interests']);
    expect([body.educations, body.careers, body.languages]).toEqual([[], [], []]);
    expect(body.interests).toEqual(['BACKEND']);
    expect(body.certifications).toEqual([{ name: '정보처리기사', issuer: null, acquiredMonth: null }]);
  });

  it('sends the test name only for "기타"', async () => {
    const server = connectMockServer();
    await open();
    pick('백엔드');
    click('어학 추가'); click('어학 추가');
    choose(lang.test(1), 'TOEIC'); type(lang.score(1), '900');
    choose(lang.test(2), 'OTHER'); type(lang.testName(2), ' 토픽 '); type(lang.score(2), '6급');
    choose(lang.test(1), 'OTHER'); type(lang.testName(1), '숨길 글'); choose(lang.test(1), 'TOEIC'); // 이름을 쓰다 다른 시험으로 바꾼 경우
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect((puts(server)[0].body as { languages: unknown[] }).languages).toEqual([
      { test: 'TOEIC', testName: null, score: '900', acquiredMonth: null },
      { test: 'OTHER', testName: '토픽', score: '6급', acquiredMonth: null },
    ]);
  });

  it('opens a saved profile with its rows filled in', async () => {
    connectMockServer();
    putProfile(sampleInput());
    await open();
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: '인프라, DevOps' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: '모바일' })).not.toBeChecked();
    expect(screen.getByLabelText(edu.school())).toHaveValue('샘플대학교');
    expect(screen.getByLabelText(edu.end())).toHaveValue('2022-02');
    expect(screen.getByLabelText(edu.gpa())).toHaveValue('3.8');
    expect(screen.getByRole('combobox', { name: edu.gpaMax() })).toHaveValue('4.5');
    expect(screen.getByRole('combobox', { name: edu.level() })).toHaveValue('BACHELOR');
    expect(screen.getByRole('combobox', { name: edu.status() })).toHaveValue('GRADUATED');
    expect(screen.getByLabelText(car.current())).toBeChecked();
    expect(screen.getByLabelText(car.end())).toBeDisabled();
    expect(screen.getByRole('combobox', { name: car.type() })).toHaveValue('FULL_TIME');
    expect(screen.getByRole('combobox', { name: lang.test() })).toHaveValue('TOEIC');
    expect(screen.getByLabelText(cert.issuer())).toHaveValue('');
    expect(status()).toContain('고친 곳이 없어요');
  });

  it('opens an empty, never-saved profile with nothing filled in', async () => {
    connectMockServer();
    await open();
    expect(screen.getByText('아직 입력한 학력이 없어요.')).toBeInTheDocument();
    expect(status()).toContain('고친 곳이 없어요');
  });

  it('still working disables the end month and unchecking it asks for one again', async () => {
    connectMockServer();
    putProfile(sampleInput());
    await open();
    fireEvent.click(screen.getByLabelText(car.current()));
    expect(screen.getByLabelText(car.end())).toBeEnabled();
    await save();
    expect(screen.getByText('퇴사 월을 입력하거나 재직 중을 선택해 주세요')).toBeInTheDocument();
    type(car.end(), '2024-05');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
  });

  it('clearing every row and saving sends four empty lists (the interests stay), with a warning first', async () => {
    const server = connectMockServer();
    putProfile(sampleInput());
    await open();
    for (const name of ['학력 1 삭제', '경력 1 삭제', '어학 1 삭제', '자격증 1 삭제']) click(name);
    expect(status()).toContain('모두 지운 상태예요. 저장하면 입력한 정보가 삭제돼요');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    expect(puts(server)[0].body).toEqual({ educations: [], careers: [], languages: [], certifications: [], interests: ['BACKEND', 'DEVOPS'] });
  });
});

describe('profile form — failure keeps the input', () => {
  it('keeps everything typed after a server error and saves on retry', async () => {
    let failures = 1;
    const server = connectMockServer((call) => (call.method === 'PUT' && failures-- > 0 ? jsonError(503, 'INTERNAL_ERROR') : undefined));
    await open();
    pick('백엔드');
    click('자격증 추가');
    type(cert.name(), '정보처리기사'); type(cert.month(), '2021-11');
    await save();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버에서 처리하지 못했어요');
    expect(alert).toHaveTextContent('입력한 내용은 이 화면에 남아 있어요');
    expect(screen.getByLabelText(cert.name())).toHaveValue('정보처리기사');
    expect(screen.getByLabelText(cert.month())).toHaveValue('2021-11');
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
    click('어학 추가');
    choose(lang.test(), 'TOEIC'); type(lang.score(), '900');
    await save();
    await screen.findByRole('alert');
    expect(status()).not.toContain('저장했어요');
    expect(screen.getByLabelText(lang.score())).toHaveValue('900');
  });

  it('puts server per-field errors (path + code) on their boxes, and clears one when that box is edited', async () => {
    // BE 가 error.details.fields 로 칸별 오류를 주기로 하면 이렇게 보인다(지금 #552 는 문장 하나뿐)
    connectMockServer((call) => (call.method === 'PUT'
      ? jsonError(400, 'INVALID_REQUEST', { fields: [{ path: 'careers[0].role', code: 'TOO_LONG' }, { path: 'careers[0].startMonth', code: 'INVALID_FORMAT' }] })
      : undefined));
    await open();
    pick('백엔드');
    click('경력 추가');
    fillCareerRequired(); type(car.role(), '개발');
    await save();
    await screen.findByText('50자 이하로 입력해 주세요');
    expect(screen.getByText('2024-03 처럼 연-월 형식으로 입력해 주세요')).toBeInTheDocument();
    expect(screen.getByLabelText(car.role())).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('2곳');
    await waitFor(() => expect(screen.getByLabelText(car.role())).toHaveFocus());
    type(car.role(), '백엔드 개발');
    expect(screen.queryByText('50자 이하로 입력해 주세요')).not.toBeInTheDocument();
    expect(screen.getByText('2024-03 처럼 연-월 형식으로 입력해 주세요')).toBeInTheDocument();
  });

  it('a server error about a box that is not on screen is not counted or shown — only the general message appears', async () => {
    connectMockServer((call) => (call.method === 'PUT'
      ? jsonError(400, 'INVALID_REQUEST', { fields: [{ path: 'educations[0].degree', code: 'REQUIRED' }, { path: 'educations[7].school', code: 'REQUIRED' }] })
      : undefined));
    await open();
    pick('백엔드');
    click('자격증 추가'); type(cert.name(), '자격증');
    await save();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버가 입력을 받지 못했어요');
    expect(screen.queryByText(/\d+곳을 고치면/)).not.toBeInTheDocument();
  });

  it('falls back to a general message for a 400 that is only a sentence (what #552 sends), and never shows that sentence', async () => {
    const sentence = '학력 1번째 학교 이름이 50자를 넘어요';
    connectMockServer((call) => (call.method === 'PUT'
      ? new Response(JSON.stringify({ data: null, error: { code: 'INVALID_REQUEST', message: sentence } }), { status: 400 })
      : undefined));
    await open();
    pick('백엔드');
    click('자격증 추가');
    type(cert.name(), '자격증');
    await save();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버가 입력을 받지 못했어요');
    expect(alert).toHaveTextContent('입력한 내용은 이 화면에 그대로 남아 있어요');
    expect(document.body.textContent).not.toContain('50자를 넘어요');
    expect(document.body.textContent).not.toContain(sentence);
    expect(screen.getByLabelText(cert.name())).toHaveValue('자격증');
    expect(screen.queryByRole('button', { name: '저장' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 저장' })).toBeEnabled();
  });
});

describe('profile form — leaving with unsaved changes', () => {
  it('asks before leaving a dirty form; staying keeps the input, leaving drops it', async () => {
    connectMockServer();
    const { router } = await open();
    click('자격증 추가');
    type(cert.name(), '정보처리기사');
    await act(async () => { void router.navigate('/elsewhere'); });
    const dialog = await screen.findByRole('dialog', { name: '아직 저장되지 않은 내용이 있어요' });
    fireEvent.click(within(dialog).getByRole('button', { name: '계속 작성' }));
    expect(router.state.location.pathname).toBe('/settings/profile');
    expect(screen.getByLabelText(cert.name())).toHaveValue('정보처리기사');
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하지 않고 나가기' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/elsewhere'));
  });

  it('"save and leave" saves first and then leaves', async () => {
    const server = connectMockServer();
    const { router } = await open();
    pick('백엔드');
    click('자격증 추가');
    type(cert.name(), '정보처리기사'); type(cert.month(), '2021-11');
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하고 이동' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/elsewhere'));
    expect(puts(server)).toHaveLength(1);
  });

  it('"save and leave" with invalid input stays and explains why (not "check your connection")', async () => {
    const server = connectMockServer();
    const { router } = await open();
    click('자격증 추가'); // 이름 없음
    type(cert.issuer(), '한국산업인력공단');
    await act(async () => { void router.navigate('/elsewhere'); });
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '저장하고 이동' }));
    const why = await screen.findByText(/입력을 확인해야 저장할 수 있어요/);
    expect(why).toBeInTheDocument();
    expect(why.textContent).not.toMatch(/빨간|붉은|초록|파란|색/); // 색을 못 보는 사람에게도 통하는 안내
    expect(screen.queryByText(/연결을 확인한 뒤/)).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/settings/profile');
    expect(puts(server)).toEqual([]);
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
    pick('백엔드');
    click('자격증 추가');
    type(cert.name(), '정보처리기사'); type(cert.month(), '2021-11');
    await save();
    await waitFor(() => expect(status()).toContain('저장했어요'));
    await act(async () => { await router.navigate('/elsewhere'); });
    expect(router.state.location.pathname).toBe('/elsewhere');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
