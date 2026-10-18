import { describe, expect, it } from 'vitest';
import { interviewBank, seedCandidates } from '@/mock/fixtures';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

describe('sample interview and evidence consistency', () => {
  it.each(['T', 'A', 'R'])('returns the selected %s field question and options together', (field) => {
    resetDb();
    const turn = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field }, true).data;
    expect(turn).toMatchObject({ field, question: interviewBank[field].question, options: interviewBank[field].options });
  });

  it('offers goals for T, actions for A, and outcomes for R', () => {
    expect(interviewBank.T.options).toEqual(['사용자가 다시 로그인하지 않도록 해야 했어요', '반복되는 오류를 해결해야 했어요', '정해진 기한 안에 기능을 완성해야 했어요']);
    expect(interviewBank.A.question).toBe('문제를 해결하기 위해 어떤 방법을 썼나요?');
    expect(interviewBank.R.question).toBe('작업을 마친 뒤 무엇이 달라졌나요?');
    expect(interviewBank.R.options).toEqual(['오류가 줄었어요', '사용자가 작업을 더 빠르게 마칠 수 있게 됐어요', '아직 결과를 확인하지 못했어요']);
  });

  it.each(['r_board', 'r_auth'])('offers recall goals without unsupported global counts for %s', (repoId) => {
    resetDb();
    const recall = handle('GET', `/repos/${repoId}/recall`, new URLSearchParams(), {}, true).data as { note: string; dirs: { path: string; question: string; options: string[] }[] };
    const api = recall.dirs.find((dir) => dir.path === '/src/api/')!;
    expect(api.question).toContain('무엇을 해내려던');
    expect(api.options).toEqual(['로그인이 정상적으로 동작하게 하려 했어요', '기존 기능을 완성하려 했어요', '반복되는 오류를 해결하려 했어요']);
    expect(recall.note).toBe('어떤 파일을 만졌는지 살펴보며 작업을 떠올려 보세요. 답한 내용은 그대로 정성 카드의 근거가 됩니다.');
  });

  it.each(seedCandidates)('$id counts agree with explicit sample evidence', (candidate) => {
    const review = candidate.reason.match(/리뷰 코멘트 (\d+)건/);
    const files = candidate.reason.match(/(?:변경 파일 |문서 )(\d+)(?:개|파일)/);
    const commits = candidate.reason.match(/(\d+)커밋|커밋 (\d+)건/);
    const titleCommits = candidate.title.match(/(\d+)커밋/);
    const days = candidate.reason.match(/(\d+)일간/);
    if (review) expect(candidate.meta.reviewComments).toBe(Number(review[1]));
    if (files) expect(candidate.meta.files).toBe(Number(files[1]));
    if (commits) expect(candidate.meta.commits).toBe(Number(commits[1] ?? commits[2]));
    if (titleCommits) expect(candidate.meta.commits).toBe(Number(titleCommits[1]));
    if (days) expect(candidate.meta.days).toBe(Number(days[1]));
  });
});
