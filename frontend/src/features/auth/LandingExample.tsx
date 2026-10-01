import { ArrowRight, Github, GitBranch, GitCommitHorizontal } from 'lucide-react';
import { StarKey } from '@/components/ui';
import type { StarField } from '@/api/schemas';

// Illustrative content only. It is never submitted as user activity or an analysis result.
const commits = [
  { sha: 'a3f21c9', title: '로그인 세션 검증 추가', file: 'auth/session.ts' },
  { sha: '9c02de1', title: '만료된 토큰 재발급 처리', file: 'api/auth.ts' },
  { sha: '7f3e9a2', title: '새로고침 로그인 유지 테스트', file: 'auth/session.test.ts' },
];
const fields: { key: StarField; title: string; text: string | null }[] = [
  { key: 'S', title: '상황', text: '페이지를 새로고침하면 로그인이 풀려 사용자가 다시 로그인해야 했다.' },
  { key: 'T', title: '과제', text: '새로고침 후에도 인증 상태가 유지되도록 로그인 흐름을 개선해야 했다.' },
  { key: 'A', title: '행동', text: '세션 검증과 토큰 재발급을 구현하고 로그인 유지 테스트를 추가했다.' },
  { key: 'R', title: '결과', text: null },
];

export function LandingExample() {
  return <div className="landing-example">
    <figure className="github-snapshot" aria-label="GitHub 기록 예시">
      <figcaption className="example-heading"><Github size={20} /><strong>GitHub 기록</strong><span>예시</span></figcaption>
      <div className="github-snapshot__repo"><span>gitory / web</span><span><GitBranch size={13} /> main</span></div>
      <div className="github-snapshot__title">로그인 유지 개선 <span>#42</span></div>
      <ol className="github-snapshot__commits">{commits.map((commit) => <li key={commit.sha}>
        <GitCommitHorizontal size={18} />
        <div><strong>{commit.title}</strong><span>{commit.file}</span></div>
        <code>{commit.sha}</code>
      </li>)}</ol>
      <div className="github-snapshot__diff"><span>+ 세션 확인</span><span>+ 토큰 재발급</span><span>+ 로그인 유지 테스트</span></div>
    </figure>
    <div className="landing-example__connector" aria-hidden="true"><ArrowRight size={18} /></div>
    <figure className="landing-card" aria-label="경험 카드 예시">
      <figcaption className="example-heading"><strong>경험 카드</strong><span>기록에서 정리</span></figcaption>
      <h2>로그인 유지 문제 개선</h2>
      <div className="landing-card__fields">{fields.map((field) => <div className={`landing-card__field ${field.text ? '' : 'landing-card__field--empty'}`} key={field.key}>
        <StarKey field={field.key} dropped={!field.text} small />
        <div><strong>{field.title}</strong><p>{field.text ?? '실제로 무엇이 달라졌는지 답변해 채울 수 있어요.'}</p></div>
      </div>)}</div>
      <span className="landing-card__evidence">연결된 근거 · 커밋 3개</span>
    </figure>
  </div>;
}
