import { useEffect, useState } from "react";
import { MOCK_CARDS } from "../data/cards";

/*
  경험 카드 목록을 불러오는 훅.
  지금은 mock 데이터를 반환하지만, 백엔드가 준비되면
  아래 fetch 블록의 주석을 풀고 mock 부분을 지우면 됩니다.

  기대하는 응답 형태:
  [{ id, kind, title, status, needsReview, repoName, source, dateLabel,
     evidenceCount, userStatedCount, emptyFieldNote }, ...]
*/
export function useCards() {
  const [cards, setCards] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let alive = true;

    // ── 백엔드 연동 시 이 블록을 사용 ─────────────────────────
    // fetch("/api/cards")
    //   .then((res) => {
    //     if (!res.ok) throw new Error("경험 카드 목록을 불러오지 못했어요");
    //     return res.json();
    //   })
    //   .then((data) => { if (alive) setCards(data); })
    //   .catch((err) => { if (alive) setError(err); })
    //   .finally(() => { if (alive) setLoading(false); });

    // ── mock (백엔드 연동 시 삭제) ────────────────────────────
    const timer = setTimeout(() => {
      if (alive) {
        setCards(MOCK_CARDS);
        setLoading(false);
      }
    }, 150);

    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, []);

  return { cards, loading, error };
}
