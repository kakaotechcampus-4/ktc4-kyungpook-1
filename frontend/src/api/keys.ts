/** 쿼리 키 팩토리. 무효화는 항상 여기 키로만. */
export const keys = {
  me: ['me'] as const,
  repos: ['repos'] as const,
  repo: (id: string) => ['repos', id] as const,
  candidates: (repoId: string) => ['repos', repoId, 'candidates'] as const,
  recall: (repoId: string) => ['repos', repoId, 'recall'] as const,
  job: (id: string) => ['jobs', id] as const,
  activeJobs: ['jobs', 'active'] as const,
  cards: ['cards'] as const,
  card: (id: string) => ['cards', id] as const,
  versions: (id: string) => ['cards', id, 'versions'] as const,
  interview: (id: string) => ['cards', id, 'interview'] as const,
};
