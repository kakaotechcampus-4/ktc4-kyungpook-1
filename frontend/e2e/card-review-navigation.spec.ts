import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';
for (const width of [320, 390])
  test(`draft navigation clears the bottom action row at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await loginAsDemo(page);
    await page.goto('/cards/card_01');
    const panel = page.getByRole('region', { name: '다른 카드 보기' });
    await expect(panel).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    const nav = await panel.boundingBox();
    const footer = await page.locator('.sticky-footer').boundingBox();
    expect(footer!.y - (nav!.y + nav!.height)).toBeGreaterThanOrEqual(16);
  });
for (const width of [320, 390, 1440])
  test(`card review navigation follows title order and fits ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginAsDemo(page);
    await page.goto('/cards');
    // 정렬 칸 대신 묶어 보기(최근순). 이전/다음은 화면에 보이는 순서를 따른다
    await page.getByRole('radio', { name: '최근순', exact: true }).click();
    const items = page.locator('.experience-item');
    await expect(items.first()).toBeVisible();
    const order = await items.evaluateAll((links) =>
      links.map((link) => link.getAttribute('href')),
    );
    await items.first().click();
    const panel = page.getByRole('region', { name: '다른 카드 보기' });
    await expect(
      panel.getByRole('button', { name: '이전 카드' }),
    ).toBeDisabled();
    await expect(
      panel.getByRole('link', { name: '다음 카드' }),
    ).toHaveAttribute('href', order[1]!);
    await panel.scrollIntoViewIfNeeded();
    if (width === 1440) {
      const body = await page.locator('.two-col > :first-child').boundingBox();
      const nav = await panel.boundingBox();
      expect(
        Math.abs(body!.y + body!.height - (nav!.y + nav!.height)),
      ).toBeLessThanOrEqual(2);
    }
    await panel.getByRole('link', { name: '다음 카드' }).click();
    await expect(page).toHaveURL(order[1]!);
    await expect(
      page
        .getByRole('region', { name: '다른 카드 보기' })
        .getByRole('link', { name: '이전 카드' }),
    ).toHaveAttribute('href', order[0]!);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    expect(await page.evaluate(() => window.scrollY)).toBeLessThan(160);
  });
test('edit and masking modes do not expose navigation that bypasses writing protection', async ({
  page,
}) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_01?mode=edit');
  await expect(
    page.getByRole('button', { name: '저장하고 닫기' }),
  ).toBeVisible();
  await expect(page.locator('.card-navigation')).toHaveCount(0);
  await page.goto('/cards/card_01?mode=mask');
  await expect(
    page.getByRole('heading', { name: '공유할 정보 가리기' }),
  ).toBeVisible();
  await expect(page.locator('.card-navigation')).toHaveCount(0);
});
test('editing, history and interview roundtrips retain the originating card sequence', async ({
  page,
}) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  // 정렬 선택 칸 대신 묶어 보기 — 다른 보기로 바꿔도 이전/다음은 화면에 보이는 순서를 따른다
  await page.getByRole('radio', { name: '최근순', exact: true }).click();
  const items = page.locator('.experience-item');
  await expect(items.first()).toBeVisible();
  const order = await items.evaluateAll((links) =>
    links.map((link) => link.getAttribute('href')),
  );
  const index = order.indexOf('/cards/card_01');
  await page.locator('.experience-item[href="/cards/card_01"]').click();
  // 하단 "직접 수정"은 없앴다 — 칸마다 있는 수정 버튼으로 편집에 들어간다
  await page.getByRole('button', { name: '상황 수정', exact: true }).click();
  await expect(page.locator('.card-navigation')).toHaveCount(0);
  await page
    .getByRole('button', { name: '저장하고 닫기', exact: true })
    .click();
  const verify = async () => {
    if (index > 0)
      await expect(
        page
          .getByRole('region', { name: '다른 카드 보기' })
          .getByRole('link', { name: '이전 카드' }),
      ).toHaveAttribute('href', order[index - 1]!);
    if (index < order.length - 1)
      await expect(
        page
          .getByRole('region', { name: '다른 카드 보기' })
          .getByRole('link', { name: '다음 카드' }),
      ).toHaveAttribute('href', order[index + 1]!);
  };
  await verify();
  await page.getByRole('button', { name: '작성 이력', exact: true }).click();
  await page.getByRole('dialog').getByLabel('닫기', { exact: true }).click();
  await verify();
  await page
    .getByRole('button', { name: '과제 답변하기', exact: true })
    .click();
  await page.getByRole('link', { name: '카드로', exact: true }).click();
  await verify();
});
