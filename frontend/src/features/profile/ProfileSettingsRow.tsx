import { Link } from 'react-router-dom';
import { profileSupported } from '@/api/capabilities';
import { isProfileEmpty, useProfile } from '@/api/profile';

/** 마이페이지의 인적사항 줄 — GitHub 연결 줄과 같은 모양. 실서버(미지원)에서는 그리지도, 요청하지도 않는다. */
export function ProfileSettingsRow() {
  const profile = useProfile();
  if (!profileSupported()) return null;
  const p = profile.data;
  const summary = !p ? '학력·경력·어학·자격증을 공고 매칭에 써요'
    : p.savedAt === null ? '아직 입력하지 않았어요 · 공고 매칭에 써요'
    : isProfileEmpty(p) ? '입력한 정보가 없어요'
    : `학력 ${p.educations.length} · 경력 ${p.careers.length} · 어학 ${p.languages.length} · 자격증 ${p.certifications.length}`;
  return (
    <Link to="/settings/profile" className="kv" style={{ padding: '14px 0' }}>
      <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 13.5 }}>인적사항</span><span className="t-12 c-2">{summary}</span></div>
      <span className="c-3" style={{ fontSize: 16 }} aria-hidden="true">›</span>
    </Link>
  );
}
