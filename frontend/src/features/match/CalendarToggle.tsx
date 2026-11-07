import { useReducer } from "react";
import { CalendarMinus, CalendarPlus } from "lucide-react";
import { useMe } from "@/api/queries";
import { Button } from "@/components/ui";
import { toast } from "@/lib/toast";
import { calendarStorageKey, readSchedules, seoulDateKey, type JobSchedule } from "@/features/home/calendarModel";

type Posting = { id: string; company: string; role: string; expiresAt: string | null };

const read = (key: string): JobSchedule[] | null => {
  try { return readSchedules(localStorage.getItem(key)); } catch { return null; }
};

/**
 * 공고 마감일을 홈 캘린더에 담거나 뺀다. 캘린더와 같은 자리(이 브라우저, 사용자별)에 '지원 마감' 일정으로 저장하고,
 * 공고 id(matchId)를 함께 적어 캘린더에서 누르면 이 공고로 오게 한다. 마감일이 없는 공고에는 버튼이 없다.
 */
export function CalendarToggle({ posting }: { posting: Posting }) {
  const userId = useMe().data?.id;
  const [, refresh] = useReducer((n: number) => n + 1, 0);
  if (!userId || !posting.expiresAt) return null;
  const key = calendarStorageKey(userId);
  const added = read(key)?.some((event) => event.matchId === posting.id) ?? false;
  const toggle = () => {
    const current = read(key);
    if (!current) {
      toast("저장된 캘린더 일정을 읽지 못했어요. 홈 캘린더에서 확인해 주세요.", { tone: "danger" });
      return;
    }
    const next: JobSchedule[] = added
      ? current.filter((event) => event.matchId !== posting.id)
      : [...current, { id: crypto.randomUUID(), company: posting.company, role: posting.role, date: seoulDateKey(posting.expiresAt!), kind: "deadline", url: "", note: "", matchId: posting.id }];
    try {
      localStorage.setItem(key, JSON.stringify(next));
    } catch {
      toast("캘린더에 저장하지 못했어요. 브라우저 저장 공간과 설정을 확인해 주세요.", { tone: "danger" });
      return;
    }
    toast(added ? "캘린더에서 뺐어요" : "캘린더에 마감일을 담았어요", { tone: "success" });
    refresh();
  };
  return (
    <Button variant="outline" onClick={toggle}>
      {added ? <CalendarMinus size={16} aria-hidden /> : <CalendarPlus size={16} aria-hidden />}
      {added ? "캘린더에서 빼기" : "캘린더에 담기"}
    </Button>
  );
}
