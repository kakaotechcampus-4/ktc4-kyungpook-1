import Avatar from "../common/Avatar";

export default function GithubConnectionCard({ connection, onSelectRepo }) {
  return (
    <div className="w-full shrink-0 rounded-xl bg-white p-4 shadow-sm shadow-slate-200/60 ring-1 ring-slate-200/70 lg:w-80">
      <h3 className="mb-3 text-sm font-semibold text-slate-900">GitHub 연결 상태</h3>

      <div className="mb-3 flex items-center gap-3">
        <Avatar name={connection.username} />
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-slate-900">
            {connection.username} · <span className="font-medium text-emerald-600">연결됨</span>
          </div>
          <div className="text-xs text-slate-400">읽기 전용 · {connection.scope}</div>
        </div>
      </div>

      <p className="mb-3 text-xs leading-relaxed text-slate-500">
        public 레포만 읽습니다. private 레포를 포함하려면 권한을 추가하세요.
      </p>

      <div className="flex gap-2">
        <button
          onClick={onSelectRepo}
          className="rounded-lg bg-slate-900 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-slate-800"
        >
          레포 선택
        </button>
        <button className="rounded-lg border border-slate-200 px-3 py-2 text-xs font-medium text-slate-600 transition-colors hover:bg-slate-50">
          권한 추가
        </button>
      </div>
    </div>
  );
}
