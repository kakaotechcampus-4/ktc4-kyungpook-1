import '@/styles/profile.css';
import { profileSupported } from '@/api/capabilities';
import { useProfile, type Profile } from '@/api/profile';
import { Button, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { track } from '@/lib/track';
import { InterestPicker, MailSection } from './ProfileSections';
import { useProfileFormState } from './useProfileFormState';

/**
 * 처음 입력 창 — 저장된 관심 분야가 없으면(처음 들어온 사람, 전에 건너뛴 사람) 어느 화면 위에든 띄우고, 저장해야 닫힌다.
 * 실서버에서 기능이 꺼져 있으면 아무것도 그리지도, 요청하지도 않는다.
 */
export function ProfileSetupModal() {
  const profile = useProfile();
  if (!profileSupported() || !profile.isSuccess || profile.data.interests.length > 0) return null;
  return <SetupDialog initial={profile.data} />;
}

function SetupDialog({ initial }: { initial: Profile }) {
  const state = useProfileFormState(initial, { guard: false });

  const save = async () => {
    const saved = await state.submit();
    if (saved) track('profile_setup_completed', { interestCount: saved.interests.length });
  };

  return (
    <Modal title="관심 분야" width={720} dismissible={false} onClose={() => undefined}
      footer={{ strong: '금방 끝나요', actions: <Button loading={state.saving} onClick={() => void save()}>저장하고 시작하기</Button> }}>
      {state.view && <Note strong={state.view.title} tone="danger">{state.view.message}</Note>}
      <fieldset className="profile-fields" disabled={state.saving}>
        <legend className="sr-only">관심 분야 입력</legend>
        <InterestPicker value={state.form.interests} onChange={state.setInterests} error={state.errors.interests} />
        <MailSection state={state} />
      </fieldset>
    </Modal>
  );
}
