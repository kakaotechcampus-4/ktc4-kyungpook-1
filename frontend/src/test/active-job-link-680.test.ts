import { describe, expect, it } from 'vitest';
import { activeJobLink } from '@/lib/jobLinks';

describe('#680 진행 중인 작업은 시작한 화면에서 이어 본다', () => {
  it('레포 정리는 그 레포의 4단계 화면에서 그 작업을 이어 본다', () => {
    expect(activeJobLink({ jobId: 'job 1', type: 'ANALYZE', userRepositoryId: 'r_auth' })).toBe('/repos/r_auth/run?job=job%201');
  });

  it('카드 초안 만들기는 만든 카드 id 를 모르면 그 레포의 카드 폴더로 간다', () => {
    expect(activeJobLink({ jobId: 'job', type: 'DRAFT', userRepositoryId: 'r_auth' })).toBe('/cards/repo/r_auth');
  });

  it('카드 초안 만들기는 쓰고 있는 첫 카드의 화면으로 가고 레포 정리는 카드 id 와 상관없다', () => {
    expect(activeJobLink({ jobId: 'job', type: 'DRAFT', userRepositoryId: 'r_auth' }, ['c1', 'c2'])).toBe('/cards/c1');
    expect(activeJobLink({ jobId: 'job', type: 'DRAFT', userRepositoryId: 'r_auth' }, [])).toBe('/cards/repo/r_auth');
    expect(activeJobLink({ jobId: 'job 1', type: 'ANALYZE', userRepositoryId: 'r_auth' }, ['c1'])).toBe('/repos/r_auth/run?job=job%201');
  });

  it('어느 레포인지 모르면 작업 상태 화면 대신 레포 목록이나 카드 목록으로 간다', () => {
    expect(activeJobLink({ jobId: 'job', type: 'ANALYZE', userRepositoryId: null })).toBe('/repos');
    expect(activeJobLink({ jobId: 'job', type: 'DRAFT', userRepositoryId: null })).toBe('/cards');
  });
});
