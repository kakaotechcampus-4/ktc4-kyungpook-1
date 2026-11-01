/**
 * 활동 만들기·고치기 입력 검사와 요청 만들기. 규칙은 계약(2026-10-28-EXPERIENCE_CARDS.md 1절)과 openapi ActivityRequest 를 그대로 옮겼다.
 * 서버 400 의 message 는 화면에 보이지 않으므로(서버 문구 비노출) 칸별 안내는 여기서 먼저 만든다.
 */
import { ACTIVITY_LIMITS, isMonth, type Activity, type ActivityCreateBody, type ActivityPatchBody } from '@/api/activities';
import { isBlankCardText, type ActivityType } from '@/api/schemas';

export type ActivityFormValues = {
  name: string;
  /** 고르기 전에는 '' */
  type: ActivityType | '';
  startMonth: string;
  endMonth: string;
  /** 켜면 끝난 달을 비운다(진행 중) */
  ongoing: boolean;
  role: string;
};
export type ActivityField = 'name' | 'type' | 'startMonth' | 'endMonth' | 'role';
export type ActivityFormErrors = Partial<Record<ActivityField, string>>;

export const emptyActivityForm = (): ActivityFormValues => ({ name: '', type: '', startMonth: '', endMonth: '', ongoing: false, role: '' });
export const formFromActivity = (activity: Activity): ActivityFormValues => ({
  name: activity.name, type: activity.type ?? '', startMonth: activity.startMonth,
  endMonth: activity.endMonth ?? '', ongoing: activity.endMonth === null, role: activity.role ?? '',
});

const hasNul = (text: string) => text.includes('\u0000');

/** 칸마다 안내 하나. 모두 통과하면 `{}` */
export function validateActivityForm(values: ActivityFormValues): ActivityFormErrors {
  const errors: ActivityFormErrors = {};
  const name = values.name.trim();
  if (isBlankCardText(name)) errors.name = '활동 이름을 적어 주세요.';
  else if (name.length > ACTIVITY_LIMITS.NAME) errors.name = `활동 이름은 ${ACTIVITY_LIMITS.NAME}자까지 쓸 수 있어요.`;
  else if (hasNul(name)) errors.name = '쓸 수 없는 글자가 들어 있어요.';

  if (values.type === '') errors.type = '종류를 골라 주세요.';

  if (values.startMonth === '') errors.startMonth = '시작한 달을 골라 주세요.';
  else if (!isMonth(values.startMonth)) errors.startMonth = '시작한 달은 2025-03 처럼 적어 주세요.';

  if (!values.ongoing) {
    if (values.endMonth === '') errors.endMonth = '끝난 달을 골라 주세요. 아직 하고 있다면 "진행 중이에요"를 켜 주세요.';
    else if (!isMonth(values.endMonth)) errors.endMonth = '끝난 달은 2025-12 처럼 적어 주세요.';
    // YYYY-MM 은 글자 순서가 달 순서와 같다
    else if (!errors.startMonth && values.endMonth < values.startMonth) errors.endMonth = '끝난 달이 시작한 달보다 앞설 수 없어요.';
  }

  const role = values.role.trim();
  if (role.length > ACTIVITY_LIMITS.ROLE) errors.role = `역할은 ${ACTIVITY_LIMITS.ROLE}자까지 쓸 수 있어요.`;
  else if (hasNul(role)) errors.role = '쓸 수 없는 글자가 들어 있어요.';
  return errors;
}

/** 검사를 통과한 값으로 만든다. 끝난 달·역할이 없으면 칸을 아예 보내지 않는다 */
export function toCreateBody(values: ActivityFormValues): ActivityCreateBody {
  const role = values.role.trim();
  return {
    name: values.name.trim(), type: values.type as ActivityType, startMonth: values.startMonth,
    ...(values.ongoing ? {} : { endMonth: values.endMonth }),
    ...(role ? { role } : {}),
  };
}

/**
 * 바뀐 칸만 보낸다. 서버는 보낸 칸만 고치고 바꿀 것이 없으면 400 이다 → 아무것도 안 바뀌었으면 `{}`.
 * 진행 중으로 바꾸거나 역할을 지울 때는 빈 글(`''`)을 보낸다. 생략하면 그대로 두라는 뜻이기 때문이다.
 */
export function toPatchBody(values: ActivityFormValues, original: Activity): ActivityPatchBody {
  const patch: ActivityPatchBody = {};
  const name = values.name.trim();
  if (name !== original.name) patch.name = name;
  if (values.type !== '' && values.type !== original.type) patch.type = values.type;
  if (values.startMonth !== original.startMonth) patch.startMonth = values.startMonth;
  if (values.ongoing) { if (original.endMonth !== null) patch.endMonth = ''; }
  else if (values.endMonth !== original.endMonth) patch.endMonth = values.endMonth;
  const role = values.role.trim();
  if (role !== (original.role ?? '')) patch.role = role;
  return patch;
}
