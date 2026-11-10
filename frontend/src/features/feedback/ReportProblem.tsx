import { useState } from 'react';
import { useMe } from '@/api/queries';
import { Button } from '@/components/ui';
import { FeedbackDialog } from './FeedbackDialog';

/** 신고는 계정에 묶여 저장되므로 로그인한 사용자에게만 자리를 만든다 */
function useReport() {
  const me = useMe();
  const [open, setOpen] = useState(false);
  return { loggedIn: me.isSuccess, open, setOpen };
}

export function ReportProblemButton() {
  const { loggedIn, open, setOpen } = useReport();
  if (!loggedIn) return null;
  return (
    <>
      <button type="button" className="legal-links__report" onClick={() => setOpen(true)}>문제 신고</button>
      {open && <FeedbackDialog onClose={() => setOpen(false)} />}
    </>
  );
}

export function ReportProblemAction() {
  const { loggedIn, open, setOpen } = useReport();
  if (!loggedIn) return null;
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>문제 신고</Button>
      {open && <FeedbackDialog onClose={() => setOpen(false)} />}
    </>
  );
}
