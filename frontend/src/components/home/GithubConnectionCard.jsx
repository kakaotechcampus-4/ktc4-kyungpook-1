import Avatar from "../common/Avatar";

export default function GithubConnectionCard({ connection, onSelectRepo }) {
  return (
    <div className="w-full shrink-0 rounded-xl border border-ink-200 bg-white p-4 lg:w-80">
      <h3 className="mb-3 text-sm font-semibold text-ink-900">GitHub 연결 상태</h3>

      <div className="mb-3 flex items-center gap-3">
        <Avatar name={connection.username} />
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-ink-900">
            {connection.username} · <span className="font-medium text-emerald-600">연결됨</span>
          </div>
          <div className="text-xs text-ink-400">읽기 전용 · {connection.scope}</div>
        </div>
      </div>

      <p className="mb-3 text-xs leading-relaxed text-ink-500">
        public 레포만 읽습니다. private 레포를 포함하려면 권한을 추가하세요.
      </p>

      <div className="flex gap-2">
        <button
          onClick={onSelectRepo}
          className="rounded-lg bg-accent-600 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-accent-700"
        >
          레포 선택
        </button>
        <button className="rounded-lg border border-ink-200 px-3 py-2 text-xs font-medium text-ink-600 transition-colors hover:bg-ink-50">
          권한 추가
        </button>
      </div>
    </div>
  );
}
