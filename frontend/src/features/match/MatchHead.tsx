import { useOverallRecommendation, useStartOverallRecommendation, type OverallRecommendation } from "@/api/matching";
import { errorView } from "@/api/errorView";
import { Badge, Button, PageTitle, Track } from "@/components/ui";
import { isDemo } from "@/mock/browser";

const dayLabel = (iso: string) =>
  new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "long", day: "numeric" }).format(new Date(iso));

/**
 * 공고 매칭 머리 — 제목, 언제 무엇으로 맞춰 봤는지 한 줄, '내 조건으로 맞춰 보기' 버튼과 그 아래 알림 줄.
 * 누를 때만 서버가 배경에서 계산하고 그 밖에는 저장된 결과를 읽는다(검색·직무 칩과는 상관없다).
 * 지난 결과를 못 읽은 것은 공고 목록 오류처럼 크게 알리지 않는다(목록이 이 화면의 본문이다).
 */
export function MatchHead({ confirmedCards }: { confirmedCards: number | undefined }) {
  const overall = useOverallRecommendation();
  const start = useStartOverallRecommendation();
  const data = overall.data;
  const computing = start.isPending || data?.status === "COMPUTING";
  const matched = !!data && data.status !== "NONE";
  return (
    <>
      <div className="match-head">
        <div className="match-head__main">
          <PageTitle after={isDemo ? <Badge kind="NEUTRAL">예시 공고</Badge> : undefined}>공고 매칭</PageTitle>
          {computing ? <Progress progress={data?.progress ?? null} /> : <p className="match-head__sub">{subLine(data, confirmedCards)}</p>}
        </div>
        <Button
          variant={matched ? "outline" : "primary"}
          onClick={() => start.mutate()}
          disabled={computing || overall.isPending}
          loading={start.isPending}
        >
          {computing ? "맞춰 보는 중" : matched ? "다시 맞춰 보기" : "내 조건으로 맞춰 보기"}
        </Button>
      </div>
      {start.isError && (
        <p className="match-line match-line--danger" role="alert">
          <strong>맞춰 보기를 시작하지 못했어요.</strong> {errorView(start.error).message}
        </p>
      )}
      {overall.isError && !data && (
        <p className="match-line">
          지난 결과를 불러오지 못했어요.{" "}
          <Button variant="text" size="sm" loading={overall.isFetching} onClick={() => void overall.refetch()}>결과 다시 불러오기</Button>
        </p>
      )}
      {data && !computing && data.status === "FAILED" && (
        <p className="match-line">
          <strong>맞춰 보지 못했어요.</strong> 잠시 뒤 다시 맞춰 봐 주세요.{data.companies.length > 0 && " 아래는 지난번 결과예요."}
        </p>
      )}
      {data && !computing && data.status === "READY" && data.stale && (
        <p className="match-line">
          <strong>카드나 인적사항이 바뀌었어요.</strong> 다시 맞춰 보면 바뀐 내용으로 골라요.
        </p>
      )}
      {data && !computing && data.status === "READY" && data.companies.length === 0 && (
        <p className="match-line">맞는 공고를 찾지 못했어요. 경험 카드를 더 확정하거나 인적사항을 채우면 달라질 수 있어요.</p>
      )}
    </>
  );
}

function subLine(data: OverallRecommendation | undefined, confirmedCards: number | undefined) {
  if (!data || data.status === "NONE" || !data.computedAt) return "확정한 경험 카드와 인적사항으로 맞는 공고를 골라 드려요";
  const cards = confirmedCards === undefined ? "확정 카드와" : `확정 카드 ${confirmedCards}장과`;
  return `${dayLabel(data.computedAt)}에 ${cards} 인적사항으로 맞춰 봤어요`;
}

function Progress({ progress }: { progress: OverallRecommendation["progress"] }) {
  const known = !!progress && progress.total > 0;
  return (
    <div className="match-head__progress">
      <span role="status">{known ? `공고 ${progress.total}곳 중 ${progress.done}곳을 확인했어요` : "맞춰 볼 공고를 고르고 있어요"}</span>
      <Track value={known ? progress.done / progress.total : 0} label="맞춰 보기 진행률" live />
    </div>
  );
}
