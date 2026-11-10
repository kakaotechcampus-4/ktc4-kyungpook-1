import { describe, expect, it, vi } from 'vitest';
import { findChatTarget, scrollChatTo, scrollTopFor } from '@/components/chat/chatScroll';

const thread = (html: string) => {
  const el = document.createElement('div');
  el.innerHTML = html;
  return el;
};

describe('대화 스크롤 계산', () => {
  it('C1 새 말이 대화 칸에 다 들어가면 맨 아래로 내리고, 넘치면 새 말의 첫 줄을 칸 맨 위에 맞춘다', () => {
    // 칸 240, 전체 600 — 마지막 말이 100 이면 다 들어가므로 맨 아래(360)
    expect(scrollTopFor({ targetTop: 500, scrollHeight: 600, clientHeight: 240, pad: 8 })).toBe(360);
    // 새 말이 400 이라 칸보다 길면 첫 줄 위치에서 pad 만큼 위
    expect(scrollTopFor({ targetTop: 200, scrollHeight: 600, clientHeight: 240, pad: 8 })).toBe(192);
    // 칸보다 짧은 대화는 0 에서 안 움직인다
    expect(scrollTopFor({ targetTop: 20, scrollHeight: 150, clientHeight: 240, pad: 8 })).toBe(0);
  });

  it('C2 보여 줄 자리는 내 마지막 말 뒤에 온 Gitory 의 첫 말이고, 아직 답이 없으면 내 마지막 말이다', () => {
    const answered = thread(`
      <span id="f1" data-chat-start=""></span><div id="q1" class="chat-bub"></div><div id="m1" class="chat-bub chat-bub--me"></div>
      <span id="f2" data-chat-start=""></span><div id="a2" class="chat-bub"></div><div id="q2" class="chat-bub"></div>`);
    expect(findChatTarget(answered)?.id).toBe('f2');
    const waiting = thread(`<span id="f1" data-chat-start=""></span><div class="chat-bub chat-bub--me" id="m1"></div><div class="chat-bub chat-bub--me" id="m2"></div>`);
    expect(findChatTarget(waiting)?.id).toBe('m2');
    // 내 말이 없으면 가장 나중 Gitory 말, 그 질문의 이유 줄이 있으면 이유 줄
    const first = thread(`<span id="f1" data-chat-start=""></span><p id="why" data-chat-start="lead"></p><span id="f2" data-chat-start=""></span>`);
    expect(findChatTarget(first)?.id).toBe('why');
  });

  it('C4 자소서 대화에서 답을 보낸 뒤 Gitory 의 첫 답 첫 줄이 패널의 붙어 있는 머리 줄에 가리지 않고 보인다', () => {
    const box = document.createElement('div');
    const el = thread(`<div class="chat-bub chat-bub--me"></div><span data-chat-start=""></span>`);
    const start = el.querySelector('[data-chat-start]')!;
    // 칸은 화면 위 100 에서 시작해 이미 30 굴려져 있고, 첫 답의 첫 줄은 화면 위 400 에 있다 — 칸 안 위치는 400 - 100 + 30 = 330
    box.getBoundingClientRect = () => ({ top: 100 }) as DOMRect;
    start.getBoundingClientRect = () => ({ top: 400 }) as DOMRect;
    box.scrollTop = 30;
    Object.defineProperty(box, 'scrollHeight', { value: 900 });
    Object.defineProperty(box, 'clientHeight', { value: 300 });
    box.scrollTo = vi.fn() as unknown as typeof box.scrollTo;
    scrollChatTo(box, el, { pad: 52, smooth: false });
    // 머리 줄 높이(44)와 여백(8)만큼 아래에 놓여 머리 줄 밑에 닿는다
    expect(box.scrollTo).toHaveBeenCalledWith({ top: 330 - 52, behavior: 'auto' });
  });
});
