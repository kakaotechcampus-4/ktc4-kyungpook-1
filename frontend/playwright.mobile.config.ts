import { defineConfig } from '@playwright/test';
import base from './playwright.static.config';

/** Repeat the existing user journeys against a production build on a phone viewport. */
export default defineConfig({
  ...base,
  testMatch: /(?:core-flow|flows|card-workflow|resilience|candidate-recovery|candidate-commit-picker|card-review-navigation|mobile-footer)\.spec\.ts/,
  use: { ...base.use, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true },
});
