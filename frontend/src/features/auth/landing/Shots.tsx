/**
 * 첫 화면에 쓰는 실제 화면 캡처. 밝은, 어두운 테마를 둘 다 두고 지금 테마에 맞는 쪽만 보인다(landing-scroll.css).
 * 캡처는 샘플 데이터 화면이며, public/landing 에 있다.
 */
const src = (name: string, theme: 'light' | 'dark') => `/landing/${name}-${theme}.webp`;

function Themed({ name, alt, eager }: { name: string; alt: string; eager?: boolean }) {
  return <>
    <img className="lp-shot lp-shot--light" src={src(name, 'light')} alt={alt} loading={eager ? 'eager' : 'lazy'} decoding="async" />
    <img className="lp-shot lp-shot--dark" src={src(name, 'dark')} alt="" aria-hidden="true" loading="lazy" decoding="async" />
  </>;
}

/** 노트북 틀. video 를 주면 캡처 대신 짧은 시연 영상을 반복 재생한다 */
export function Laptop({ name, alt, video, eager }: { name?: string; alt: string; video?: boolean; eager?: boolean }) {
  // 움직임 줄이기를 켠 사람에게는 영상을 틀지 않고 첫 장면만 보인다
  const still = typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  return (
    <figure className="lp-laptop">
      <div className="lp-laptop__lid">
        <div className="lp-laptop__screen">
          {video
            ? <video className="lp-shot" src="/landing/demo.mp4" poster="/landing/demo-poster.webp" autoPlay={!still} muted loop playsInline preload="metadata" aria-label={alt} />
            : <Themed name={name!} alt={alt} eager={eager} />}
        </div>
      </div>
      <div className="lp-laptop__base" aria-hidden="true" />
    </figure>
  );
}

/** 휴대폰 틀. 위에 시간과 섬, 아래에 홈 막대를 두어 화면이 테두리에 붙지 않게 한다 */
export function Phone({ name, alt, className }: { name: string; alt: string; className?: string }) {
  return (
    <figure className={`lp-phone${className ? ` ${className}` : ''}`}>
      <div className="lp-phone__screen">
        <div className="lp-phone__status" aria-hidden="true"><span>9:41</span><i /></div>
        <Themed name={name} alt={alt} />
        <div className="lp-phone__home" aria-hidden="true" />
      </div>
    </figure>
  );
}

/** 제목을 단어로 나눠 차례로 떠오르게 한다. 읽는 사람에게는 한 문장으로 읽힌다 */
export function Split({ as: Tag = 'h2', className, lines }: { as?: 'h2' | 'h3'; className?: string; lines: string[] }) {
  return (
    <Tag className={className} data-split="" aria-label={lines.join(' ')}>
      {lines.map((line, li) => (
        <span key={li} className="lp-line" aria-hidden="true">
          {line.split(' ').map((w, wi) => <span key={wi}>{wi > 0 && ' '}<span className="lp-word" data-word="">{w}</span></span>)}
        </span>
      ))}
    </Tag>
  );
}
