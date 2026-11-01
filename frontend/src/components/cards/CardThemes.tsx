import { useId, useState } from 'react';
import { Check, Pencil } from 'lucide-react';
import { cardMetadataSupported } from '@/api/capabilities';
import { CARD_THEMES, CARD_THEME_LIMIT, type Card, type CardTheme } from '@/api/schemas';
import { SaveStatus } from '@/components/SaveStatus';
import { Button } from '@/components/ui';
import { themeHint, themeLabel } from '@/lib/cardThemes';
import { useLateThemes, useThemeSelection } from '@/lib/useThemeSelection';
import './CardThemes.css';

/**
 * 경험 카드의 자소서 문항 유형(`themes`)을 보이고 고친다. 자소서에 쓸 카드를 고르는 표시라 확정 카드도 다시 열지 않고 바로 고친다.
 * 서버는 AI 가 고른 유형과 사용자가 고른 유형을 구분해 보내지 않으므로 화면도 구분하지 않는다.
 * 응답에 `themes` 가 없으면(옛 서버) 비어 있음으로 읽지 않고 영역 전체를 숨긴다. 기술 카드는 늘 [] 라 보일 것이 없다.
 */
export function CardThemes({ card, editable = true }: { card: Card; editable?: boolean }) {
  if (card.kind !== 'QUALITATIVE' || card.themes === undefined) return null;
  return <ThemesSection card={card} themes={card.themes} editable={editable} />;
}

function ThemesSection({ card, themes, editable }: { card: Card; themes: CardTheme[]; editable: boolean }) {
  const { selected, state, toggle, retry } = useThemeSelection(card.id, themes);
  const [open, setOpen] = useState(false);
  const labelId = useId();
  const noteId = useId();
  const waiting = useLateThemes(card, state !== 'idle');
  const canEdit = editable && cardMetadataSupported();
  if (!canEdit && selected.length === 0) return null; // 보일 것도 고칠 수도 없으면 자리를 만들지 않는다
  const full = selected.length >= CARD_THEME_LIMIT;
  const verb = selected.length ? '수정' : '고르기';

  if (open && canEdit) {
    return (
      <div className="card-themes">
        <fieldset className="card-themes__editor" aria-describedby={noteId}>
          <legend className="card-themes__label">자소서 문항 유형</legend>
          <div className="card-themes__options">
            {CARD_THEMES.map((theme) => {
              const checked = selected.includes(theme);
              return (
                <label key={theme} className="card-themes__option" data-checked={checked || undefined}>
                  <input type="checkbox" className="card-themes__input" checked={checked} disabled={!checked && full} onChange={() => toggle(theme)} />
                  <span className="card-themes__box" aria-hidden><Check size={14} strokeWidth={2.5} /></span>
                  <span className="card-themes__text">
                    <span className="card-themes__name">{themeLabel[theme]}</span>
                    <span className="card-themes__hint">{themeHint[theme]}</span>
                  </span>
                </label>
              );
            })}
          </div>
          <p id={noteId} className="card-themes__note">
            {full ? `${CARD_THEME_LIMIT}개를 골랐어요. 다른 유형을 고르려면 하나를 해제해 주세요.` : `최대 ${CARD_THEME_LIMIT}개까지 고를 수 있어요.`} 직접 고르면 AI 가 다시 바꾸지 않아요.
          </p>
          <div className="card-themes__foot">
            <SaveStatus status={state} retry={retry} />
            <Button variant="outline" size="sm" onClick={() => setOpen(false)}>완료</Button>
          </div>
        </fieldset>
      </div>
    );
  }

  return (
    <div className="card-themes">
      <div className="card-themes__row">
        <span className="card-themes__label" id={labelId}>자소서 문항 유형</span>
        {selected.length > 0
          ? <ul className="card-themes__chips" aria-labelledby={labelId}>{selected.map((theme) => <li key={theme}>{themeLabel[theme]}</li>)}</ul>
          : <span className="card-themes__empty">{waiting ? 'AI 가 유형을 고르는 중일 수 있어요' : '정해지지 않았어요'}</span>}
        {canEdit && (
          <Button variant="ghost" size="sm" aria-label={`자소서 문항 유형 ${verb}`} onClick={() => setOpen(true)}>
            <Pencil size={14} aria-hidden />{verb}
          </Button>
        )}
      </div>
      {/* 편집창을 닫은 뒤에도 저장하지 못한 채로 남았으면 알린다 */}
      {state === 'error' && <SaveStatus status="error" retry={retry} />}
    </div>
  );
}
