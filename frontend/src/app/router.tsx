import { lazy, Suspense, type ReactNode } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from '@/components/layout/AppShell';
import { AuthGate } from './AuthGate';
import { ErrorBoundary } from './ErrorBoundary';
import { Spinner } from '@/components/ui';

/**
 * 라우트 = 플로우 맵 노드 ID. 기능별로 코드 스플리팅한다.
 *  A1 /login · (OAuth 콜백은 Spring 이 처리하고 / 또는 /login?error= 로 돌려보낸다)
 *  B1 /repos · B3 /repos/:id/run · C1 /repos/:id/candidates · C4 /repos/:id/recall
 *  D1~D9·E2·E3 /cards/:id (모드는 쿼리) · D5 /cards/:id/interview · E4 /cards/new · E1 /
 *  F1 /settings/github · F2 /settings · F3 /settings/leave
 */
const L = (f: () => Promise<Record<string, unknown>>, name: string) => lazy(() => f().then((m) => ({ default: m[name] as React.ComponentType })));
const LandingPage = L(() => import('@/features/auth/LandingPage'), 'LandingPage');
const HomePage = L(() => import('@/features/home/HomePage'), 'HomePage');
const ReposPage = L(() => import('@/features/repos/ReposPage'), 'ReposPage');
const AnalyzePage = L(() => import('@/features/repos/AnalyzePage'), 'AnalyzePage');
const CandidateBoardPage = L(() => import('@/features/candidates/CandidateBoardPage'), 'CandidateBoardPage');
const RecallPage = L(() => import('@/features/candidates/RecallPage'), 'RecallPage');
const CardsListPage = L(() => import('@/features/cards/CardsListPage'), 'CardsListPage');
const CardPage = L(() => import('@/features/cards/CardPage'), 'CardPage');
const InterviewPage = L(() => import('@/features/cards/InterviewPage'), 'InterviewPage');
const NewCardPage = L(() => import('@/features/cards/NewCardPage'), 'NewCardPage');
const GithubSettingsPage = L(() => import('@/features/settings/GithubSettingsPage'), 'GithubSettingsPage');
const MyPage = L(() => import('@/features/settings/MyPage'), 'MyPage');
const LeavePage = L(() => import('@/features/settings/LeavePage'), 'LeavePage');
const NotFoundPage = L(() => import('@/features/NotFoundPage'), 'NotFoundPage');

const S = ({ children }: { children: ReactNode }) => <Suspense fallback={<main className="main" style={{ alignItems: 'center', paddingTop: 96 }}><Spinner /></main>}>{children}</Suspense>;

export const router = createBrowserRouter([
  { path: '/login', element: <S><LandingPage /></S>, errorElement: <ErrorBoundary /> },
  { path: '/auth/callback', element: <Navigate to="/" replace /> }, // Spring 이 세션을 세운 뒤 여기로 보낼 수도 있다
  {
    element: <AuthGate><AppShell /></AuthGate>,
    errorElement: <ErrorBoundary />,
    children: [
      { path: '/', element: <S><HomePage /></S> },
      { path: '/repos', element: <S><ReposPage /></S> },
      { path: '/repos/:repoId/run', element: <S><AnalyzePage /></S> },
      { path: '/repos/:repoId/candidates', element: <S><CandidateBoardPage /></S> },
      { path: '/repos/:repoId/recall', element: <S><RecallPage /></S> },
      { path: '/cards', element: <S><CardsListPage /></S> },
      { path: '/cards/new', element: <S><NewCardPage /></S> },
      { path: '/cards/:cardId', element: <S><CardPage /></S> },
      { path: '/cards/:cardId/interview', element: <S><InterviewPage /></S> },
      { path: '/settings', element: <S><MyPage /></S> },
      { path: '/settings/github', element: <S><GithubSettingsPage /></S> },
      { path: '/settings/leave', element: <S><LeavePage /></S> },
      { path: '*', element: <S><NotFoundPage /></S> },
    ],
  },
]);
