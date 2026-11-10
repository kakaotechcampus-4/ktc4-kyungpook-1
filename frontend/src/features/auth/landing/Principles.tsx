import { Reveal } from './Shot';

const ROWS = [
  { title: '근거 없는 문장은 쓰지 않아요', text: '커밋·PR에 없는 일은 자소서에 넣지 않고, 문장마다 근거를 붙여요.' },
  { title: '빈 칸은 먼저 물어요', text: '모르는 결과·수치는 지어내지 않고 한 가지씩 물어서 채워요.' },
  { title: '내 커밋만 읽어요', text: '기본 브랜치의 내 커밋·PR·리뷰만 읽고, GitHub 토큰은 암호화해 보관해요.' },
  { title: '비공개 저장소는 읽지 않아요', text: '공개 저장소 범위만 허용받아요.' },
  { title: '지원서는 대신 내지 않아요', text: '쓰는 데까지만 하고, 내는 건 사용자가 해요.' },
];

export function Principles() {
  return (
    <section id="principles" className="lp-principles lp-wrap" aria-labelledby="lp-principles-title">
      <Reveal>
        <h2 id="lp-principles-title" className="lp-section-title">지키는 것</h2>
        <ul className="lp-rows">{ROWS.map((row) => (
          <li key={row.title}><h3>{row.title}</h3><p>{row.text}</p></li>
        ))}</ul>
      </Reveal>
    </section>
  );
}
