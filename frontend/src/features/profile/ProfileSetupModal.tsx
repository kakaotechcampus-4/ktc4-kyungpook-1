import '@/styles/profile.css';
import { useState } from 'react';
import { profileSupported } from '@/api/capabilities';
import { useProfile, type Profile } from '@/api/profile';
import { Button, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { type SectionKey } from '@/lib/profileForm';
import { track } from '@/lib/track';
import { CertificationQuick, EDU_LEVEL_ID, EducationQuick, InterestPicker, LanguageQuick, SectionEditor } from './ProfileSections';
import { useProfileFormState, type Scope } from './useProfileFormState';

/**
 * 단계 순서(#656). 경력·어학·자격증은 '없음'으로 넘길 수 있고, 학력은 꼭 고른다 — 매칭이 학력 조건을 볼 때 쓴다.
 * 학력·어학·자격증은 타일로 빠르게 받고(학교 이름·발급 기관 같은 세부는 마이페이지 폼에서), 경력만 줄 입력이다 — 매칭이 입사·퇴사 월로 경력 개월을 센다.
 */
const STEPS: readonly Scope[] = ['interests', 'careers', 'educations', 'languages', 'certifications'];
const TITLE: Record<Scope, string> = { interests: '관심 분야', careers: '경력', educations: '학력', languages: '어학', certifications: '자격증' };
const SKIPPABLE: readonly SectionKey[] = ['careers', 'languages', 'certifications'];

/**
 * 처음 입력 창 — 저장된 관심 분야가 없으면(처음 들어온 사람, 전에 건너뛴 사람) 어느 화면 위에든 띄우고, 저장해야 닫힌다.
 * 관심 분야가 있다 = 다 입력했다. 이미 저장된 학력·경력은 미리 채워 둔다. 실서버에서 기능이 꺼져 있으면 아무것도 그리지도, 요청하지도 않는다.
 */
export function ProfileSetupModal() {
  const profile = useProfile();
  if (!profileSupported() || !profile.isSuccess || profile.data.interests.length > 0) return null;
  return <SetupDialog initial={profile.data} />;
}

function SetupDialog({ initial }: { initial: Profile }) {
  const state = useProfileFormState(initial, { guard: false });
  const [step, setStep] = useState(0);
  const [askedEducation, setAskedEducation] = useState(false);
  const scope = STEPS[step];
  const last = step === STEPS.length - 1;
  const empty = scope !== 'interests' && state.form[scope].length === 0;
  const needsEducation = scope === 'educations' && askedEducation && state.form.educations.length === 0;

  const go = (index: number) => { setAskedEducation(false); setStep(index); };
  const next = async () => {
    state.attempt(scope);
    if (scope === 'educations' && state.form.educations.length === 0) { setAskedEducation(true); state.focus(EDU_LEVEL_ID); return; }
    const firstError = state.firstErrorId(scope);
    if (firstError) { state.focus(firstError); return; }
    if (!last) { go(step + 1); return; }
    const saved = await state.submit();
    if (saved) { track('profile_setup_completed', { interestCount: saved.interests.length }); return; }
    // 앞 단계로 돌아가 고친 뒤 검사에 걸리면 그 단계로 보낸다. 서버 실패는 아래 안내로 보이고 이 자리에서 다시 누른다
    const broken = STEPS.findIndex((candidate) => state.firstErrorId(candidate) !== null);
    if (broken >= 0 && broken !== step) go(broken);
  };

  const actions = (
    <>
      {step > 0 && <Button variant="text" disabled={state.saving} onClick={() => go(step - 1)}>이전</Button>}
      {empty && SKIPPABLE.includes(scope as SectionKey)
        ? <Button loading={state.saving} onClick={() => void next()}>{TITLE[scope]} 없음</Button>
        : <Button loading={state.saving} onClick={() => void next()}>{last ? '저장하고 시작하기' : '다음'}</Button>}
    </>
  );

  return (
    <Modal title={`인적사항 입력 — ${TITLE[scope]}`} sub={`${step + 1}/${STEPS.length} 단계 · 다 입력해야 Gitory 를 쓸 수 있어요`} width={720} dismissible={false} onClose={() => undefined}
      footer={{ strong: last ? '저장하면 바로 시작할 수 있어요' : `다음은 ${TITLE[STEPS[step + 1]]}`, sub: empty && SKIPPABLE.includes(scope as SectionKey) ? '해당 사항이 없으면 그대로 넘겨 주세요' : undefined, actions }}>
      <ol className="setup-steps" aria-label="입력 단계">
        {STEPS.map((candidate, index) => (
          <li key={candidate} className={index < step ? 'is-done' : undefined} aria-current={index === step ? 'step' : undefined}><span>{TITLE[candidate]}</span></li>
        ))}
      </ol>
      {state.view && <Note strong={state.view.title} tone="danger">{state.view.message}</Note>}
      {needsEducation && <Note strong="학력이 필요해요" tone="danger">학력을 1개 이상 입력해 주세요</Note>}
      <fieldset className="profile-fields" disabled={state.saving}>
        <legend className="sr-only">{TITLE[scope]} 입력</legend>
        {scope === 'interests' ? <InterestPicker value={state.form.interests} onChange={state.setInterests} error={state.errors.interests} />
          : scope === 'educations' ? <EducationQuick state={state} />
          : scope === 'languages' ? <LanguageQuick state={state} />
          : scope === 'certifications' ? <CertificationQuick state={state} />
          : <SectionEditor section={scope} state={state} />}
      </fieldset>
    </Modal>
  );
}
