import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { CompanyMark } from "@/features/match/CompanyMark";
import { companyMonogram, companyTone } from "@/features/match/model";
import { LOGO } from "./match-company-fixtures";

// jsdom 은 <img> 를 실제로 받지 않는다 — 이 시험은 네트워크로 나가지 않는다. 로드 실패는 error 이벤트로 재현한다.
afterEach(cleanup);
const markOf = (container: HTMLElement) => container.querySelector(".company-mark") as HTMLElement;

it("https 로고 주소가 있으면 장식 이미지로 그린다 — alt 비움, aria-hidden, 지연 로딩, 고정 크기, 리퍼러 없음", () => {
  const { container } = render(<CompanyMark name="카카오" logoUrl={LOGO} size={44} />);
  const mark = markOf(container);
  expect(mark).toHaveAttribute("aria-hidden", "true");
  expect(mark).toHaveClass("company-mark--logo");
  expect(mark.style.getPropertyValue("--cm-size")).toBe("44px");
  const img = mark.querySelector("img")!;
  expect(img).toHaveAttribute("src", LOGO);
  expect(img).toHaveAttribute("alt", "");
  expect(img).toHaveAttribute("aria-hidden", "true");
  expect(img).toHaveAttribute("loading", "lazy");
  expect(img).toHaveAttribute("decoding", "async");
  expect(img).toHaveAttribute("referrerpolicy", "no-referrer");
  expect(img).toHaveAttribute("width", "44");
  expect(img).toHaveAttribute("height", "44");
  expect(mark).not.toHaveTextContent("카"); // 이미지가 있으면 이니셜을 겹쳐 그리지 않는다
  expect(img).not.toHaveAttribute("crossorigin");
});

it("로고가 없으면(null·undefined·빈 문자열) 이니셜과 이름에서 정해진 색을 그린다", () => {
  for (const logoUrl of [null, undefined, ""]) {
    const { container, unmount } = render(<CompanyMark name="나래랩스" logoUrl={logoUrl} />);
    const mark = markOf(container);
    expect(mark.querySelector("img")).toBeNull();
    expect(mark).toHaveTextContent(companyMonogram("나래랩스"));
    expect(mark).toHaveClass(`company-mark--${companyTone("나래랩스")}`);
    expect(mark).not.toHaveClass("company-mark--logo");
    expect(mark).toHaveAttribute("aria-hidden", "true");
    unmount();
  }
});

it("이미지를 불러오지 못하면(error) 이니셜로 되돌린다 — 이름은 그대로", () => {
  const { container } = render(<CompanyMark name="카카오" logoUrl={LOGO} />);
  fireEvent.error(container.querySelector("img")!);
  const mark = markOf(container);
  expect(mark.querySelector("img")).toBeNull();
  expect(mark).toHaveTextContent("카");
  expect(mark).toHaveClass(`company-mark--${companyTone("카카오")}`);
  expect(mark).toHaveAttribute("aria-hidden", "true");
});

it("한 번 실패한 주소는 다시 그리지 않고, 다른 주소가 오면 새로 시도한다", () => {
  const { container, rerender } = render(<CompanyMark name="카카오" logoUrl={LOGO} />);
  fireEvent.error(container.querySelector("img")!);
  rerender(<CompanyMark name="카카오" logoUrl={LOGO} />);
  expect(container.querySelector("img")).toBeNull();
  rerender(<CompanyMark name="카카오" logoUrl="https://logo.example.com/kakao-v2.svg" />);
  expect(container.querySelector("img")).toHaveAttribute("src", "https://logo.example.com/kakao-v2.svg");
});

it("https 가 아닌 주소는 스키마를 거치지 않고 직접 넘어와도 그리지 않는다", () => {
  for (const bad of ["http://logo.example.com/a.png", "javascript:alert(1)", "data:image/png;base64,AAAA", "//logo.example.com/a.png", "/a.png", "https://u:p@logo.example.com/a.png"]) {
    const { container, unmount } = render(<CompanyMark name="토스" logoUrl={bad} />);
    expect(container.querySelector("img"), bad).toBeNull();
    expect(markOf(container)).toHaveTextContent("토");
    unmount();
  }
});
