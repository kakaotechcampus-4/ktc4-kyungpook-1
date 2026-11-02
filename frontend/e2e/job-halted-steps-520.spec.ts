import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #520 A-03 — 실패로 끝난 정리의 단계가 서버에서 RUNNING 으로 남아도 화면은 "진행 중"이라고 하지 않는다.
 * #459 뒤 남은 두 가지도 같이 지킨다: 멈춘 작업에 "…읽고 있어요"가 남지 않고, 단계 행이 "제목 | 숫자 | 배지"로 놓인다.
 */
test('실패한 정리의 작업 상태 화면은 남은 단계를 "중단됨"으로 말하고 행이 흐트러지지 않는다', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/');
  // 목의 r_fail 은 읽는 도중 GitHub 가 끊겨 FAILED 로 끝난다(그 단계는 RUNNING 으로 남는다)
  const jobId = await page.evaluate(async () => {
    const response = await fetch('/api/repos/r_fail/analyze', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } });
    return (await response.json()).data.jobId as string;
  });
  await page.goto(`/jobs/${jobId}`);
  const main = page.getByRole('main');

  await test.step('남은 단계는 진행 중이 아니라 중단됨이고, 현재형 문장이 없다', async () => {
    // 아직 도는 동안은 "진행 중"이 맞다 — 폴링이 FAILED 를 받으면 제목이 바뀐다
    await expect(main.getByRole('heading', { name: '레포 정리 실패', exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(main.getByText('중단됨', { exact: true })).toBeVisible();
    await expect(main.getByText('진행 중', { exact: true })).toHaveCount(0);
    await expect(main.getByRole('status')).toHaveCount(0); // 돌아가는 스피너도 없다
    await expect(main.getByText('여기서 멈췄어요', { exact: true })).toBeVisible();
    await expect(main.getByText('시작하지 못했어요').first()).toBeVisible();
    await expect(main.getByText(/고 있어요|차례예요/)).toHaveCount(0);
  });

  // 행 하나의 "제목 | 숫자 | 배지" 위치를 잰다. 한 줄이면 배지가 행의 오른쪽 끝에 붙는다
  const measure = () => page.locator('.job-status-step').evaluateAll((rows) => rows.map((row) => {
    const box = row.getBoundingClientRect();
    const inner = box.right - parseFloat(getComputedStyle(row).paddingRight);
    const [title, count, badge] = Array.from(row.children).map((el) => el.getBoundingClientRect());
    return { rowLeft: box.left, rowInner: inner, title, count, badge };
  }));
  const overflow = () => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

  for (const width of [1440, 390, 320]) {
    await test.step(`${width}px — 제목, 숫자, 배지가 차례로 놓이고 가로로 넘치지 않는다`, async () => {
      await page.setViewportSize({ width, height: 900 });
      await expect.poll(overflow).toBeLessThanOrEqual(0);
      const rows = await measure();
      expect(rows).toHaveLength(4);
      for (const { rowLeft, rowInner, title, count, badge } of rows) {
        expect(title.left).toBeGreaterThanOrEqual(rowLeft);
        expect(badge.right).toBeLessThanOrEqual(rowInner + 1); // 행 밖으로 나가지 않는다
        const sameLine = count.top < title.bottom && badge.top < title.bottom;
        if (sameLine) { // 줄바꿈 없이 한 줄이면 제목 다음에 숫자, 그다음 배지 — 숫자와 배지는 제목 바로 옆이 아니라 오른쪽 끝
          expect(title.right).toBeLessThanOrEqual(count.left + 1);
          expect(count.right).toBeLessThanOrEqual(badge.left + 1);
          if (width >= 390) expect(rowInner - badge.right).toBeLessThanOrEqual(2);
        } else { // 좁아서 줄이 바뀌면 숫자와 배지는 제목 아래로 내려온다
          expect(count.top).toBeGreaterThanOrEqual(title.bottom - 1);
        }
      }
    });
  }
});
