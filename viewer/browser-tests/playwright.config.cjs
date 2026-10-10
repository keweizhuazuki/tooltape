'use strict';
const path = require('node:path');
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: __dirname,
  testMatch: '**/*.spec.cjs',
  outputDir: path.join(__dirname, 'results'),
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 30000,
  expect: { timeout: 5000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: path.join(__dirname, 'report'), open: 'never' }],
    ['json', { outputFile: path.join(__dirname, 'artifacts', 'test-results.json') }]
  ],
  use: {
    browserName: 'chromium',
    headless: true,
    offline: true,
    locale: 'en-US',
    timezoneId: 'UTC',
    colorScheme: 'dark',
    reducedMotion: 'reduce',
    deviceScaleFactor: 1,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure'
  },
  projects: [
    { name: 'desktop-chromium', use: { viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile-chromium', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } }
  ]
});
