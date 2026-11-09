import { useEffect, useLayoutEffect, useState, type CSSProperties } from 'react';
import { UserAvatar } from '@/components/ui/UserAvatar';
import { ORBIT_REF, RING_RADIUS, ringAngles, type OrbitRef, type OrbitTile } from '@/lib/repoPick';
import { useReducedMotion } from '@/lib/useLiveText';
import { CompanyMark } from './CompanyMark';
import './repoPick.css';

const VIEW = 560;
/** 초당 라디안 — 한 바퀴에 2분 20초라 눈에 거슬리지 않고, 확인이 끝나면 멈춘다 */
const OMEGA = 0.045;
const MOVE_MS = 800;
const ease = (x: number) => 1 - (1 - Math.min(1, Math.max(0, x))) ** 3;
const wrap = (angle: number) => angle - Math.PI * 2 * Math.round(angle / (Math.PI * 2));

type Mover = { fromR: number; fromA: number; toR: number; toA: number; t0: number };
/** 타일 위치는 React 상태가 아니라 프레임마다 transform 을 직접 고친다 — 천천히 도는 동안 화면 전체를 다시 그리지 않으려는 것이다 */
type Engine = {
  stage: HTMLDivElement | null;
  nodes: Map<string, HTMLDivElement>;
  lines: Map<string, SVGLineElement>;
  movers: Map<string, Mover>;
  ups: Map<string, boolean>;
  tiles: OrbitTile[];
  spinning: boolean;
  reduced: boolean;
  spin: number;
  last: number;
  frame: number;
};
const newEngine = (): Engine => ({ stage: null, nodes: new Map(), lines: new Map(), movers: new Map(), ups: new Map(), tiles: [], spinning: false, reduced: false, spin: 0, last: 0, frame: 0 });

function place(mover: Mover, now: number, instant: boolean) {
  const u = instant ? 1 : ease((now - mover.t0) / MOVE_MS);
  return { r: mover.fromR + (mover.toR - mover.fromR) * u, a: mover.fromA + wrap(mover.toA - mover.fromA) * u };
}

/** 좁은 원 그림(휴대폰 폭)에서는 타일과 이름이 작다. 크기를 아직 모르면 가장 클 때의 값으로 센다 */
function refOf(engine: Engine): OrbitRef {
  const size = engine.stage?.clientWidth ?? 0;
  if (!size) return ORBIT_REF;
  const small = size < 420;
  return { scale: size / VIEW, logo: small ? 44 : 52, label: small ? 64 : 80, outerLabels: !small };
}

/**
 * 고리나 각도가 바뀐 회사만 지금 자리에서 새 자리로 0.8초에 걸쳐 옮긴다. 처음 나온 회사는 제 자리에 바로 놓는다.
 * 자리는 지금까지 돈 각도에서 겹침이 적은 배치라, 다 고르고 멈출 때 한 번 더 정하면 멈춘 모습이 겹치지 않는다
 */
function retarget(engine: Engine, now: number) {
  const angles = ringAngles(engine.tiles.map((tile) => tile.ring), engine.spin, refOf(engine));
  engine.tiles.forEach((tile, i) => {
    const toR = RING_RADIUS[tile.ring];
    const toA = angles[i];
    const mover = engine.movers.get(tile.key);
    if (!mover) { engine.movers.set(tile.key, { fromR: toR, fromA: toA, toR, toA, t0: now }); return; }
    if (mover.toR === toR && mover.toA === toA) return;
    const from = place(mover, now, engine.reduced);
    Object.assign(mover, { fromR: from.r, fromA: from.a, toR, toA, t0: now });
  });
  const keys = new Set(engine.tiles.map((tile) => tile.key));
  for (const key of [...engine.movers.keys()]) if (!keys.has(key)) { engine.movers.delete(key); engine.ups.delete(key); }
}

function draw(engine: Engine, now: number) {
  const size = engine.stage?.clientWidth ?? 0;
  if (!size) return;
  const k = size / VIEW;
  const center = size / 2;
  for (const tile of engine.tiles) {
    const mover = engine.movers.get(tile.key);
    const node = engine.nodes.get(tile.key);
    if (!mover || !node) continue;
    const { r, a } = place(mover, now, engine.reduced);
    const angle = a + engine.spin;
    node.style.transform = `translate(${center + Math.cos(angle) * r * k}px, ${center + Math.sin(angle) * r * k}px)`;
    // 안쪽 고리의 이름은 위쪽 반에서 로고 위로 올린다(가운데 사진을 가리지 않게). 가로선 근처에서 깜빡이지 않도록 조금 넘어가야 바꾼다
    const sin = Math.sin(angle);
    const up = tile.ring === 2 && (engine.ups.get(tile.key) ? sin < 0.06 : sin < -0.06);
    engine.ups.set(tile.key, up);
    node.classList.toggle('is-up', up);
    const line = engine.lines.get(tile.key);
    if (line && tile.state === 'CHECKING') {
      line.setAttribute('x1', String(VIEW / 2 + Math.cos(angle) * 58));
      line.setAttribute('y1', String(VIEW / 2 + Math.sin(angle) * 58));
      line.setAttribute('x2', String(VIEW / 2 + Math.cos(angle) * (r - 34)));
      line.setAttribute('y2', String(VIEW / 2 + Math.sin(angle) * (r - 34)));
    }
  }
}

function schedule(engine: Engine) {
  if (engine.frame || typeof requestAnimationFrame !== 'function') return;
  engine.frame = requestAnimationFrame((ts) => tick(engine, ts));
}

function tick(engine: Engine, ts: number) {
  engine.frame = 0;
  const dt = engine.last ? Math.min(100, ts - engine.last) : 0;
  engine.last = ts;
  const spinning = engine.spinning && !engine.reduced && engine.tiles.length > 0;
  if (spinning) engine.spin += (OMEGA * dt) / 1000;
  draw(engine, ts);
  const moving = !engine.reduced && [...engine.movers.values()].some((mover) => ts - mover.t0 < MOVE_MS && (mover.fromR !== mover.toR || mover.fromA !== mover.toA));
  if (spinning || moving) schedule(engine);
  else engine.last = 0;
}

const cx = (...names: (string | false | null | undefined)[]) => names.filter(Boolean).join(' ');

/**
 * 맞는 기업을 고르는 원 그림. 가운데가 나(GitHub 프로필 사진)와 이 레포의 카드, 바깥 고리가 후보이고 맞은 조건이 많을수록 안쪽 고리로 들어온다.
 * 꾸밈이라 스크린리더에는 숨기고, 같은 내용은 오른쪽 칸과 로그가 글로 전한다. 움직임 줄이기를 켜면 돌지 않고 바로 자리를 옮긴다.
 */
export function RepoPickOrbit({ repoName, cardCount, tiles, spinning, login, avatarUrl }: {
  repoName: string; cardCount: number; tiles: OrbitTile[]; spinning: boolean; login: string; avatarUrl?: string | null;
}) {
  const reduced = useReducedMotion();
  const [engine] = useState(newEngine);

  // tiles 는 layoutKey 가 같으면 자리에 쓰는 값(key · 고리 · 상태)이 같다
  const layoutKey = tiles.map((tile) => `${tile.key}:${tile.ring}:${tile.state}`).join('|');
  useLayoutEffect(() => {
    Object.assign(engine, { tiles, spinning, reduced });
    const now = performance.now();
    retarget(engine, now);
    draw(engine, now);
    schedule(engine);
  }, [layoutKey, spinning, reduced, engine]);

  useEffect(() => {
    const observer = typeof ResizeObserver === 'function' && engine.stage ? new ResizeObserver(() => draw(engine, performance.now())) : null;
    if (observer && engine.stage) observer.observe(engine.stage);
    return () => {
      observer?.disconnect();
      if (engine.frame) cancelAnimationFrame(engine.frame);
      engine.frame = 0;
      engine.last = 0;
    };
  }, [engine]);

  return (
    <div className="rp-stage" ref={(el) => { engine.stage = el; }} aria-hidden="true">
      <svg viewBox={`0 0 ${VIEW} ${VIEW}`}>
        <circle className="rp-ring" cx="280" cy="280" r={RING_RADIUS[0]} />
        <circle className="rp-ring" cx="280" cy="280" r={RING_RADIUS[1]} />
        <circle className="rp-ring rp-ring--in" cx="280" cy="280" r={RING_RADIUS[2]} />
        {tiles.map((tile) => (
          <line key={tile.key} className={cx('rp-scan', tile.state === 'CHECKING' && 'is-on')} x1="280" y1="280" x2="280" y2="280"
            ref={(el) => { if (el) engine.lines.set(tile.key, el); else engine.lines.delete(tile.key); }} />
        ))}
      </svg>
      <div className="rp-me">
        <UserAvatar src={avatarUrl} login={login} size="fill" className="rp-me__face" />
        <b>{repoName}</b>
        <span>카드 {cardCount}장</span>
      </div>
      {tiles.map((tile, i) => (
        <div key={tile.key} className={cx('rp-co', tile.ring > 0 && 'is-in', tile.state === 'CHECKING' && 'is-now', tile.miss && 'is-miss', tile.rank !== null && 'is-top')}
          style={{ '--i': i } as CSSProperties}
          ref={(el) => { if (el) engine.nodes.set(tile.key, el); else engine.nodes.delete(tile.key); }}>
          <span className="rp-co__logo">
            <CompanyMark name={tile.name} logoUrl={tile.logoUrl} size={48} />
            {tile.rank !== null && <i className="rp-co__rank">{tile.rank}</i>}
          </span>
          <span className="rp-co__name">{tile.name}</span>
        </div>
      ))}
    </div>
  );
}
