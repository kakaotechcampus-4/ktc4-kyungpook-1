import '@/styles/profile.css';
import { Link } from 'react-router-dom';
import type { Profile } from '@/api/profile';
import { Button, Note, StickyFooter } from '@/components/ui';
import { UnsavedChangesDialog } from '@/components/SaveStatus';
import { isDemo } from '@/mock/browser';
import { InterestPicker, SectionEditor } from './ProfileSections';
import { SAVE_ID, useProfileFormState } from './useProfileFormState';

/**
 * 인적사항 입력 — 관심 분야와 네 영역(학력·경력·어학·자격증)을 한 번에 고치고 [저장] 으로 통째로 보낸다(PUT).
 * 자동 저장을 쓰지 않는 이유: 줄이 덜 채워진 채로는 서버가 받지 않는 문서라 중간 저장이 성립하지 않고,
 * 개인정보를 입력이 멈추는 순간마다 보내지 않는 편이 낫다. 대신 고친 채로 나가면 막는다(useUnsavedChanges).
 * 값은 지표·로그·URL 어디에도 넣지 않는다 — track 에는 개수만 간다. 칸·검증·저장은 처음 입력 창(ProfileSetupModal)과 같은 훅을 쓴다.
 */
export function ProfileForm({ initial }: { initial: Profile }) {
  const state = useProfileFormState(initial);
  const { errorCount, view, saving, dirty, saveError, statusText } = state;
  const canSave = dirty || Boolean(saveError);
  return (
    <>
      <Note strong="이렇게 쓰여요" tone="inset">
        관심 분야는 공고 매칭이 공고를 고를 때 쓰고, 학력·경력·어학·자격증은 지원 자격 조건을 비교할 때 써요. 자소서를 AI 로 쓸 때도 이 정보를 읽을 수 있어요. 경험 카드 문장은 이 정보로 만들지 않아요. 학력·경력 같은 개인 정보라 분석 지표와 주소에는 남기지 않아요.{isDemo && ' 샘플 모드라 이 브라우저 탭에만 저장되고, 탭을 닫으면 사라져요.'}
      </Note>

      {errorCount > 0 && <Note strong="입력을 확인해 주세요" tone="danger">{errorCount}곳을 고치면 저장할 수 있어요.</Note>}
      {view && errorCount === 0 && <Note strong={view.title} tone="danger">{view.message}</Note>}

      <fieldset className="profile-fields" disabled={saving}>
        <legend className="sr-only">인적사항 입력</legend>
        <InterestPicker value={state.form.interests} onChange={state.setInterests} error={state.errors.interests} />
        <SectionEditor section="educations" state={state} />
        <SectionEditor section="careers" state={state} />
        <SectionEditor section="languages" state={state} />
        <SectionEditor section="certifications" state={state} />
      </fieldset>

      <p className="sr-only" role="status" aria-live="polite">{statusText}</p>
      <StickyFooter strong={statusText}>
        <Link to="/settings" className="btn btn--text">마이페이지로</Link>
        <Button id={SAVE_ID} size="lg" loading={saving} className={!canSave ? 'btn--disabled' : undefined} aria-disabled={!canSave ? true : undefined}
          onClick={() => { if (canSave) void state.submit(); }}>{saveError ? '다시 저장' : '저장'}</Button>
      </StickyFooter>
      <UnsavedChangesDialog blocker={state.guard.blocker} saving={saving} flush={async () => (await state.submit()) !== null}
        failureMessage={errorCount > 0 ? '입력을 확인해야 저장할 수 있어요. 계속 작성을 눌러 안내가 붙은 칸을 고쳐 주세요.' : undefined} />
    </>
  );
}
