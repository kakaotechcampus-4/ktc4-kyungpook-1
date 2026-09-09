import GithubConnectionCard from "../home/GithubConnectionCard";
import { GITHUB_CONNECTION } from "../../data/user";

export default function GithubSettingsPage({ onNavigate }) {
  return (
    <div className="mx-auto max-w-5xl px-8 py-9">
      <h1 className="text-[22px] font-semibold tracking-tight text-ink-900">GitHub 연결</h1>
      <p className="mt-1 text-sm text-ink-500">
        연결된 계정과 접근 권한을 확인하고, 경험 카드 초안을 만들 레포를 선택하세요.
      </p>

      <div className="mt-6 max-w-sm">
        <GithubConnectionCard connection={GITHUB_CONNECTION} onSelectRepo={() => onNavigate?.("organize")} />
      </div>
    </div>
  );
}
