import { Button, ButtonLink, PageTitle, TitleCount, buttonClass, type ButtonVariant } from '@/components/ui';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import './uiShowcase.css';

const VARIANTS: { variant: ButtonVariant; name: string; when: string }[] = [
  { variant: 'primary', name: '주 버튼', when: '그 화면의 다음 걸음. 화면마다 하나' },
  { variant: 'secondary', name: '보조 버튼', when: '이전, 뒤로, 닫기, 다른 선택지' },
  { variant: 'text', name: '글자 버튼', when: '부가 정보, 자세히 보기' },
  { variant: 'danger', name: '위험 버튼', when: '취소, 삭제처럼 되돌리기 어려운 일' },
];

/**
 * 디자인 기본값 견본(#804, #918) — 목에서만 열린다(/__ui). 새 화면을 만들 때 여기 있는 것만 쓰고,
 * 리뷰에서는 "새 버튼이 넷 중 무엇인지"를 묻는다. 값은 tokens.css, 규칙은 design-tokens-guard 시험이 지킨다.
 */
export function UiShowcasePage() {
  useDocumentTitle('디자인 기본값');
  return (
    <main className="main ui-show">
      <PageTitle lead="새 화면은 여기 있는 것만 써요. 값은 tokens.css 에 있고, 시험(design-tokens-guard)이 벗어나는 걸 막아요.">디자인 기본값</PageTitle>

      <section>
        <h2>버튼은 네 가지</h2>
        <div className="ui-show__grid">
          {VARIANTS.map((v) => (
            <div key={v.variant} className="ui-show__cell">
              <Button variant={v.variant}>{v.name}</Button>
              <span className="w-bold">{v.variant}</span>
              <span className="c-2 t-13">{v.when}</span>
            </div>
          ))}
        </div>
        <div className="row ui-show__row">
          <Button size="sm">작게</Button><Button>보통</Button><Button size="lg">크게</Button>
          <Button loading>저장 중</Button><Button disabled>못 누름</Button>
          <ButtonLink to="/" variant="secondary">화면 이동은 ButtonLink</ButtonLink>
          <a className={buttonClass('text')} href="https://github.com" target="_blank" rel="noreferrer">바깥 링크는 buttonClass()</a>
        </div>
      </section>

      <section>
        <h2>글자 굵기는 세 단계</h2>
        <p className="ui-show__weight" style={{ fontWeight: 'var(--weight-regular)' }}>본문 400, 설명과 긴 글</p>
        <p className="ui-show__weight" style={{ fontWeight: 'var(--weight-bold)' }}>강조와 버튼 700, 이름과 값</p>
        <p className="ui-show__weight" style={{ fontWeight: 'var(--weight-heavy)' }}>큰 제목 800</p>
      </section>

      <section>
        <h2>제목 옆 개수<TitleCount n={3} /></h2>
        <p className="c-2 t-14">제목과 같은 크기, 옅은 색, 한 칸 띄움. 단위(개, 건)는 붙이지 않아요. &lt;TitleCount n=&#123;3&#125; /&gt;</p>
      </section>

      <section>
        <h2>공고 한 줄</h2>
        <p className="posting-facts">백엔드, 신입, 학사 이상</p>
        <p className="c-2 t-14">직무, 경력, 학력은 쉼표로 이은 글. 칩은 기술 이름에만 써요(내 카드와 겹치면 표시가 붙는 것).</p>
      </section>

      <section>
        <h2>지금 고른 칸</h2>
        <div className="ui-show__cells">
          <div className="ui-show__slot">지난 칸</div>
          <div className="ui-show__slot is-now">지금 쓰는 칸</div>
        </div>
        <p className="c-2 t-14">둥근 상자 안의 칸은 가장자리에 테두리를 그리지 않고, 안쪽으로 들여 둥근 테두리를 그려요. 모서리와 옆 칸 경계에 잘리지 않아요.</p>
      </section>
    </main>
  );
}
