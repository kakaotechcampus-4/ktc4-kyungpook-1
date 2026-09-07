import { CURRENT_USER } from "../../data/user";
import Avatar from "../common/Avatar";

export default function AccountSettingsPage({ onLogout }) {
  return (
    <div className="mx-auto max-w-5xl px-8 py-7">
      <h1 className="text-[22px] font-semibold tracking-tight text-slate-900">마이페이지</h1>
      <p className="mt-1 text-sm text-slate-500">계정 정보와 이용 중인 플랜을 확인하세요.</p>

      <div className="mt-5 max-w-sm rounded-xl bg-white p-4 shadow-sm shadow-slate-200/60 ring-1 ring-slate-200/70">
        <div className="flex items-center gap-3">
          <Avatar name={CURRENT_USER.name} size={11} />
          <div>
            <div className="text-sm font-semibold text-slate-900">{CURRENT_USER.name}</div>
            <div className="text-xs text-slate-400">{CURRENT_USER.plan} 플랜</div>
          </div>
        </div>
        <div className="mt-4 flex items-center justify-between border-t border-slate-100 pt-4 text-sm">
          <span className="text-slate-500">남은 크레딧</span>
          <span className="font-semibold text-slate-900">{CURRENT_USER.credits}개</span>
        </div>
        <div className="mt-4 border-t border-slate-100 pt-4">
          <button
            onClick={onLogout}
            className="text-sm font-medium text-slate-400 transition-colors hover:text-slate-600"
          >
            로그아웃
          </button>
        </div>
      </div>
    </div>
  );
}
