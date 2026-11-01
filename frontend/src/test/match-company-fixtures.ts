import { MatchTarget } from "@/api/matching";
import sampleList from "../../docs/examples/matching/list.json";

const base = MatchTarget.parse(sampleList.data[0]);

/** 지어낸 로고 주소 — 단위 시험(jsdom)은 이미지를 받지 않고, 브라우저 시험은 이 호스트를 가로챈다. 실제 네트워크로 나가지 않는다. */
export const LOGO = "https://logo.example.com/kakao.svg";

type Spec = Pick<MatchTarget, "id" | "company" | "role"> & Partial<MatchTarget>;
/** 예시 JSON 이 companyId·logoUrl 을 갖게 돼도 시험이 달라지지 않도록 두 칸을 항상 직접 정한다 */
export const target = (spec: Spec): MatchTarget => ({ ...base, fit: null, companyId: null, logoUrl: null, ...spec });

/**
 * 서버가 companyId·logoUrl 을 준 목록의 한 장면.
 * - 카카오: 같은 회사(companyId kakao)인데 이름 표기가 두 가지, 로고는 한쪽 공고에만 있다
 * - 오로라랩스: 로켓펀치 출처 — companyId 에 `:` 와 한글이 들어간다, 로고 없음
 * - 토스: 공고 1건, 로고 없음
 * - 나래랩스: 구서버처럼 companyId 가 없는 공고 — 이름으로 묶는다
 */
export const companyFixture: MatchTarget[] = [
  target({ id: "k1", company: "카카오", companyId: "kakao", logoUrl: LOGO, role: "백엔드 개발자", fit: "A" }),
  target({ id: "r1", company: "오로라랩스", companyId: "rocketpunch:오로라랩스", role: "프론트엔드 개발자", category: "FRONTEND", fit: "B" }),
  target({ id: "k2", company: "Kakao Corp.", companyId: "kakao", role: "서버 개발자", category: "ETC", fit: null }),
  target({ id: "t1", company: "토스", companyId: "toss", role: "데이터 엔지니어", category: "DATA", fit: "D" }),
  target({ id: "r2", company: "오로라랩스", companyId: "rocketpunch:오로라랩스", role: "QA 엔지니어", category: "QA", fit: "C" }),
  target({ id: "n1", company: "나래랩스", companyId: null, role: "인프라 엔지니어", category: "DEVOPS", fit: "B" }),
];
