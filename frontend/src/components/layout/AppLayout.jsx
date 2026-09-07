import Sidebar from "./Sidebar";

export default function AppLayout({ active, onNavigate, children }) {
  return (
    <div className="flex h-screen w-full overflow-hidden bg-slate-50 font-sans text-slate-900">
      <Sidebar active={active} onNavigate={onNavigate} />
      <main className="flex-1 overflow-y-auto">{children}</main>
    </div>
  );
}
