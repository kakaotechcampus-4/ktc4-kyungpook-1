import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { endpoints } from '@/api/endpoints';
import { errorView } from '@/api/errorView';
import { demoLogin, isDemo } from '@/mock/browser';

export function useLogin() {
  const nav = useNavigate(); const qc = useQueryClient();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const start = async () => {
    if (pending) return;
    setPending(true); setFailure(null);
    try {
      if (!isDemo) { window.location.assign(endpoints.githubStartUrl()); return; }
      demoLogin(); await qc.resetQueries(); nav('/', { replace: true });
    } catch (error) { setFailure(errorView(error).message); }
    finally { setPending(false); }
  };
  return { isDemo, start, pending, failure, label: isDemo ? '샘플로 체험하기' : 'GitHub로 시작' };
}
