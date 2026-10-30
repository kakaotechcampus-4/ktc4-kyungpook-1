import '@/styles/profile.css';
import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { ContactRound } from 'lucide-react';
import { profileSupported } from '@/api/capabilities';
import { useProfile, useSkipProfile } from '@/api/profile';
import { Button, IconBox } from '@/components/ui';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';

/**
 * 홈 첫 방문 안내 — 한 번도 저장하지 않았을 때(`savedAt` 이 null)만 보인다.
 * '건너뛰기'는 빈 목록 네 개를 저장한다(BE 계약). 그러면 `savedAt` 이 생겨 다른 브라우저·기기에서도 다시 뜨지 않는다.
 * 주 흐름을 막지 않는다: 마법사로 강제하지 않고, 불러오는 중·실패·실서버(미지원)에는 아무것도 그리지 않는다.
 */
export function ProfilePrompt() {
  const profile = useProfile();
  const skip = useSkipProfile();
  const titleId = useId();
  const [failed, setFailed] = useState(false);

  if (!profileSupported() || !profile.isSuccess || profile.data.savedAt !== null) return null;

  const dismiss = async () => {
    setFailed(false);
    try {
      await skip.mutateAsync();
    } catch {
      setFailed(true); // 저장이 안 됐으니 안내는 그대로 두고, 다시 누를 수 있게 한다
      return;
    }
    track('profile_prompt_dismissed');
    toast('마이페이지에서 언제든 인적사항을 입력할 수 있어요.');
    // 닫은 버튼이 사라지므로 초점을 페이지 제목으로 옮긴다 — 키보드·스크린리더 사용자가 길을 잃지 않게
    const heading = document.querySelector<HTMLElement>('main h1');
    if (heading) { heading.tabIndex = -1; heading.focus({ preventScroll: true }); }
  };

  return (
    <section className="profile-prompt" aria-labelledby={titleId}>
      <IconBox icon={ContactRound} size={40} />
      <div className="profile-prompt__text">
        <h2 id={titleId}>매칭에 쓸 인적사항을 입력해 주세요</h2>
        <p>학력·경력·어학·자격증을 적어 두면 공고의 지원 자격을 비교할 때 써요. 건너뛰어도 다른 기능은 그대로 쓸 수 있어요.</p>
        {failed && <p className="profile-error" role="alert">건너뛰지 못했어요. 잠시 뒤 다시 시도해 주세요.</p>}
      </div>
      <div className="profile-prompt__actions">
        <Link to="/settings/profile" className="btn btn--primary" onClick={() => track('profile_prompt_accepted')}>입력하기</Link>
        <Button variant="text" loading={skip.isPending} aria-label="인적사항 입력 건너뛰기" onClick={() => void dismiss()}>건너뛰기</Button>
      </div>
    </section>
  );
}
