import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { queryClient } from './app/queryClient';
import { router } from './app/router';
import { applyTheme, getTheme } from './lib/theme';
import { Toaster } from './lib/toast';
import { installDemoApi } from './mock/browser';
import './styles/tokens.css';
import './styles/base.css';
import './styles/ui.css';
import './styles/shell.css';
import './styles/pages.css';
import './styles/design2.css';
import './styles/layout-tio.css';

applyTheme(getTheme());
installDemoApi(); // VITE_API_MOCK=false 면 아무것도 하지 않는다 (fetch 가 그대로 /api 로 나간다)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster />
    </QueryClientProvider>
  </StrictMode>,
);
