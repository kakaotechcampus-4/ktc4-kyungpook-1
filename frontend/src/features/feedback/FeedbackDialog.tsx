import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { endpoints } from '@/api/endpoints';
import { errorView } from '@/api/errorView';
import { Button, Textarea } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';

const MAX = 1000;

export function FeedbackDialog({ onClose }: { onClose: () => void }) {
  const [text, setText] = useState('');
  const send = useMutation({
    mutationFn: (message: string) => endpoints.sendFeedback({ message, path: window.location.pathname }),
    meta: { errorPresentation: 'inline' },
  });
  const blank = text.trim().length === 0;

  if (send.isSuccess) {
    return (
      <Modal title="문제 신고" width={480} onClose={onClose}>
        <p style={{ fontSize: 16 }}>신고를 받았어요. 확인해 볼게요.</p>
        <div className="row" style={{ justifyContent: 'flex-end', marginTop: 16 }}>
          <Button size="lg" onClick={onClose}>닫기</Button>
        </div>
      </Modal>
    );
  }
  return (
    <Modal title="문제 신고" width={480} onClose={onClose}>
      <form className="stack" style={{ gap: 12 }} onSubmit={(e) => { e.preventDefault(); if (!blank && !send.isPending) send.mutate(text.trim()); }}>
        <label className="field">
          <span className="field__label" style={{ fontSize: 16 }}>무엇이 안 되나요?</span>
          <Textarea rows={6} maxLength={MAX} required value={text} onChange={(e) => setText(e.target.value)} style={{ fontSize: 16 }} />
        </label>
        <span className="feedback-count">{text.length} / {MAX}</span>
        <p className="c-2" style={{ fontSize: 15 }}>지금 화면 주소와 브라우저 종류가 함께 전달돼요. 카드와 자소서 내용은 전달되지 않아요.</p>
        {send.isError && <p role="alert" style={{ fontSize: 15 }}>{errorView(send.error).message}</p>}
        <div className="row" style={{ justifyContent: 'flex-end', gap: 8 }}>
          <Button size="lg" variant="secondary" onClick={onClose}>취소</Button>
          <Button size="lg" type="submit" disabled={blank} loading={send.isPending}>보내기</Button>
        </div>
      </form>
    </Modal>
  );
}
