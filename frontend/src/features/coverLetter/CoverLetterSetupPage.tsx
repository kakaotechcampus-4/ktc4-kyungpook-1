import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { FileText } from 'lucide-react';
import { CoverLetterQuestion, useCoverLetters, useCreateCoverLetter } from '@/api/coverLetter';
import { matchSupported, useMatch, useMatches } from '@/api/matching';
import { useCards } from '@/api/queries';
import type { CardSummary } from '@/api/schemas';
import { Badge, Breadcrumb, Button, Check, Field, IconBox, Note, PageTitle, SectionHead, Skeleton, StickyFooter } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { Select } from '@/components/ui/Select';
import { CHAR_LIMITS } from '@/lib/coverLetter';
import { ymdhm } from '@/lib/format';
import { cardKindLabel, coverLetterQuestionLabel } from '@/lib/labels';
import { track } from '@/lib/track';
import { useActionGate } from '@/lib/useActionGate';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { CoverLetterUnavailable, NeedsConfirmedCards } from './shared';

const NONE = 'none';

/** 자소서 초안 만들기 — 지원할 공고·문항·사용할 확정 카드를 고르면, 그 카드 문장으로 초안을 만든다. */
export function CoverLetterSetupPage() {
  useDocumentTitle('자소서 초안');
  const [sp] = useSearchParams();
  const nav = useNavigate();
  const matches = useMatches();
  const cards = useCards();
  const letters = useCoverLetters();
  const create = useCreateCoverLetter();
  const actions = useActionGate();
  const [matchChoice, setMatchChoice] = useState(sp.get('match') ?? NONE);
  const parsed = CoverLetterQuestion.safeParse(sp.get('question'));
  const [question, setQuestion] = useState<CoverLetterQuestion>(parsed.success ? parsed.data : 'MOTIVATION');
  const limitParam = sp.get('limit'); // 다시 만들기 링크가 이어 주는 이전 글자 수 제한
  const [limit, setLimit] = useState(CHAR_LIMITS.some((value) => String(value) === limitParam) ? limitParam! : NONE);
  const [picked, setPicked] = useState<string[] | null>(null); // null = 지원할 공고에 맞춰 자동 추천
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  // 목록에 없는 공고(마감·사라짐·필수·우대 조건 없음)로 들어오면 '지원 공고 없음'으로 본다.
  const matchId = matches.data?.some((match) => match.id === matchChoice) ? matchChoice : NONE;
  const detail = useMatch(matchId === NONE ? '' : matchId);
  const confirmed = useMemo(() => (cards.data ?? []).filter((card) => card.status === 'CONFIRMED'), [cards.data]);
  // 등급을 세는 필수·우대 조건의 근거 카드만 추천한다. 담당 업무는 '하게 될 일'이라 추천 기준에 넣지 않는다.
  const supporting = useMemo(() => new Set((detail.data?.conditions ?? [])
    .filter((condition) => condition.type !== 'RESPONSIBILITY')
    .flatMap((condition) => condition.supports.map((support) => support.cardId))), [detail.data]);
  const suggested = useMemo(() => (matchId === NONE ? confirmed.map((card) => card.id) : confirmed.filter((card) => supporting.has(card.id)).map((card) => card.id)), [matchId, confirmed, supporting]);
  const selected = (picked ?? suggested).filter((id) => confirmed.some((card) => card.id === id));

  if (!matchSupported()) return <CoverLetterUnavailable />;
  const toggle = (id: string) => setPicked(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);
  const submit = () => void actions.run(async () => {
    if (!selected.length) return;
    const charLimit = limit === NONE ? null : Number(limit);
    const letter = await create.mutateAsync({ matchId: matchId === NONE ? null : matchId, question, cardIds: selected, charLimit });
    track('cover_letter_created', { letterId: letter.id, cards: selected.length, withTarget: matchId !== NONE, charLimit });
    if (mounted.current) nav(`/cover-letter/${letter.id}`); // 응답을 기다리는 동안 다른 화면으로 갔다면 끌고 오지 않는다
  });
  const crumbs = [{ label: '경험정리/홈', to: '/' }, { label: '자소서 초안' }];

  if (cards.isPending || matches.isPending) return <main className="main"><Breadcrumb items={crumbs} /><Skeleton h={40} w={280} /><Skeleton h={260} /></main>;
  if (cards.isError) return <main className="main"><Breadcrumb items={crumbs} /><QueryFailure error={cards.error} retry={() => cards.refetch()} pending={cards.isFetching} /></main>;

  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={crumbs} />
      <PageTitle>자소서 초안 만들기</PageTitle>
      <Note strong="확정한 카드의 문장으로만 만들어요" tone="inset">
        새로운 사실이나 수치는 만들지 않아요. 카드의 가리기 규칙은 그대로 적용하고, 문장을 이어 주는 AI 문장은 따로 표시해요.
      </Note>
      {confirmed.length === 0 ? <NeedsConfirmedCards /> : (
        <>
          {/* 공고 목록을 못 받아도 일반 자소서는 만들 수 있다 — 매칭 실패가 초안 만들기를 막지 않는다. */}
          {matches.isError && <QueryFailure error={matches.error} retry={() => matches.refetch()} pending={matches.isFetching} />}
          <div className="card stack" style={{ gap: 18, padding: '20px' }}>
            <Field label="지원할 공고" hint="고르면 그 공고의 필수·우대 조건을 뒷받침하는 카드를 먼저 골라 드려요">
              <Select value={matchId} label="지원할 공고" onChange={(value) => { setMatchChoice(value); setPicked(null); }}
                options={[{ value: NONE, label: '지원 공고 없음 (일반 자소서)' }, ...(matches.data ?? []).map((match) => ({ value: match.id, label: `${match.company} · ${match.role}` }))]} />
            </Field>
            <Field label="문항" hint={question === 'MOTIVATION' ? '지원 동기는 내가 가장 잘 아는 이야기라 AI가 대신 쓰지 않고, 직접 쓸 빈 칸으로 남겨요' : undefined}>
              <Select value={question} label="문항" onChange={setQuestion}
                options={CoverLetterQuestion.options.map((value) => ({ value, label: coverLetterQuestionLabel[value] }))} />
            </Field>
            <Field label="글자 수 제한" hint="공백 포함이에요. 카드 문장을 그대로 쓰기 때문에, 제한을 넘으면 초안에서 알려 드려요">
              <Select value={limit} label="글자 수 제한" onChange={setLimit}
                options={[{ value: NONE, label: '제한 없음' }, ...CHAR_LIMITS.map((value) => ({ value: String(value), label: `${value.toLocaleString()}자` }))]} />
            </Field>
          </div>
          <section className="stack" style={{ gap: 10 }}>
            <SectionHead label="사용할 카드" count={selected.length} />
            {matchId !== NONE && detail.isError && <QueryFailure error={detail.error} retry={() => detail.refetch()} pending={detail.isFetching} />}
            {matchId !== NONE && detail.isSuccess && suggested.length === 0 && (
              <Note strong="이 공고의 조건을 뒷받침하는 확정 카드가 아직 없어요" tone="caution">쓸 카드를 직접 골라도 되지만, 초안에는 근거로 이어지지 않는 조건이 따로 표시돼요.</Note>
            )}
            <div className="record-list" role="group" aria-label="사용할 카드">
              {confirmed.map((card) => <CardPickRow key={card.id} card={card} on={selected.includes(card.id)} supports={supporting.has(card.id)} onToggle={() => toggle(card.id)} />)}
            </div>
          </section>
        </>
      )}
      {(letters.data?.length ?? 0) > 0 && (
        <section className="stack" style={{ gap: 10 }}>
          <SectionHead label="만든 초안" count={letters.data!.length} />
          <div className="record-list" aria-label="만든 초안">
            {letters.data!.map((letter) => (
              <Link key={letter.id} to={`/cover-letter/${letter.id}`} className="card row repo-row">
                <IconBox icon={FileText} size={34} />
                <div className="stack grow" style={{ gap: 5, textAlign: 'left' }}>
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    <span className="w-600" style={{ fontSize: 14 }}>{letter.company ?? '지원 공고 없음'}</span>
                    <span className="t-12 c-3">{coverLetterQuestionLabel[letter.question]}</span>
                    {letter.edited && <Badge kind="DRAFT">직접 고침</Badge>}
                  </div>
                  <span className="t-12 c-3">수정 {ymdhm(letter.updatedAt)}</span>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}
      <StickyFooter strong={`카드 ${selected.length}장으로 만들어요`} sub={confirmed.length ? undefined : '확정한 카드가 필요해요'}>
        <Link to="/match" className="btn btn--outline">공고 매칭 보기</Link>
        <Button size="lg" disabled={!selected.length || confirmed.length === 0} loading={actions.running} onClick={submit}>초안 만들기</Button>
      </StickyFooter>
    </main>
  );
}

function CardPickRow({ card, on, supports, onToggle }: { card: CardSummary; on: boolean; supports: boolean; onToggle: () => void }) {
  return (
    <div className={`card row repo-row ${on ? 'card--selected' : ''}`}
      onClick={(event) => { if ((event.target as HTMLElement).closest('button, a, input')) return; onToggle(); }}>
      <Check checked={on} onChange={onToggle} label={`${card.title} 사용`} />
      <div className="stack grow" style={{ gap: 5, textAlign: 'left' }}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <span className="w-600" style={{ fontSize: 14 }}>{card.title}</span>
          <Badge kind="NEUTRAL">{cardKindLabel[card.kind]}</Badge>
          {supports && <Badge kind="PR">이 공고 조건 근거</Badge>}
        </div>
        <span className="repo-row__stats t-12 c-3">
          <span>근거 {(card.evidenceCount ?? 0) + (card.userStatedCount ?? 0)}건</span>
          {card.period && <span>{card.period}</span>}
        </span>
      </div>
    </div>
  );
}
