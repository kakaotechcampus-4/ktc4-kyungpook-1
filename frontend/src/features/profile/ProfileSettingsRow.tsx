import { Link } from 'react-router-dom';
import { profileSupported } from '@/api/capabilities';
import { useProfile } from '@/api/profile';
import { INTEREST_LABEL } from '@/lib/profileForm';

/** 마이페이지의 인적사항 줄 — GitHub 연결 줄과 같은 모양. 실서버(미지원)에서는 그리지도, 요청하지도 않는다. */
export function ProfileSettingsRow() {
  const profile = useProfile();
  if (!profileSupported()) return null;
  const p = profile.data;
  const summary = !p ? '관심 분야를 공고 매칭에 써요'
    : p.interests.length === 0 ? '아직 입력하지 않았어요. 공고 매칭에 써요'
    : `관심 ${p.interests.map((interest) => INTEREST_LABEL[interest]).join(', ')}`;
  return (
    <Link to="/settings/profile" className="set-row">
      <div className="set-row__text"><b>인적사항</b><span>{summary}</span></div>
      <span className="set-row__go" aria-hidden="true">›</span>
    </Link>
  );
}
