import { expect, test } from '@playwright/test';

for (const width of [320, 390, 768, 1440]) {
  test(`logo docks into the header and keeps login usable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/login?intro=1');
    const landing = page.locator('.landing');
    await expect(landing).toHaveAttribute('data-intro', 'playing');
    await expect(page.locator('.landing__hero')).toHaveAttribute('inert', '');
    await expect(landing).toHaveAttribute('data-intro', 'done');
    const brand = await page.getByRole('img', { name: 'Gitory', exact: true }).boundingBox();
    expect(brand!.y).toBeLessThan(60);
    expect(brand!.x).toBeGreaterThanOrEqual(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.getByRole('button', { name: '샘플로 체험하기', exact: true }).click();
    await expect(page.getByRole('heading', { name: '경험 정리', exact: true })).toBeVisible();
  });
}

for (const loggedIn of [false, true]) {
  test(`every refresh replays the introduction with loggedIn=${loggedIn}`, async ({ page }) => {
    await page.addInitScript(loggedIn => {
      sessionStorage.setItem('gitory.brand-intro.v1', '1'); // Legacy flag must no longer suppress entry.
      if (loggedIn) sessionStorage.setItem('gitory.demo.session', '1');
    }, loggedIn);
    await page.goto('/login');
    await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'playing');
    await page.keyboard.press('Escape');
    await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'done');
    await page.reload();
    await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'playing');
  });
}

for (const query of ['reason=expired', 'error=access_denied']) {
  test(`login feedback remains accessible after intro: ${query}`, async ({ page }) => {
    await page.goto(`/login?${query}`);
    await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'playing');
    await page.keyboard.press('Escape');
    await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'done');
    await expect(page.locator('.landing__hero')).not.toHaveAttribute('inert');
  });
}

test('a direct consent dialog remains immediately accessible', async ({ page }) => {
  await page.goto('/login?consent=1');
  await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'done');
});

for (const width of [390, 1440]) {
  test(`the brand stays centered longer before docking at ${width}px`, async ({ page }) => {
    await page.setViewportSize({width, height:900});
    await page.goto('/login');
    await page.waitForFunction(() => document.getAnimations().some(animation => (animation.effect as KeyframeEffect)?.target instanceof Element && ((animation.effect as KeyframeEffect).target as Element).classList.contains('landing-brand')));
    await page.evaluate(() => {
      const flight = document.getAnimations().find(animation => ((animation.effect as KeyframeEffect)?.target as Element)?.classList.contains('landing-brand'))!;
      flight.pause(); flight.currentTime = 800;
    });
    const bounds = await page.getByRole('img', {name:'Gitory',exact:true}).boundingBox();
    expect(Math.abs(bounds!.x + bounds!.width / 2 - width / 2)).toBeLessThan(1);
  });
}

test('reduced motion exposes the page immediately', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/login?intro=1');
  await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'done');
  await expect(page.locator('.landing__hero')).not.toHaveAttribute('inert');
});

test('resizing mid-flight safely reveals the final layout', async ({ page }) => {
  await page.goto('/login?intro=1');
  await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'playing');
  await page.setViewportSize({ width: 390, height: 750 });
  await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'done');
  await expect(page.locator('.landing__hero')).not.toHaveAttribute('inert');
});

test('skipping ends only the current intro and refresh replays it', async ({ page }) => {
  await page.goto('/login');
  await page.getByRole('button', { name: /건너뛰기/ }).click();
  await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'done');
  await page.reload();
  await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'playing');
});

test('missing animation support never blocks the landing page', async ({ page }) => {
  await page.addInitScript(() => { Object.defineProperty(Element.prototype, 'animate', { value: undefined }); });
  await page.goto('/login?intro=1');
  await expect(page.locator('.landing')).toHaveAttribute('data-intro', 'done');
  await expect(page.locator('.landing__hero')).not.toHaveAttribute('inert');
});
