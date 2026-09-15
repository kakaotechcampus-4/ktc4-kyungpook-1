import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useCreateManualCard, useRepos } from '@/api/queries';
import type { StarField } from '@/api/schemas';
import { Breadcrumb, Button, EvidenceStrip, Field, Input, PageTitle, StarKey, StickyFooter, Textarea } from '@/components/ui';
import { STAR_FIELDS, starFieldName } from '@/lib/labels';
import { track } from '@/lib/track';
import { toast } from '@/lib/toast';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

const HINT: Record<StarField, string> = {
  S: '어떤 상황이었나요? 팀 규모와 맥락을 한 줄로.',
  T: '무엇을 해내야 했나요?',
  A: '본인이 무엇을 했나요? "우리" 대신 "나"로 쓰면 면접에서 흔들리지 않습니다.',
  R: '무엇이 얼마나 달라졌나요? 숫자가 없으면 없다고 쓰면 됩니다.',
};
const MAX = 300;

/** E4 정성 카드 직접 작성 — 코드에 없는 경험. 근거 = 내가 말한 것(USER_STATED). 출처를 물리적으로 분리한다. */
export function NewCardPage() {
  useDocumentTitle('직접 작성');
  const nav = useNavigate();
  const repos = useRepos();
  const create = useCreateManualCard();
  const [title, setTitle] = useState('');
  const [period, setPeriod] = useState('');
  const [repoId, setRepoId] = useState<string>('');
  const [f, setF] = useState<Record<StarField, string>>({ S: '', T: '', A: '', R: '' });
  const filled = STAR_FIELDS.filter((k) => f[k].trim());
  const canConfirm = !!f.S.trim() && !!f.A.trim() && !!title.trim();

  const save = async (thenConfirm: boolean) => {
    const card = await create.mutateAsync({ title: title.trim(), period, repoId: repoId || null, fields: Object.fromEntries(filled.map((k) => [k, f[k].trim()])) });
    track('manual_card_created', { cardId: card.id, fields: filled.join('') });
    if (!thenConfirm) toast('임시 저장했습니다 — 빈 칸은 나중에 채울 수 있습니다', { tone: 'success' });
    nav(`/cards/${card.id}${thenConfirm ? '?confirm=1' : ''}`);
  };

  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={[{ label: '경험정리/홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: '직접 작성' }]} />
      <PageTitle right="근거 = 내가 말한 것">코드에 없는 경험 쓰기</PageTitle>

      <div className="card row" style={{ gap: 16, padding: '16px 20px', alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div className="grow" style={{ minWidth: 240 }}><Field label="카드 제목"><Input autoFocus value={title} onChange={(e) => setTitle(e.target.value)} placeholder="예) 팀원과 토큰 저장 위치로 갈린 경험" /></Field></div>
        <div style={{ width: 160 }}><Field label="기간"><Input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="2024.04" /></Field></div>
        <div style={{ width: 220 }}>
          <Field label="관련 레포 (선택)">
            <select className="input" value={repoId} onChange={(e) => setRepoId(e.target.value)} aria-label="관련 레포">
              <option value="">없음</option>
              {(repos.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.owner} / {r.name}</option>)}
            </select>
          </Field>
        </div>
      </div>

      <div className="card star-read">
        {STAR_FIELDS.map((k, i) => (
          <div key={k} className="star-read__row">
            <StarKey field={k} dropped={i > 0 && !f[k]} />
            <div className="stack grow" style={{ gap: 9 }}>
              <div className="row"><span className="star__name">{starFieldName[k]}</span><span className="right t-12 c-3">{f[k].length} / {MAX}자</span></div>
              <Textarea className="input--lg" rows={3} maxLength={MAX} value={f[k]} onChange={(e) => setF((x) => ({ ...x, [k]: e.target.value }))} placeholder={HINT[k]} aria-label={starFieldName[k]} />
              <EvidenceStrip e={{ field: k, type: 'USER_STATED', sha: null, url: null, snippet: null, turnNo: 0 }} turnText="이 칸의 근거는 USER_STATED — 내가 쓴 문장입니다" />
            </div>
          </div>
        ))}
      </div>

      <StickyFooter strong={filled.length ? `${filled.join(' · ')} 작성됨` : 'S 와 A 를 채우면 확정할 수 있어요'}>
        <Link to="/" className="btn btn--text">취소</Link>
        <Button variant="outline" disabled={!title.trim() || !filled.length} loading={create.isPending} onClick={() => save(false)}>임시 저장</Button>
        <Button size="lg" disabled={!canConfirm} loading={create.isPending} onClick={() => save(true)}>확정으로</Button>
      </StickyFooter>
    </main>
  );
}
