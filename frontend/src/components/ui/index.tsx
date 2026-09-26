import type { ReactNode, ButtonHTMLAttributes, InputHTMLAttributes, TextareaHTMLAttributes } from 'react';
import { Link } from 'react-router-dom';
import { FolderGit2, GitCommitHorizontal, Inbox, MessageSquareQuote, Search, X, type LucideIcon } from 'lucide-react';
import type { Evidence as EvidenceT, StarField, CardKind } from '@/api/schemas';
import { evidenceTypeLabel } from '@/lib/labels';

const cx = (...xs: (string | false | null | undefined)[]) => xs.filter(Boolean).join(' ');
const STAR_LABELS: Record<StarField, string> = { S: '상황', T: '과제', A: '행동', R: '결과' };

// ───────── Button ─────────
type BtnProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'outline' | 'text' | 'danger';
  size?: 'sm' | 'md' | 'lg' | 'xl';
  loading?: boolean;
};
export function Button({ variant = 'primary', size = 'md', loading, className, children, disabled, ...rest }: BtnProps) {
  return (
    <button type="button" className={cx('btn', `btn--${variant}`, size !== 'md' && `btn--${size}`, loading && 'btn--loading', className)} disabled={disabled || loading} aria-busy={loading || undefined} {...rest}>
      {children}
    </button>
  );
}

// ───────── Badge (kind = enum 그대로) ─────────
export type BadgeKind = 'CONFIRMED' | 'DRAFT' | 'NEW' | 'USED' | 'EXCLUDED' | 'PR' | 'COMMIT_CLUSTER' | 'ISSUE' | 'MANUAL' | 'CAUTION' | 'FAILED' | 'OPEN' | 'NEUTRAL';
export const Badge = ({ kind, children, title }: { kind: BadgeKind; children: ReactNode; title?: string }) => (
  <span className={`badge badge--${kind}`} title={title}>{children}</span>
);

// ───────── Chip · Check · Radio ─────────
export const Chip = ({ children, fill, onClick, disabled }: { children: ReactNode; fill?: boolean; onClick?: () => void; disabled?: boolean }) =>
  onClick ? <button type="button" className={cx('chip', fill && 'chip--fill')} disabled={disabled} onClick={onClick}>{children}</button>
          : <span className={cx('chip', fill && 'chip--fill')}>{children}</span>;

export const Check = ({ checked, onChange, label, disabled }: { checked: boolean; onChange?: (v: boolean) => void; label: string; disabled?: boolean }) => (
  <button type="button" role="checkbox" aria-checked={checked} aria-label={label} className="check" disabled={disabled} onClick={(e) => { e.stopPropagation(); onChange?.(!checked); }}>
    {checked ? '✓' : ''}
  </button>
);
export const Radio = ({ checked }: { checked: boolean }) => <span className="radio" role="radio" aria-checked={checked} />;

// ───────── 아이콘 박스 · Note · Empty · Divider ─────────
export const IconBox = ({ icon: Icon, size = 32, tone = 'subtle' }: { icon: LucideIcon; size?: number; tone?: 'subtle' | 'ink' | 'paper' }) => (
  <span className={`iconbox iconbox--${tone}`} style={{ width: size, height: size }} aria-hidden><Icon size={Math.round(size * 0.5)} /></span>
);
export const KindIcon = ({ kind, size = 28 }: { kind: CardKind; size?: number }) => <IconBox icon={kind === 'TECH' ? GitCommitHorizontal : MessageSquareQuote} size={size} />;

export const Note = ({ strong, children, tone }: { strong: string; children?: ReactNode; tone?: 'inset' | 'caution' | 'danger' }) => (
  <div className={cx('note', tone && `note--${tone}`)} role={tone === 'danger' ? 'alert' : undefined}>
    <strong>{strong}</strong>{children && <span>{children}</span>}
  </div>
);
export const EmptyState = ({ title, desc, icon: Icon = Inbox, children }: { title: string; desc: string; icon?: LucideIcon; children?: ReactNode }) => (
  <div className="empty">
    <IconBox icon={Icon} size={44} tone="paper" />
    <h2 className="t-18 w-600" style={{ fontSize: 17 }}>{title}</h2>
    <p className="c-2" style={{ maxWidth: 620, fontSize: 13, lineHeight: '21px' }}>{desc}</p>
    {children && <div className="row" style={{ gap: 10, marginTop: 6, flexWrap: 'wrap', justifyContent: 'center' }}>{children}</div>}
  </div>
);
export const Divider = () => <div className="divider" role="separator" />;

// ───────── Input ─────────
export function Field({ label, hint, right, children }: { label: string; hint?: string; right?: ReactNode; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field__label">{label}{right && <span className="right c-3" style={{ fontWeight: 400, fontSize: 11 }}>{right}</span>}</span>
      {children}
      {hint && <span className="t-12l c-2">{hint}</span>}
    </label>
  );
}
export const Input = ({ className, ...p }: InputHTMLAttributes<HTMLInputElement>) => <input className={cx('input', className)} {...p} />;
export const Textarea = ({ className, ...p }: TextareaHTMLAttributes<HTMLTextAreaElement>) => <textarea className={cx('input', className)} {...p} />;

// ───────── Progress · StarKey · StarDots · Evidence ─────────
export const Track = ({ value, label }: { value: number; label?: string }) => (
  <div className="track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(value * 100)} aria-label={label}>
    <span style={{ width: `${Math.max(2, value * 100)}%` }} />
  </div>
);
export const StarKey = ({ field, dropped, small }: { field: StarField; dropped?: boolean; small?: boolean }) => (
  <span className={cx('star-key', dropped && 'star-key--dropped', small && 'star-key--sm')} aria-hidden>{field}</span>
);
/** S·T·A·R 채움 상태를 고정된 네 칸으로 — 목록에서 카드 상태를 한눈에. */
export const StarDots = ({ filled, low, showLabels }: { filled: StarField[]; low?: StarField[]; showLabels?: boolean }) => (
  <span className={cx('stardots', showLabels && 'stardots--labeled')} aria-label={`STAR 채움: ${filled.join('') || '없음'}`}>
    {(['S', 'T', 'A', 'R'] as StarField[]).map((f) => (
      <span key={f} className={cx('stardot', filled.includes(f) && 'stardot--on', low?.includes(f) && 'stardot--low')} title={`${f} ${filled.includes(f) ? (low?.includes(f) ? '확인 필요' : '채움') : '비어 있음'}`}>
        <span className="stardot__letter">{f}</span>
        {showLabels && <span className="stardot__label">{STAR_LABELS[f]}</span>}
      </span>
    ))}
  </span>
);

/** 근거 스트립 — 커밋은 링크(잉크 다크), 사용자 발화는 흰 카드. 무채색. */
export function EvidenceStrip({ e, turnText }: { e: EvidenceT; turnText?: string }) {
  if (e.type === 'COMMIT') {
    return (
      <a className="evidence" href={e.url ?? '#'} target="_blank" rel="noreferrer" title={e.url ?? undefined}>
        <GitCommitHorizontal size={13} className="evidence__icon" aria-hidden />
        <span className="evidence__label">{evidenceTypeLabel.COMMIT}</span>
        <span className="evidence__sha">{e.sha}</span>
        {e.snippet && <span className="evidence__snippet">"{e.snippet}"</span>}
        <span className="evidence__arrow" aria-hidden>↗</span>
      </a>
    );
  }
  return (
    <div className="evidence evidence--user">
      <MessageSquareQuote size={13} className="evidence__icon" aria-hidden />
      <span className="evidence__label">{evidenceTypeLabel[e.type]}</span>
      <span className="grow" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{turnText ?? (e.turnNo ? `되묻기 ${e.turnNo}턴 · 다듬지 않고 그대로 저장됨` : '내가 쓴 문장입니다')}</span>
    </div>
  );
}

// ───────── 페이지 구조 ─────────
export const Breadcrumb = ({ items }: { items: { label: string; to?: string }[] }) => (
  <nav className="crumb" aria-label="breadcrumb">
    {items.map((it, i) => {
      const last = i === items.length - 1;
      return (
        <span key={i} className="row" style={{ gap: 8 }}>
          {it.to && !last ? <Link to={it.to}>{it.label}</Link> : <span className={cx(last && 'crumb__cur')}>{it.label}</span>}
          {!last && <span className="crumb__sep" aria-hidden>›</span>}
        </span>
      );
    })}
  </nav>
);
export const PageTitle = ({ children, right, sub, lead }: { children: ReactNode; right?: ReactNode; sub?: ReactNode; lead?: ReactNode }) => (
  <div className="stack" style={{ gap: 8 }}>
    <div className="page-title"><h1>{children}</h1>{right && <span className="page-title__right">{right}</span>}</div>
    {sub && <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>{sub}</div>}
    {lead && <p className="page-lead">{lead}</p>}
  </div>
);
export const SectionHead = ({ label, count, right }: { label: string; count?: number; right?: ReactNode }) => (
  <div className="section-head">
    <h2>{label}</h2>
    {count != null && <span className="section-head__count">{count}</span>}
    <div className="divider grow" style={{ width: 'auto' }} />
    {right}
  </div>
);
/** 툴바 — 검색(지우기 포함) + 우측 액션. 동작 없는 장식 컨트롤은 두지 않는다. */
export function Toolbar({ placeholder, value, onChange, sort, children }: { placeholder: string; value: string; onChange: (v: string) => void; sort?: string; children?: ReactNode }) {
  return (
    <div className="card toolbar">
      <label className="toolbar__search">
        <Search size={14} className="c-3" aria-hidden />
        <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} aria-label={placeholder} />
        {value && <button type="button" className="toolbar__clear" onClick={() => onChange('')} aria-label="검색 지우기"><X size={13} /></button>}
      </label>
      {sort && <span className="chip chip--static">{sort}</span>}
      <div className="right row" style={{ gap: 8, flexWrap: 'wrap' }}>{children}</div>
    </div>
  );
}
export const RepoContext = ({ name, note, right }: { name: string; note: string; right?: ReactNode }) => (
  <div className="card card--paper repo-ctx">
    <IconBox icon={FolderGit2} size={36} />
    <div className="stack grow" style={{ gap: 4 }}>
      <span className="repo-ctx__name">{name}</span>
      <span className="repo-ctx__note">{note}</span>
    </div>
    {right}
  </div>
);
export const StickyFooter = ({ strong, sub, children }: { strong: string; sub?: string; children: ReactNode }) => (
  <div className="sticky-footer">
    <div className="sticky-footer__text"><strong>{strong}</strong>{sub && <span>{sub}</span>}</div>
    <div className="sticky-footer__actions">{children}</div>
  </div>
);
export const Spinner = () => <span className="spinner" role="status" aria-label="불러오는 중" />;
export const Skeleton = ({ h = 20, w = '100%' }: { h?: number; w?: number | string }) => <div className="skeleton" style={{ height: h, width: w }} aria-hidden />;
