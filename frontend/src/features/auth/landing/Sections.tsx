import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Check, Link2 } from 'lucide-react';
import { LEGAL } from '@/lib/legal';
import { Laptop, Split } from './Shots';

const FLOW = ['내가 한 일 찾기', '경험 정리', '자소서 초안', '공고 맞춰 보기', '면접 대비', '로그인 없이 체험'];

/** 옆으로 흘러가는 한 줄. 같은 줄을 두 번 이어 붙여 끊김 없이 돈다 */
export function FlowStrip() {
  const row = (hidden?: boolean) => (
    <div className="lp-strip__row" aria-hidden={hidden || undefined}>
      {FLOW.map((t) => <span key={t}>{t}<i aria-hidden="true" /></span>)}
    </div>
  );
  return (
    <section className="lp-strip" aria-label="Gitory가 하는 일">
      <div className="lp-strip__track">{row()}{row(true)}</div>
    </section>
  );
}

/** 세 줄로 나눠 보여 주고, 줄 안의 구절이 스크롤에 맞춰 차례로 채워진다 */
const INK = [['흩어진 커밋, PR,', '리뷰 속에'], ['면접에서 꺼낼 경험이', '이미 다 들어 있어요.'], ['찾고,', '정리하고,', '근거를 붙이는 일은', 'Gitory가', '할게요.']];

/** 스크롤하면 회색 글자가 한 구절씩 검게 채워지는 문장 */
export function InkStatement() {
  return (
    <section className="lp-ink" data-ink="">
      <div className="lp-ink__stage">
        <p className="lp-ink__text">
          {INK.map((line, li) => (
            <span key={li} className="lp-ink__line">
              {line.map((t, i) => <span key={i}>{i > 0 && ' '}<span data-ink-part="">{t}</span></span>)}
            </span>
          ))}
        </p>
      </div>
    </section>
  );
}

export function Empathy() {
  return (
    <section className="lp-sec lp-empathy">
      <div className="lp-wrap">
        <Split className="lp-h2 lp-center" lines={['이런 적, 있지 않으세요?']} />
        <div className="lp-grid3">
          <div className="lp-quote" data-reveal="">“이 프로젝트에서<br />내가 뭘 했더라?”</div>
          <div className="lp-quote" data-reveal="">“이걸 왜 이렇게<br />구현했었지?”</div>
          <div className="lp-quote lp-quote--ink" data-reveal="">답은 이미<br />내 커밋에 있어요<small>Gitory가 찾아서 써 드릴게요</small></div>
        </div>
      </div>
    </section>
  );
}

function Step({ no, label, title, body, media, flip, children }: {
  no: string; label: string; title: string[]; body: string; media: ReactNode; flip?: boolean; children?: ReactNode;
}) {
  return (
    <section className={`lp-step${flip ? ' lp-step--flip' : ''}`}>
      <div className="lp-wrap lp-step__in">
        <div className="lp-step__copy">
          <p className="lp-chip" data-reveal="">{no}  {label}</p>
          <Split as="h3" className="lp-h3" lines={title} />
          <p className="lp-body" data-reveal="">{body}</p>
          {children}
        </div>
        <div className="lp-step__media" data-reveal="">{media}</div>
      </div>
    </section>
  );
}

export function Steps() {
  return (
    <>
      <section id="how" className="lp-sec lp-how">
        <div className="lp-wrap lp-center">
          <p className="lp-eyebrow" data-reveal="">어떻게 쓰나요</p>
          <Split className="lp-h2" lines={['레포 고르기부터 자소서까지,', '한 흐름으로 이어져요']} />
        </div>
      </section>
      <Step no="01" label="레포 고르기" title={['레포만 고르면,', '내가 한 일을 찾아 드려요']}
        body="수백 개 커밋 대신, 내가 한 일만 골라 보여 줘요."
        media={<Laptop name="board-d" alt="경험 후보를 고르는 화면" />} />
      <Step no="02" label="경험 카드" title={['내가 한 일이,', '자소서 문장이 돼요']} flip
        body="문장을 누르면 그때 한 작업이 바로 열려요."
        media={<Laptop name="card-d" alt="근거 커밋이 붙은 STAR 경험 카드 화면" />}>
        <div className="lp-callout" data-reveal="">
          <span className="lp-callout__icon" aria-hidden="true"><Link2 size={20} /></span>
          <p>면접에서 “이거 진짜 하셨어요?”<br /><span>커밋 링크로 바로 보여 주세요</span></p>
        </div>
      </Step>
      <Step no="03" label="되묻기" title={['코드에 없는 이야기는,', '지어내지 않고 물어봐요']}
        body="코드로 모르는 건 짧게 묻고, 답으로 빈칸을 채워요."
        media={<Laptop name="interview-d" alt="빈칸을 질문으로 채우는 답변하기 화면" />}>
        <div className="lp-chat" data-reveal="">
          <p className="lp-chat__ask">그때 어떤 역할을 맡았나요?</p>
          <p className="lp-chat__answer">인증 처리를 맡았어요</p>
        </div>
      </Step>
      <Step no="04" label="공고 매칭" title={['공고를 넣으면,', '맞는지 보여 줘요']} flip
        body="조건마다 맞는지 한눈에 보여 줘요."
        media={<Laptop name="match-d" alt="공고 조건과 내 경험을 맞춰 본 화면" />}>
        <div className="lp-tags" data-reveal="">
          <span>자격 요건 <b>3/3 맞음</b></span>
          <span>우대 사항 <b>1/2 맞음</b></span>
        </div>
      </Step>
      <Step no="05" label="자소서" title={['문항만 붙여 넣으면,', '내 경험으로 초안까지']}
        body="내가 진짜 한 일로만, 문항마다 초안을 써요."
        media={<Laptop name="cl-d" alt="문항별 자소서 초안 화면" />}>
        <ul className="lp-grid2" data-reveal="">
          <li><span className="lp-mark">확인할 곳</span>만 짚어 줘요</li>
          <li>말로 고쳐 달라고 하세요</li>
          <li>지원 동기도 같이 채워요</li>
          <li>복사해서 바로 제출</li>
        </ul>
      </Step>
    </>
  );
}

const CHAT = ['레포를 뒤져 내가 한 일을 직접 찾고', '복사해서 붙여 넣고 설명까지 써 줘야', '그럴듯하게 지어낸 문장을 다시 걸러 내고', '공고 조건은 한 줄씩 직접 대조'];
const OURS = ['레포만 고르면 내가 한 일을 알아서 찾아요', '자소서에 쓸 경험으로 정리해 줘요', '지어낸 경험 없이, 내가 한 일만 써요', '공고 조건마다 맞는 경험을 찾아 줘요'];

/** ChatGPT 같은 AI 채팅과 무엇이 다른지 */
export function Compare() {
  return (
    <section className="lp-sec lp-compare">
      <div className="lp-wrap">
        <p className="lp-eyebrow lp-center" data-reveal="">ChatGPT가 있는데, 왜 Gitory일까요?</p>
        <Split className="lp-h2 lp-center" lines={['글을 써 주는 AI는 많아요.', '내 커밋에서 찾아 주는 건 Gitory뿐이에요']} />
        <div className="lp-compare__cols">
          <div className="lp-compare__col" data-reveal="">
            <p className="lp-compare__label">AI 채팅으로 쓰면</p>
            <ol>{CHAT.map((t, i) => <li key={t}><span className="lp-num" aria-hidden="true">{i + 1}</span>{t}</li>)}</ol>
          </div>
          <div className="lp-compare__col lp-compare__col--ours" data-reveal="">
            <p className="lp-compare__label">Gitory로 쓰면</p>
            <ul>{OURS.map((t) => <li key={t}><span className="lp-check" aria-hidden="true"><Check size={15} strokeWidth={3} /></span>{t}</li>)}</ul>
          </div>
        </div>
      </div>
    </section>
  );
}

/** 마지막 시작 권유. 버튼은 위 히어로와 같은 동작을 받는다 */
export function Closing({ actions }: { actions: ReactNode }) {
  return (
    <section className="lp-sec lp-closing">
      <div className="lp-wrap">
        <div className="lp-closing__box" data-reveal="">
          <div className="lp-closing__copy">
            <h2 className="lp-h2">오늘 올린 커밋도,<br />내일의 자소서가 됩니다</h2>
            <p className="lp-body">공개 레포만 읽고, 코드는 바꾸지 않아요.</p>
            <div className="lp-actions">{actions}</div>
          </div>
          <div className="lp-closing__media" aria-hidden="true">
            <Laptop name="home-d" alt="" />
          </div>
        </div>
      </div>
    </section>
  );
}

export function LandingFooter() {
  return (
    <footer className="lp-footer">
      <div className="lp-wrap lp-footer__in">
        <div className="lp-footer__about">
          <strong>Gitory</strong>
          <p>GitHub 기록으로 경험 카드와 자소서를 만들어요.</p>
        </div>
        <nav className="lp-footer__links" aria-label="정책과 문의">
          <Link to="/terms">이용약관</Link>
          <Link to="/privacy" className="lp-footer__strong">개인정보처리방침</Link>
          <Link to="/faq">자주 묻는 질문</Link>
          <a href="https://github.com/kakaotechcampus-4/ktc4-kyungpook-1" target="_blank" rel="noreferrer">GitHub 저장소</a>
        </nav>
        <p className="lp-footer__copy">© 2026 {LEGAL.operator}</p>
      </div>
    </footer>
  );
}
