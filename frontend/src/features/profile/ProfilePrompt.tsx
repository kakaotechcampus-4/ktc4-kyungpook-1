import '@/styles/profile.css';
import { useId, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ContactRound } from 'lucide-react';
import { profileSupported } from '@/api/capabilities';
import { isProfileEmpty, useProfile } from '@/api/profile';
import { useMe } from '@/api/queries';
import { Button, IconBox } from '@/components/ui';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';

const DISMISSED_KEY = 'gitory.profile-prompt.dismissed';
// 사용자별로 구분한다 — 같은 브라우저에서 계정을 바꿔도 다른 사람의 "나중에"를 물려받지 않게. 값은 개인정보가 아닌 '닫았다'는 표시뿐이다
const dismissedKey = (userId: string) => `${DISMISSED_KEY}.${userId}`;
const wasDismissed = (key: string) => { try { return localStorage.getItem(key) === '1'; } catch { return false; } };
const rememberDismissed = (key: string) => { try { localStorage.setItem(key, '1'); } catch { /* 저장이 막혀도 이번 화면에서는 닫힌다 */ } };

/**
 * 홈 첫 방문 안내 — 인적사항이 비어 있고 "나중에"를 누르지 않았을 때만 보인다.
 * 주 흐름을 막지 않는다: 마법사로 강제하지 않고, 불러오는 중·실패·실서버(미지원)에는 아무것도 그리지 않는다.
 */
export function ProfilePrompt() {
  const me = useMe();
  const profile = useProfile();
  const titleId = useId();
  const key = me.data ? dismissedKey(me.data.id) : null;
  const [closed, setClosed] = useState(false);
  const remembered = useMemo(() => (key ? wasDismissed(key) : false), [key]);

  if (!profileSupported() || !key || !profile.isSuccess || !isProfileEmpty(profile.data) || closed || remembered) return null;

  const dismiss = () => {
    rememberDismissed(key);
    setClosed(true);
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
        <p>학력·경력·어학·자격증을 적어 두면 공고의 지원 자격을 비교할 때 써요. 지금 하지 않아도 다른 기능은 그대로 쓸 수 있어요.</p>
      </div>
      <div className="profile-prompt__actions">
        <Link to="/settings/profile" className="btn btn--primary" onClick={() => track('profile_prompt_accepted')}>입력하기</Link>
        <Button variant="text" onClick={dismiss}>나중에</Button>
      </div>
    </section>
  );
}
