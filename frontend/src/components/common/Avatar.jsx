const PALETTE = [
  { bg: "#E7EFF6", fg: "#254A6B" },
  { bg: "#ECEAF4", fg: "#443C7D" },
  { bg: "#E4EFE9", fg: "#22543F" },
  { bg: "#F3E8E2", fg: "#733E23" },
  { bg: "#F4E5EA", fg: "#722C46" },
  { bg: "#F3ECDD", fg: "#5C4419" },
];

function pickTone(seed) {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  return PALETTE[hash % PALETTE.length];
}

export default function Avatar({ name, size = 9 }) {
  const initial = name?.trim()?.[0]?.toUpperCase() ?? "?";
  const tone = pickTone(name ?? "");
  const sizeClass = { 7: "h-7 w-7 text-xs", 9: "h-9 w-9 text-sm", 11: "h-11 w-11 text-base" }[size] ?? "h-9 w-9 text-sm";

  return (
    <div
      className={`grid shrink-0 place-items-center rounded-full font-semibold ring-1 ring-inset ring-black/[0.04] ${sizeClass}`}
      style={{ background: tone.bg, color: tone.fg }}
    >
      {initial}
    </div>
  );
}
