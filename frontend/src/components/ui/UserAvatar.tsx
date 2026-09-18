import { useEffect, useState } from 'react';

type AvatarSize = 28 | 36 | 48 | 64;

export function UserAvatar({ src, login, size = 36, className = '' }: {
  src?: string | null;
  login: string;
  size?: AvatarSize;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [src]);
  const label = login || '사용자';
  const initial = Array.from(label.trim())[0]?.toUpperCase() ?? 'G';

  return (
    <span className={`user-avatar ${className}`.trim()} style={{ width: size, height: size }}>
      {src && !failed
        ? <img src={src} alt={`${label} GitHub 프로필`} onError={() => setFailed(true)} />
        : <span className="user-avatar__fallback" role="img" aria-label={`${label} 프로필 기본 이미지`}>{initial}</span>}
    </span>
  );
}
