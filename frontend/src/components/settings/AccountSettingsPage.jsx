import { CURRENT_USER } from "../../data/user";
import Avatar from "../common/Avatar";

export default function AccountSettingsPage({ onLogout }) {
  return (
    <div className="mx-auto max-w-5xl px-8 py-9">
      <h1 className="text-[22px] font-semibold tracking-tight text-ink-900">마이페이지</h1>
      <p className="mt-1 text-sm text-ink-500">계정 정보와 이용 중인 플랜을 확인하세요.</p>

      <div className="mt-6 max-w-sm rounded-xl border border-ink-200 bg-white p-5">
        <div className="flex items-center gap-3">
          <Avatar name={CURRENT_USER.name} size={11} />
          <div>
            <div className="text-sm font-semibold text-ink-900">{CURRENT_USER.name}</div>
            <div className="text-xs text-ink-400">{CURRENT_USER.plan} 플랜</div>
          </div>
        </div>
        <div className="mt-4 flex items-center justify-between border-t border-ink-100 pt-4 text-sm">
          <span className="text-ink-500">남은 크레딧</span>
          <span className="font-semibold tabular-nums text-ink-900">{CURRENT_USER.credits}개</span>
        </div>
        <div className="mt-4 border-t border-ink-100 pt-4">
          <button
            onClick={onLogout}
            className="text-sm font-medium text-ink-400 transition-colors hover:text-ink-600"
          >
            로그아웃
          </button>
        </div>
      </div>
    </div>
  );
}
