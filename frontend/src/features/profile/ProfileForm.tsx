import '@/styles/profile.css';
import type { Profile } from '@/api/profile';
import { Button, ButtonLink, Note, StickyFooter } from '@/components/ui';
import { UnsavedChangesDialog } from '@/components/SaveStatus';
import { isDemo } from '@/mock/browser';
import { InterestPicker, MailSection } from './ProfileSections';
import { SAVE_ID, useProfileFormState } from './useProfileFormState';

/**
 * 인적사항 입력 — 관심 분야와 메일 알림을 고치고 [저장] 으로 통째로 보낸다(PUT).
 * 자동 저장을 쓰지 않는 이유: 개인정보를 입력이 멈추는 순간마다 보내지 않는 편이 낫다. 대신 고친 채로 나가면 막는다(useUnsavedChanges).
 * 값은 지표·로그·URL 어디에도 넣지 않는다 — track 에는 개수만 간다. 칸·검증·저장은 처음 입력 창(ProfileSetupModal)과 같은 훅을 쓴다.
 */
export function ProfileForm({ initial }: { initial: Profile }) {
  const state = useProfileFormState(initial);
  const { errorCount, view, saving, dirty, saveError, statusText } = state;
  const canSave = dirty || Boolean(saveError);
  return (
    <>
      {/* 처음부터 펼쳐 두면 칸보다 설명이 먼저 읽혀, 궁금할 때 여는 줄로 접는다(#805) */}
      <details className="profile-usage">
        <summary>이렇게 쓰여요</summary>
        <p>관심 분야는 공고 매칭이 공고를 고를 때 써요. 다른 개인 정보는 받지 않아요.{isDemo && ' 샘플 모드라 이 브라우저 탭에만 저장되고, 탭을 닫으면 사라져요.'}</p>
      </details>

      {errorCount > 0 && <Note strong="입력을 확인해 주세요" tone="danger">{errorCount}곳을 고치면 저장할 수 있어요.</Note>}
      {view && errorCount === 0 && <Note strong={view.title} tone="danger">{view.message}</Note>}

      <fieldset className="profile-fields" disabled={saving}>
        <legend className="sr-only">인적사항 입력</legend>
        <InterestPicker value={state.form.interests} onChange={state.setInterests} error={state.errors.interests} />
        <MailSection state={state} />
      </fieldset>

      <p className="sr-only" role="status" aria-live="polite">{statusText}</p>
      <StickyFooter strong={statusText}>
        <ButtonLink to="/settings" variant="text">마이페이지로</ButtonLink>
        <Button id={SAVE_ID} size="lg" loading={saving} className={!canSave ? 'btn--disabled' : undefined} aria-disabled={!canSave ? true : undefined}
          onClick={() => { if (canSave) void state.submit(); }}>{saveError ? '다시 저장' : '저장'}</Button>
      </StickyFooter>
      <UnsavedChangesDialog blocker={state.guard.blocker} saving={saving} flush={async () => (await state.submit()) !== null}
        failureMessage={errorCount > 0 ? '입력을 확인해야 저장할 수 있어요. 계속 작성을 눌러 안내가 붙은 칸을 고쳐 주세요.' : undefined} />
    </>
  );
}
