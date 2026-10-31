import { useId, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';
import { ACTIVITY_LIMITS, useActivity, useCreateActivity, useUpdateActivity, type Activity } from '@/api/activities';
import { ACTIVITY_TYPES, type ActivityType } from '@/api/schemas';
import { Breadcrumb, Button, Field, Input, Note, PageTitle, Skeleton } from '@/components/ui';
import { Select, type SelectOption } from '@/components/ui/Select';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { activityTypeLabel } from '@/lib/activityLabels';
import { emptyActivityForm, formFromActivity, toCreateBody, toPatchBody, validateActivityForm, type ActivityField, type ActivityFormValues } from '@/lib/activityForm';
import { folderPath } from '@/lib/cardFolders';
import { toast } from '@/lib/toast';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import './Activities.css';

const TYPE_OPTIONS: readonly SelectOption<ActivityType | ''>[] = [
  { value: '', label: '종류를 골라 주세요' },
  ...ACTIVITY_TYPES.map((value) => ({ value, label: activityTypeLabel[value] })),
];
const FIELD_ORDER: ActivityField[] = ['name', 'type', 'startMonth', 'endMonth', 'role'];

/** 활동 만들기 */
export function ActivityNewPage() {
  useDocumentTitle('활동 만들기');
  return (
    <main className="main main--tight activities-page">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: '활동', to: '/activities' }, { label: '만들기' }]} />
      <PageTitle lead="동아리, 인턴, 공모전처럼 레포 밖에서 한 경험이에요. 만든 뒤 그 안에서 경험 카드를 쓸 수 있어요.">활동 만들기</PageTitle>
      <ActivityForm />
    </main>
  );
}

/** 활동 고치기. 활동 하나를 읽는 API 가 없어 목록에서 id 로 찾는다 */
export function ActivityEditPage() {
  const { activityId = '' } = useParams();
  const { activity, isPending, isError, error, refetch, isFetching } = useActivity(activityId);
  useDocumentTitle(activity ? `${activity.name} 고치기` : '활동 고치기');
  return (
    <main className="main main--tight activities-page">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: '활동', to: '/activities' }, { label: activity?.name ?? '활동 고치기' }]} />
      <PageTitle>활동 고치기</PageTitle>
      {isPending && <div className="stack" style={{ gap: 12 }} aria-busy="true"><Skeleton h={48} /><Skeleton h={240} /></div>}
      {isError && <QueryFailure error={error} retry={() => refetch()} pending={isFetching} />}
      {!isPending && !isError && !activity && (
        <div className="folder-missing">
          <p className="c-2">이미 삭제됐거나 볼 수 없는 활동이에요.</p>
          <Link to="/activities" className="btn btn--outline">활동 목록</Link>
        </div>
      )}
      {activity && <ActivityForm key={activity.id} activity={activity} />}
    </main>
  );
}

/**
 * 입력 한 벌. `activity` 가 있으면 고치기다 — 바뀐 칸만 보내고 바뀐 게 없으면 저장 버튼이 잠긴다(서버는 빈 PATCH 를 400 으로 막는다).
 * 만들기는 열 때 한 번 만든 Idempotency-Key 를 쓴다. 응답을 못 받고 다시 눌러도 활동은 하나만 생긴다.
 * 검사 안내는 저장을 눌러 본 뒤부터 보이고, 그 뒤로는 고치는 즉시 갱신된다. 서버가 400 으로 막아도 서버 문구는 보이지 않는다.
 */
function ActivityForm({ activity }: { activity?: Activity }) {
  const nav = useNavigate();
  const create = useCreateActivity();
  const update = useUpdateActivity(activity?.id ?? '');
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [values, setValues] = useState<ActivityFormValues>(() => activity ? formFromActivity(activity) : emptyActivityForm());
  const [attempted, setAttempted] = useState(false);
  const [saveError, setSaveError] = useState<unknown>(null);
  const root = useRef<HTMLFormElement>(null);
  const baseId = useId();
  const id = (field: ActivityField) => `${baseId}-${field}`;

  const errors = attempted ? validateActivityForm(values) : {};
  const patch = activity ? toPatchBody(values, activity) : null;
  const unchanged = patch !== null && Object.keys(patch).length === 0;
  const saving = create.isPending || update.isPending;
  const edit = (next: Partial<ActivityFormValues>) => { setSaveError(null); setValues((current) => ({ ...current, ...next })); };

  const focusFirst = (found: ReturnType<typeof validateActivityForm>) => {
    const field = FIELD_ORDER.find((name) => found[name]);
    if (!field) return;
    const target = root.current?.querySelector<HTMLElement>(`[id="${id(field)}"]`);
    (target?.matches('input,button,textarea') ? target : target?.querySelector<HTMLElement>('button'))?.focus();
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (saving) return;
    const found = validateActivityForm(values);
    setAttempted(true);
    if (Object.keys(found).length > 0) { focusFirst(found); return; }
    try {
      if (activity) {
        if (patch && Object.keys(patch).length > 0) await update.mutateAsync(patch);
        toast('활동을 고쳤어요', { tone: 'success' });
        nav(folderPath.activity(activity.id));
      } else {
        const created = await create.mutateAsync({ body: toCreateBody(values), idempotencyKey });
        toast('활동을 만들었어요', { tone: 'success' });
        nav(folderPath.activity(created.id));
      }
    } catch (error) {
      setSaveError(error);
    }
  };

  const view = saveError ? (saveError instanceof ApiError && saveError.status === 400 ? { title: '입력을 확인해 주세요', message: '입력한 내용을 서버가 받아들이지 않았어요. 칸마다 안내를 확인한 뒤 다시 저장해 주세요.' } : errorView(saveError, 'write')) : null;
  const missing = saveError instanceof ApiError && saveError.status === 404;
  const cell = (field: ActivityField, label: string, control: (props: Record<string, unknown>) => ReactNode, opts: { full?: boolean; optional?: boolean } = {}) => {
    const message = errors[field];
    const shown = opts.optional ? `${label} (선택)` : label;
    return (
      <div className={`activity-cell ${opts.full ? 'activity-cell--full' : ''} ${message ? 'activity-cell--invalid' : ''}`}>
        <Field label={shown}>{control({ id: id(field), 'aria-label': shown, 'aria-invalid': message ? true : undefined, 'aria-describedby': message ? `${id(field)}-error` : undefined })}</Field>
        {message && <p id={`${id(field)}-error`} className="activity-error">{message}</p>}
      </div>
    );
  };

  return (
    <form ref={root} className="activity-form" onSubmit={submit} noValidate>
      {view && <Note strong={view.title} tone="danger">{missing ? '이미 삭제된 활동이에요. 활동 목록에서 다시 확인해 주세요.' : view.message}</Note>}
      <fieldset className="card activity-form__card" disabled={saving} style={{ margin: 0, minWidth: 0 }}>
        <legend className="sr-only">활동 정보</legend>
        {cell('name', '활동 이름', (props) => (
          <Input {...props} maxLength={ACTIVITY_LIMITS.NAME} autoFocus={!activity} autoComplete="off" value={values.name} placeholder="예) 교내 개발 동아리" onChange={(e) => edit({ name: e.target.value })} />
        ), { full: true })}
        {cell('type', '종류', (props) => (
          <div id={props.id as string}>
            <Select className="select-trigger--field" value={values.type} label="종류" options={TYPE_OPTIONS} invalid={Boolean(props['aria-invalid'])} describedBy={props['aria-describedby'] as string | undefined} onChange={(type) => edit({ type })} />
          </div>
        ), { full: true })}
        {cell('startMonth', '시작한 달', (props) => (
          <Input {...props} type="month" placeholder="2025-03" value={values.startMonth} onChange={(e) => edit({ startMonth: e.target.value })} />
        ))}
        {cell('endMonth', '끝난 달', (props) => (
          <Input {...props} type="month" placeholder="2025-12" disabled={values.ongoing} value={values.ongoing ? '' : values.endMonth} onChange={(e) => edit({ endMonth: e.target.value })} />
        ))}
        <label className="activity-check activity-cell--full">
          <input type="checkbox" checked={values.ongoing} onChange={(e) => edit({ ongoing: e.target.checked })} />
          진행 중이에요
        </label>
        {cell('role', '맡은 역할', (props) => (
          <Input {...props} maxLength={ACTIVITY_LIMITS.ROLE} autoComplete="off" value={values.role} placeholder="예) 백엔드 파트장" onChange={(e) => edit({ role: e.target.value })} />
        ), { full: true, optional: true })}
      </fieldset>
      <div className="activity-form__actions">
        <Button type="submit" size="lg" loading={saving} disabled={unchanged}>{activity ? '고친 내용 저장' : '활동 만들기'}</Button>
        <Link to={activity ? folderPath.activity(activity.id) : '/activities'} className="btn btn--text">취소</Link>
        {unchanged && <span className="t-12 c-2">바뀐 내용이 없어요</span>}
      </div>
    </form>
  );
}
