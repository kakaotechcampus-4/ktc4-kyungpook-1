import { Reveal, ThemedShot } from './Shot';

const CLAIMS = [
  {
    title: '레포를 고르면, 카드는 끝납니다',
    sub: '커밋·PR·리뷰를 읽어 상황·과제·행동·결과를 채우고, 칸이 찬 카드는 스스로 확정해요. 빈 칸은 지어내지 않고 먼저 물어요.',
    shot: { name: 'card-star', width: 1150, height: 655, alt: '확정된 경험 카드. 상황, 과제, 행동, 결과 네 칸마다 근거 커밋과 내 답변이 붙어 있어요.' },
  },
  {
    title: '공고를 넣으면, 맞는지 보여 줍니다',
    sub: '필수·우대 조건마다 내 카드가 맞는지 표시하고, 모자란 조건은 레포에서 근거를 찾아 제안해요.',
    shot: { name: 'match', width: 1150, height: 700, alt: '공고 상세 화면. 자격 요건과 우대 사항마다 맞는 경험 카드가 표시되고, 오른쪽에 맞는 조건 수가 보여요.' },
  },
  {
    title: '문항이 없어도, 자소서는 나옵니다',
    sub: '자주 나오는 문항으로 먼저 쓰고, 빈 칸 질문과 첨삭으로 다듬어요. 근거 없는 문장은 쓰지 않아요.',
    shot: { name: 'letter', width: 1120, height: 520, alt: '자소서 초안 화면. 왼쪽에 문항별 글과 근거 표시가 있고, 오른쪽 대화창이 빈 칸을 물어봐요.' },
  },
];

export function Claims() {
  return (
    <section id="features" className="lp-claims lp-wrap" aria-label="기능">
      {CLAIMS.map((claim, index) => (
        <Reveal key={claim.title} className={`lp-claim ${index % 2 ? 'lp-claim--flip' : ''}`}>
          <div className="lp-claim__text">
            <h2>{claim.title}</h2>
            <p>{claim.sub}</p>
          </div>
          <figure className="lp-claim__shot"><ThemedShot {...claim.shot} /></figure>
        </Reveal>
      ))}
    </section>
  );
}
