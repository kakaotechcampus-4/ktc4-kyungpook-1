import { useRef, type KeyboardEvent } from 'react';

export type PanelTab = 'fill' | 'polish' | 'inference';
export const PANEL_TABS: { key: PanelTab; label: string }[] = [
  { key: 'fill', label: '채우기' },
  { key: 'polish', label: '진단과 첨삭' },
  { key: 'inference', label: '짐작 확인' },
];
export const tabId = (k: PanelTab) => `clp-tab-${k}`;
export const tabPanelId = (k: PanelTab) => `clp-panel-${k}`;

/** 패널 탭 셋 — 한 번에 하나만 보인다. 화살표·Home·End 로 옮겨 다닌다(탭 하나만 Tab 순서에 든다) */
export function PanelTabs({ tab, onTab, badge }: { tab: PanelTab; onTab: (t: PanelTab) => void; badge: number }) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  const onKey = (e: KeyboardEvent) => {
    const i = PANEL_TABS.findIndex((t) => t.key === tab);
    const to = e.key === 'ArrowRight' ? (i + 1) % PANEL_TABS.length : e.key === 'ArrowLeft' ? (i + PANEL_TABS.length - 1) % PANEL_TABS.length
      : e.key === 'Home' ? 0 : e.key === 'End' ? PANEL_TABS.length - 1 : -1;
    if (to < 0) return;
    e.preventDefault();
    onTab(PANEL_TABS[to].key);
    refs.current[PANEL_TABS[to].key]?.focus();
  };
  return (
    <div className="clp-seg clp-tabs" role="tablist" aria-label="다듬기" onKeyDown={onKey}>
      {PANEL_TABS.map((t) => (
        <button key={t.key} ref={(el) => { refs.current[t.key] = el; }} type="button" role="tab" id={tabId(t.key)} aria-selected={tab === t.key}
          aria-controls={tabPanelId(t.key)} tabIndex={tab === t.key ? 0 : -1} onClick={() => onTab(t.key)}>
          {t.label}
          {t.key === 'inference' && badge > 0 && <span className="clp-badge">{badge}</span>}
        </button>
      ))}
    </div>
  );
}
