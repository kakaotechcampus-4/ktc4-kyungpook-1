const PALETTE = [
  { bg: "#E6F1FB", fg: "#0C447C" },
  { bg: "#EEEDFE", fg: "#3C3489" },
  { bg: "#E1F5EE", fg: "#085041" },
  { bg: "#FAECE7", fg: "#712B13" },
  { bg: "#FBEAF0", fg: "#72243E" },
  { bg: "#FAEEDA", fg: "#633806" },
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
      className={`grid shrink-0 place-items-center rounded-full font-semibold ${sizeClass}`}
      style={{ background: tone.bg, color: tone.fg }}
    >
      {initial}
    </div>
  );
}
