import { useState } from "react";
import { Home, FolderGit2, ListChecks, GitBranch, User, ChevronLeft, ChevronRight } from "lucide-react";
import { CURRENT_USER } from "../../data/user";
import Avatar from "../common/Avatar";

const NAV_GROUPS = [
  {
    label: "커리어 관리",
    items: [
      { key: "home", label: "경험정리/홈", icon: Home },
      { key: "organize", label: "레포 정리", icon: FolderGit2 },
      { key: "cards", label: "경험 카드", icon: ListChecks },
    ],
  },
  {
    label: "설정",
    items: [
      { key: "github", label: "GitHub 연결", icon: GitBranch },
      { key: "mypage", label: "마이페이지", icon: User },
    ],
  },
];

const STORAGE_KEY = "gitory:sidebar-collapsed";

function readInitialCollapsed() {
  try {
    return localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

export default function Sidebar({ active, onNavigate }) {
  const [collapsed, setCollapsed] = useState(readInitialCollapsed);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(STORAGE_KEY, next ? "1" : "0");
      } catch {
        // 저장 실패는 무시 — 세션 동안만 접힘 상태 유지
      }
      return next;
    });
  };

  return (
    <aside
      className={`relative flex shrink-0 flex-col border-r border-slate-200 bg-white transition-all duration-200 ease-in-out ${
        collapsed ? "w-16" : "w-56"
      }`}
    >
      {/* 접기/펼치기 — 사이드바·본문 경계선에 걸치는 원형 버튼 */}
      <button
        onClick={toggleCollapsed}
        title={collapsed ? "사이드바 펼치기" : "사이드바 접기"}
        className="absolute -right-3 top-6 z-10 grid h-6 w-6 place-items-center rounded-full border border-slate-200 bg-white text-slate-400 shadow-sm transition-colors hover:bg-slate-50 hover:text-slate-600"
      >
        {collapsed ? (
          <ChevronRight className="h-3.5 w-3.5" strokeWidth={2} />
        ) : (
          <ChevronLeft className="h-3.5 w-3.5" strokeWidth={2} />
        )}
      </button>

      <div className="flex h-full flex-col overflow-hidden">
        {/* 로고 */}
        <div className={`flex items-center gap-2.5 px-5 py-5 ${collapsed ? "justify-center px-0" : ""}`}>
          <div className="grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-slate-900 text-sm font-semibold text-white">
            G
          </div>
          {!collapsed && (
            <span className="whitespace-nowrap text-lg font-semibold tracking-tight text-slate-900">Gitory</span>
          )}
        </div>

        {/* 메뉴 */}
        <nav className="flex flex-1 flex-col gap-5 px-3">
          {NAV_GROUPS.map((group) => (
            <div key={group.label}>
              {!collapsed && (
                <div className="whitespace-nowrap px-2 pb-2 text-xs font-medium text-slate-400">{group.label}</div>
              )}
              <div className="flex flex-col gap-0.5">
                {group.items.map(({ key, label, icon: Icon }) => {
                  const on = active === key;
                  return (
                    <button
                      key={key}
                      title={collapsed ? label : undefined}
                      onClick={() => onNavigate(key)}
                      className={`flex items-center gap-3 rounded-lg py-2.5 text-sm font-medium transition-colors ${
                        collapsed ? "justify-center px-0" : "px-3"
                      } ${on ? "bg-slate-900 text-white" : "text-slate-600 hover:bg-slate-100"}`}
                    >
                      <Icon className={`h-[18px] w-[18px] shrink-0 ${on ? "text-white" : "text-slate-400"}`} strokeWidth={1.8} />
                      {!collapsed && <span className="whitespace-nowrap">{label}</span>}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>

        {/* 하단 프로필 */}
        <div className={`border-t border-slate-100 p-3 ${collapsed ? "flex justify-center" : ""}`}>
          <button
            onClick={() => onNavigate("mypage")}
            title={collapsed ? "마이페이지" : undefined}
            className={`flex w-full items-center gap-3 rounded-lg py-2 text-left transition-colors hover:bg-slate-50 ${
              collapsed ? "justify-center px-0" : "px-2"
            }`}
          >
            <Avatar name={CURRENT_USER.name} />
            {!collapsed && (
              <div className="min-w-0 leading-tight">
                <div className="truncate text-sm font-semibold text-slate-900">{CURRENT_USER.name}</div>
                <div className="truncate text-xs text-slate-400">
                  {CURRENT_USER.plan} · 크레딧 {CURRENT_USER.credits}
                </div>
              </div>
            )}
          </button>
        </div>
      </div>
    </aside>
  );
}
