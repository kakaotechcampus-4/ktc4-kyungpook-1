/** 대화 칸 안에서 Gitory 의 말이 시작되는 자리에 붙는 표시 — 그 앞에 오는 이유 줄은 값을 lead 로 둔다 */
export const CHAT_START = 'data-chat-start';

/**
 * 새 말이 칸에 다 들어가면 맨 아래, 넘치면 새 말의 첫 줄이 칸 맨 위(pad 만큼 아래)에 오는 scrollTop.
 * targetTop 은 스크롤 칸 안에서 새 말의 첫 줄 위치다.
 */
export function scrollTopFor({ targetTop, scrollHeight, clientHeight, pad }: { targetTop: number; scrollHeight: number; clientHeight: number; pad: number }) {
  return Math.max(0, Math.min(targetTop - pad, scrollHeight - clientHeight));
}

/**
 * 보여 줄 자리는 내 마지막 말 뒤에 온 Gitory 의 첫 말이고, 답이 아직 없으면 내 마지막 말이다.
 * 내 말이 하나도 없으면 가장 나중 Gitory 말을 잡되, 그 질문의 이유 줄이 있으면 그 줄을 잡는다.
 */
export function findChatTarget(thread: HTMLElement): HTMLElement | null {
  const marks = Array.from(thread.querySelectorAll<HTMLElement>(`.chat-bub--me, [${CHAT_START}]`));
  const lastMe = marks.reduce((found, el, i) => (el.classList.contains('chat-bub--me') ? i : found), -1);
  if (lastMe >= 0) return marks.slice(lastMe + 1).find((el) => el.hasAttribute(CHAT_START)) ?? marks[lastMe];
  const starts = marks.filter((el) => el.hasAttribute(CHAT_START));
  return starts.find((el) => el.getAttribute(CHAT_START) === 'lead') ?? starts.at(-1) ?? null;
}

/** 스크롤되는 칸(box) 안의 대화(thread)에서 새 말 자리로 굴린다 */
export function scrollChatTo(box: HTMLElement, thread: HTMLElement, { pad, smooth }: { pad: number; smooth: boolean }) {
  const target = findChatTarget(thread);
  const behavior = smooth ? 'smooth' : 'auto';
  if (!target) {
    box.scrollTo?.({ top: box.scrollHeight, behavior });
    return;
  }
  const targetTop = target.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop - box.clientTop;
  box.scrollTo?.({ top: scrollTopFor({ targetTop, scrollHeight: box.scrollHeight, clientHeight: box.clientHeight, pad }), behavior });
}

/** 페이지가 스크롤되는 대화 — 아래에 붙은 입력 줄(dock) 위쪽만 보이는 칸으로 본다 */
export function scrollPageTo(thread: HTMLElement, dock: HTMLElement | null, { pad }: { pad: number }) {
  const target = findChatTarget(thread);
  if (!target) return;
  const visible = Math.min(window.innerHeight, dock ? dock.getBoundingClientRect().top : window.innerHeight);
  const threadBottom = thread.getBoundingClientRect().bottom + window.scrollY;
  const targetTop = target.getBoundingClientRect().top + window.scrollY;
  window.scrollTo?.({ top: scrollTopFor({ targetTop, scrollHeight: threadBottom, clientHeight: visible, pad }) });
}
