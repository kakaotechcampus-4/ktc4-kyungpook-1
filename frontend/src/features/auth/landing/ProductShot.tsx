import { ThemedShot } from './Shot';

/** 첫 화면 아래로 이어지는 실제 홈 화면. 브라우저 창 모양 틀에 넣는다 */
export function ProductShot() {
  return (
    <figure className="lp-frame">
      <div className="lp-frame__bar" aria-hidden="true"><i /><i /><i /><span>Gitory — 홈</span></div>
      <ThemedShot name="home" width={1440} height={780}
        alt="Gitory 홈 화면. 지원할 곳 3곳이 마감 순으로 보이고, 가온소프트 백엔드 엔지니어 공고 카드에 자소서 쓰기 버튼이 있어요." />
    </figure>
  );
}
