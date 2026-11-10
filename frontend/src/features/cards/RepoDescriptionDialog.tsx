import { useState } from 'react';
import { errorView } from '@/api/errorView';
import { useUpdateRepoDescription } from '@/api/queries';
import type { RepoSummary } from '@/api/schemas';
import { Button, Field, Input, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { CONFIG } from '@/lib/config';

/**
 * 폴더 설명 고치기(#777). 사용자가 쓴 설명만 칸에 채우고, GitHub 설명이나 README 문장이 보이는 중이면 칸을 비운 채 그 글을 안내로 보여 준다.
 * 비운 채 저장하면 사용자가 쓴 설명을 지워 GitHub 설명이나 README 문장으로 돌아간다.
 * README 에서 문장을 못 골라 자동으로 요약한 문장(AI)이 보이는 중이면 그 글이 자동으로 만든 것임을 칸 위에 알린다(#783).
 */
export function RepoDescriptionDialog({ repo, onClose }: { repo: Pick<RepoSummary, 'id' | 'description' | 'descriptionSource'>; onClose: () => void }) {
  const mine = repo.descriptionSource === 'USER' ? repo.description ?? '' : '';
  const [text, setText] = useState(mine);
  const save = useUpdateRepoDescription(repo.id);
  const body = text.trim();
  const changed = body !== mine;
  const submit = async () => {
    if (!changed || save.isPending) return;
    try { await save.mutateAsync(body || null); onClose(); } catch { /* 저장하지 못한 글은 칸에 그대로 두고 아래 안내만 보인다 */ }
  };
  return (
    <Modal title="폴더 설명" width={560} onClose={() => { if (!save.isPending) onClose(); }}
      footer={{ strong: '비워 두면 GitHub 설명이나 README 문장을 보여요.', actions: <><Button variant="secondary" disabled={save.isPending} onClick={onClose}>취소</Button><Button disabled={!changed} loading={save.isPending} onClick={() => void submit()}>저장</Button></> }}>
      <Field label="한 줄 설명">
        {repo.descriptionSource === 'AI' && <span className="t-14 c-2">자동으로 요약한 문장이에요</span>}
        <Input aria-label="한 줄 설명" autoFocus maxLength={CONFIG.REPO_DESCRIPTION_MAX} value={text} disabled={save.isPending}
          placeholder={repo.descriptionSource === 'USER' ? undefined : repo.description ?? undefined}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) void submit(); }} />
      </Field>
      {save.isError && <Note strong="설명을 저장하지 못했어요" tone="danger">{errorView(save.error).message}</Note>}
    </Modal>
  );
}
