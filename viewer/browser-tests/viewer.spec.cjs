'use strict';
const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { pathToFileURL } = require('node:url');

const root = path.resolve(__dirname, '../..');
const viewer = path.join(root, 'viewer/index.html');
const fixture = path.join(root, 'fixtures/weather-retry.tape');
const sessionFixture = path.join(root, 'fixtures/weather-retry-v2.tape');
const encode = value => Buffer.from(value, 'utf8').toString('base64url');
const file = (name, content) => ({ name, mimeType: 'text/plain', buffer: Buffer.from(content) });
const tape = rows => 'TOOLTAPE\t1\n' + rows.map((row, index) => [
  index, row.nanos || '1', encode(row.name || 'test.run'),
  encode(row.input || '{}'), row.status || 'OK', encode(row.output || '{}')
].join('\t')).join('\n') + (rows.length ? '\n' : '');
const calls = page => page.locator('#timeline button[data-position]');

async function openFixture(page) {
  await page.getByLabel('Open primary tape', { exact: true }).setInputFiles(fixture);
  await expect(page.locator('#stat-calls')).toHaveText('2');
  await expect(page.locator('#filename')).toHaveText('weather-retry.tape');
}

async function chooseWithButton(page, button, input) {
  const chooserPromise = page.waitForEvent('filechooser');
  await button.click();
  await (await chooserPromise).setFiles(input);
}

async function chooseWithKeyboard(page, button, key, input) {
  await button.focus();
  const chooserPromise = page.waitForEvent('filechooser');
  await page.keyboard.press(key);
  await (await chooserPromise).setFiles(input);
}

async function capture(page, browser, testInfo, name, fixtureDescription, sourceFixture = fixture) {
  const directory = path.join(__dirname, 'artifacts/screenshots');
  await fs.mkdir(directory, { recursive: true });
  await page.evaluate(() => {
    document.activeElement?.blur();
    window.scrollTo(0, 0);
  });
  const screenshot = path.join(directory, name + '.png');
  await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' });
  const sha256 = data => createHash('sha256').update(data).digest('hex');
  await fs.writeFile(path.join(directory, name + '.json'), JSON.stringify({
    screenshot: name + '.png',
    kind: 'actual Chromium rendering, no image compositing',
    viewer: 'viewer/index.html',
    viewerSha256: sha256(await fs.readFile(viewer)),
    fixture: fixtureDescription,
    sourceFixture: path.relative(root, sourceFixture).split(path.sep).join('/'),
    sourceFixtureSha256: sha256(await fs.readFile(sourceFixture)),
    commit: process.env.GITHUB_SHA || null,
    workflowRun: process.env.GITHUB_RUN_ID || null,
    playwright: require('@playwright/test/package.json').version,
    browser: browser.version(),
    project: testInfo.project.name,
    viewport: page.viewportSize(),
    deviceScaleFactor: 1
  }, null, 2) + '\n');
  await testInfo.attach(name, { path: screenshot, contentType: 'image/png' });
}

test.beforeEach(async ({ page }) => {
  const errors = [];
  const requests = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => {
    if (!request.url().startsWith('file:')) requests.push(request.url());
  });
  page.__tooltapeErrors = errors;
  page.__tooltapeRequests = requests;
  await page.goto(pathToFileURL(viewer).href);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
});

test.afterEach(async ({ page }) => {
  expect(page.__tooltapeErrors, 'no uncaught browser errors').toEqual([]);
  expect(page.__tooltapeRequests, 'the offline viewer makes no non-file requests').toEqual([]);
});

test('opens the real fixture through the file chooser and supports choosing it again', async ({ page }) => {
  await expect(page.locator('#compare-button')).toBeDisabled();
  await expect(page.locator('#empty')).toBeVisible();
  await chooseWithButton(page, page.getByRole('button', { name: 'Open a tape', exact: true }), fixture);
  await expect(page.locator('#stat-calls')).toHaveText('2');
  await expect(page.locator('#stat-errors')).toHaveText('1');
  await expect(page.locator('#stat-success')).toHaveText('50');
  await expect(calls(page)).toHaveCount(2);
  await expect(page.locator('#empty')).toBeHidden();
  await expect(page.locator('#compare-button')).toBeEnabled();
  await expect(page.locator('#announcer')).toContainText('Opened weather-retry.tape');
  await expect(page.locator('#open-file')).toHaveValue('');
  await chooseWithButton(page, page.locator('#open-button'), fixture);
  await expect(calls(page)).toHaveCount(2);
  await expect(page.locator('#notice')).toBeHidden();
});

test('Tab skips invisible file inputs before and after opening a tape', async ({ page }) => {
  await expect(page.locator('#open-file')).toHaveAttribute('tabindex', '-1');
  await expect(page.locator('#compare-file')).toHaveAttribute('tabindex', '-1');
  await page.locator('#open-button').focus();
  await page.keyboard.press('Tab');
  await expect(page.locator('#empty-open')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.locator('#load-example')).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(page.locator('#empty-open')).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(page.locator('#open-button')).toBeFocused();

  await openFixture(page);
  await page.locator('#compare-button').focus();
  await page.keyboard.press('Tab');
  await expect(page.locator('#open-button')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('searchbox')).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(page.locator('#open-button')).toBeFocused();
});

for (const key of ['Enter', 'Space']) {
  test(`${key} on visible Open and Compare buttons opens usable file choosers`, async ({ page }) => {
    const content = tape([{ name: 'keyboard.run' }]);
    await chooseWithKeyboard(page, page.getByRole('button', { name: 'Open tape', exact: true }),
      key, file('keyboard-primary.tape', content));
    await expect(page.locator('#filename')).toHaveText('keyboard-primary.tape');
    await expect(calls(page)).toHaveCount(1);
    await expect(page.locator('#open-file')).toHaveValue('');
    await expect(page.locator('#announcer')).toContainText('Opened keyboard-primary.tape');

    await chooseWithKeyboard(page, page.getByRole('button', { name: 'Compare tape', exact: true }),
      key, file('keyboard-comparison.tape', content));
    await expect(page.locator('#comparison')).toBeVisible();
    await expect(page.locator('#compare-title')).toContainText('all 1 calls match');
    await expect(page.locator('#compare-file')).toHaveValue('');
  });

  test(`${key} clears comparison and returns focus to Compare tape with an announcement`, async ({ page }) => {
    await page.locator('#open-file').setInputFiles(file('primary.tape', tape([{ output: '{"value":1}' }])));
    await expect(calls(page)).toHaveCount(1);
    await page.locator('#compare-file').setInputFiles(file('comparison.tape', tape([{ output: '{"value":2}' }])));
    await expect(page.locator('#comparison')).toBeVisible();
    await expect(page.getByLabel('comparison output', { exact: true })).toContainText('2');
    await page.getByRole('button', { name: 'Clear comparison', exact: true }).focus();
    await page.keyboard.press(key);
    await expect(page.locator('#comparison')).toBeHidden();
    await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Compare tape', exact: true })).toBeFocused();
    await expect(page.locator('#announcer')).toHaveText('Comparison cleared.');
    await expect(calls(page).first()).toHaveAttribute('aria-pressed', 'true');
    await page.keyboard.press('Tab');
    await expect(page.locator('#open-button')).toBeFocused();
  });
}

test('built-in example, filters, selection, and Escape work in a real browser', async ({ page }) => {
  await page.locator('#load-example').click();
  await expect(page.locator('#filename')).toContainText('example');
  await expect(calls(page)).toHaveCount(2);
  await page.getByLabel('Errors only', { exact: true }).check();
  await expect(calls(page)).toHaveCount(1);
  await expect(calls(page).first()).toHaveAccessibleName('Call 0: weather.lookup, ERROR');
  await page.getByRole('searchbox').fill('not-a-tool');
  await expect(page.locator('#timeline')).toContainText('No calls match these filters.');
  await page.getByRole('searchbox').press('Escape');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(page.getByLabel('Errors only', { exact: true })).not.toBeChecked();
  await expect(calls(page)).toHaveCount(2);
  await page.getByRole('searchbox').fill('temperature');
  await expect(calls(page)).toHaveCount(1);
  await calls(page).first().focus();
  await page.keyboard.press('Enter');
  await expect(calls(page).first()).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByLabel('output', { exact: true })).toContainText('22');
  await page.getByRole('searchbox').press('Escape');
  await expect(calls(page)).toHaveCount(2);
});

test('comparison ignores timing, finds drift, clears filters, and resets on a new primary', async ({ page }) => {
  await openFixture(page);
  const original = await fs.readFile(fixture, 'utf8');
  await chooseWithButton(page, page.locator('#compare-button'), file('same.tape', original.replace('48685', '1')));
  await expect(page.locator('#compare-title')).toContainText('all 2 calls match');
  await expect(page.locator('#jump-divergence')).toBeHidden();
  const drift = original.replace(encode('{"temperature":22}'), encode('{"temperature":23}'));
  await page.getByLabel('Open comparison tape', { exact: true }).setInputFiles(file('changed-result.tape', drift));
  await expect(page.locator('#compare-title')).toContainText('position 2');
  await page.getByRole('searchbox').fill('no-matches');
  await page.getByLabel('Errors only', { exact: true }).check();
  await page.locator('#jump-divergence').click();
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(page.getByLabel('Errors only', { exact: true })).not.toBeChecked();
  await expect(calls(page).nth(1)).toHaveAttribute('aria-pressed', 'true');
  await expect(calls(page).nth(1)).toBeFocused();
  await expect(page.getByLabel('output', { exact: true })).toContainText('22');
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('23');
  await page.locator('#clear-compare').click();
  await expect(page.locator('#comparison')).toBeHidden();
  await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
  await page.getByLabel('Open comparison tape', { exact: true }).setInputFiles(file('changed-again.tape', drift));
  await expect(page.locator('#comparison')).toBeVisible();
  await openFixture(page);
  await expect(page.locator('#comparison')).toBeHidden();
});

test('canonical objects and large integers compare without precision loss', async ({ page }) => {
  const left = tape([{ input: '{"z":1.0,"a":9223372036854775807}', output: '{"b":2,"a":1}' }]);
  const equivalent = tape([{ input: '{"a":9223372036854775807,"z":1e0}', output: '{"a":1.0,"b":2}' }]);
  await page.locator('#open-file').setInputFiles(file('exact.tape', left));
  await page.locator('#compare-file').setInputFiles(file('equivalent.tape', equivalent));
  await expect(page.locator('#compare-title')).toContainText('all 1 calls match');
  await expect(page.getByLabel('input', { exact: true })).toContainText('9223372036854775807');
  await page.locator('#compare-file').setInputFiles(file('different-integer.tape', equivalent.replace(encode('{"a":9223372036854775807,"z":1e0}'), encode('{"a":9223372036854775806,"z":1e0}'))));
  await expect(page.locator('#compare-title')).toContainText('position 1');
  await expect(page.locator('#compare-title')).toContainText('input');
});

test('malformed and oversized replacement files preserve the last good run', async ({ page }) => {
  await openFixture(page);
  const cases = [
    [file('truncated.tape', 'TOOLTAPE\t1'), 'final LF newline'],
    [file('invalid-utf8.tape', Buffer.from([0xff, 0xfe])), 'not valid UTF-8'],
    [file('bad-base64.tape', 'TOOLTAPE\t1\n0\t1\t!\te30\tOK\te30\n'), 'invalid base64url'],
    [file('too-large.tape', Buffer.alloc(16 * 1024 * 1024 + 1)), '16 MiB']
  ];
  for (const [input, message] of cases) {
    await page.locator('#open-file').setInputFiles(input);
    await expect(page.getByRole('alert')).toContainText(message);
    await expect(page.locator('#filename')).toHaveText('weather-retry.tape');
    await expect(calls(page)).toHaveCount(2);
  }
  await openFixture(page);
  await expect(page.getByRole('alert')).toBeHidden();
});

test('HTML-like payloads and filenames stay inert text', async ({ page }) => {
  const markup = '<img src="https://example.invalid/x" onerror="window.injected=true"><svg onload="window.injected=true"></svg><script>window.injected=true</script>';
  const content = tape([{ input: JSON.stringify({ markup }), output: JSON.stringify({ result: markup }) }]);
  const filename = '<svg onload=alert(1)>.tape';
  await page.locator('#open-file').setInputFiles(file(filename, content));
  await expect(page.locator('#filename')).toHaveText(filename);
  await expect(page.getByLabel('input', { exact: true })).toContainText('<img');
  await expect(page.getByLabel('output', { exact: true })).toContainText('<script>');
  await expect(page.locator('#inspector img, #inspector svg, #inspector script, #filename svg')).toHaveCount(0);
  expect(await page.evaluate(() => window.injected)).toBeUndefined();
});

test('empty tapes and missing calls remain inspectable', async ({ page }) => {
  await page.locator('#open-file').setInputFiles(file('empty.tape', tape([])));
  await expect(page.locator('#stat-calls')).toHaveText('0');
  await expect(page.locator('#timeline')).toContainText('no recorded calls');
  await page.locator('#compare-file').setInputFiles(fixture);
  await expect(page.locator('#compare-title')).toContainText('call missing from primary');
  await page.locator('#jump-divergence').click();
  await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
  await expect(page.getByLabel('comparison input', { exact: true })).toContainText('Hangzhou');
  await expect(page.locator('#inspector')).toBeFocused();
});

test('drop accepts one real File and rejects multiple files without losing the run', async ({ page }) => {
  const content = await fs.readFile(fixture, 'utf8');
  await page.evaluate(text => {
    const transfer = new DataTransfer();
    transfer.items.add(new File([text], 'dropped.tape', { type: 'text/plain' }));
    document.dispatchEvent(new DragEvent('dragenter', { bubbles: true, dataTransfer: transfer }));
    document.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
  }, content);
  await expect(page.locator('#filename')).toHaveText('dropped.tape');
  await expect(calls(page)).toHaveCount(2);
  await expect(page.locator('body')).not.toHaveClass(/drop-active/);
  await page.evaluate(text => {
    const transfer = new DataTransfer();
    for (const name of ['first.tape', 'second.tape']) transfer.items.add(new File([text], name));
    document.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
  }, content);
  await expect(page.getByRole('alert')).toContainText('Drop one .tape file at a time');
  await expect(page.locator('#filename')).toHaveText('dropped.tape');
});

test('a stale real-file read cannot overwrite a newer selection', async ({ page }) => {
  await page.evaluate(() => {
    const original = File.prototype.arrayBuffer;
    File.prototype.arrayBuffer = function () {
      const bytes = original.call(this);
      if (this.name !== 'delayed.tape') return bytes;
      return new Promise(resolve => { window.releaseDelayedRead = () => bytes.then(resolve); });
    };
  });
  await page.locator('#open-file').setInputFiles(file('delayed.tape', await fs.readFile(fixture)));
  await page.locator('#open-file').setInputFiles(file('newer.tape', tape([{ name: 'newer.run' }])));
  await expect(page.locator('#filename')).toHaveText('newer.tape');
  await page.evaluate(() => window.releaseDelayedRead());
  await expect(page.locator('#filename')).toHaveText('newer.tape');
  await expect(calls(page)).toHaveCount(1);
  await expect(calls(page).first()).toHaveAccessibleName('Call 0: newer.run, OK');
});

for (const count of [251, 501]) {
  for (const key of ['Enter', 'Space']) {
    test(`${key} pagination keeps focus and Tab order across ${count} calls`, async ({ page }) => {
      const rows = Array.from({ length: count }, (_, index) => ({ name: 'step.' + index }));
      await page.locator('#open-file').setInputFiles(file('many.tape', tape(rows)));
      await expect(page.locator('#stat-calls')).toHaveText(String(count));
      await expect(calls(page)).toHaveCount(250);
      await calls(page).nth(249).focus();
      await page.keyboard.press('Tab');
      const more = page.getByRole('button', { name: `Show next ${Math.min(250, count - 250)} calls`, exact: true });
      await expect(more).toBeFocused();
      await page.keyboard.press(key);
      await expect(calls(page)).toHaveCount(Math.min(500, count));
      await expect(calls(page).nth(250)).toBeFocused();
      await expect(calls(page).nth(250)).toHaveAttribute('aria-pressed', 'false');
      await expect(calls(page).first()).toHaveAttribute('aria-pressed', 'true');
      await expect(page.locator('#inspector h2')).toHaveText('step.0');

      if (count === 501) {
        await page.keyboard.press('Tab');
        await expect(calls(page).nth(251)).toBeFocused();
        await calls(page).nth(499).focus();
        await page.keyboard.press('Tab');
        await expect(page.getByRole('button', { name: 'Show next 1 calls', exact: true })).toBeFocused();
        await page.keyboard.press(key);
        await expect(calls(page)).toHaveCount(501);
        await expect(calls(page).nth(500)).toBeFocused();
        await expect(calls(page).nth(500)).toHaveAttribute('aria-pressed', 'false');
        await expect(calls(page).first()).toHaveAttribute('aria-pressed', 'true');
      }

      await expect(page.getByRole('button', { name: /^Show next / })).toHaveCount(0);
      await page.keyboard.press('Tab');
      await expect(page.getByLabel('input', { exact: true })).toBeFocused();
      await page.keyboard.press('Shift+Tab');
      await expect(calls(page).last()).toBeFocused();
    });
  }
}

test('Escape without filters preserves the focused row and a different selected row', async ({ page }) => {
  await page.locator('#open-file').setInputFiles(file('selection.tape', tape([
    { name: 'first.run' }, { name: 'selected.run' }
  ])));
  await calls(page).nth(1).focus();
  await page.keyboard.press('Enter');
  await calls(page).first().focus();
  await page.keyboard.press('Escape');
  await expect(calls(page)).toHaveCount(2);
  await expect(calls(page).first()).toBeFocused();
  await expect(calls(page).first()).toHaveAttribute('aria-pressed', 'false');
  await expect(calls(page).nth(1)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#inspector h2')).toHaveText('selected.run');
  await page.keyboard.press('Tab');
  await expect(calls(page).nth(1)).toBeFocused();
});

for (const focusedControl of ['search', 'errors-only']) {
  test(`Escape clears filters while preserving ${focusedControl} focus and a selected call beyond 250`, async ({ page }) => {
    const rows = Array.from({ length: 251 }, (_, index) => ({
      name: 'step.' + index,
      status: index === 250 ? 'ERROR' : 'OK'
    }));
    await page.locator('#open-file').setInputFiles(file('filtered.tape', tape(rows)));
    await page.getByRole('searchbox').fill('step.250');
    await page.getByLabel('Errors only', { exact: true }).check();
    await expect(calls(page)).toHaveCount(1);
    await calls(page).first().focus();
    await page.keyboard.press('Enter');
    await page.locator('#' + focusedControl).focus();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('searchbox')).toHaveValue('');
    await expect(page.getByLabel('Errors only', { exact: true })).not.toBeChecked();
    await expect(page.locator('#' + focusedControl)).toBeFocused();
    await expect(calls(page)).toHaveCount(251);
    await expect(calls(page).nth(250)).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('#inspector h2')).toHaveText('step.250');
    await page.keyboard.press('Tab');
    await expect(focusedControl === 'search' ? page.locator('#errors-only') : calls(page).first()).toBeFocused();
  });
}

test('Escape from a filtered row retains focused and selected calls beyond 250', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({
    name: (index >= 499 ? 'tail.' : 'step.') + index,
    status: index >= 499 ? 'ERROR' : 'OK'
  }));
  await page.locator('#open-file').setInputFiles(file('filtered-tail.tape', tape(rows)));
  await page.getByRole('searchbox').fill('tail.');
  await page.getByLabel('Errors only', { exact: true }).check();
  await expect(calls(page)).toHaveCount(2);
  await calls(page).nth(1).focus();
  await page.keyboard.press('Enter');
  await calls(page).first().focus();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(page.getByLabel('Errors only', { exact: true })).not.toBeChecked();
  await expect(calls(page)).toHaveCount(501);
  await expect(calls(page).nth(499)).toBeFocused();
  await expect(calls(page).nth(499)).toHaveAttribute('aria-pressed', 'false');
  await expect(calls(page).nth(500)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#inspector h2')).toHaveText('tail.500');
  await page.keyboard.press('Tab');
  await expect(calls(page).nth(500)).toBeFocused();
});

test('Escape clears filters and restores focus to the replacement Show next button', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({ name: 'step.' + index }));
  await page.locator('#open-file').setInputFiles(file('filtered-pagination.tape', tape(rows)));
  await page.getByRole('searchbox').fill('step.');
  await expect(calls(page)).toHaveCount(250);
  await page.getByRole('button', { name: 'Show next 250 calls', exact: true }).focus();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(calls(page)).toHaveCount(250);
  await expect(page.getByRole('button', { name: 'Show next 250 calls', exact: true })).toBeFocused();
  await expect(calls(page).first()).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Space');
  await expect(calls(page)).toHaveCount(500);
  await expect(calls(page).nth(250)).toBeFocused();
});

test('keyboard divergence navigation beyond 250 survives Escape and continues pagination', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({ name: 'step.' + index }));
  await page.locator('#open-file').setInputFiles(file('primary-many.tape', tape(rows)));
  await expect(calls(page)).toHaveCount(250);
  const changed = rows.map((row, index) => index === 400 ? { ...row, output: '{"changed":true}' } : row);
  await page.locator('#compare-file').setInputFiles(file('comparison-many.tape', tape(changed)));
  await expect(page.locator('#compare-title')).toContainText('position 401');
  await page.getByRole('searchbox').fill('no-matches');
  await page.getByLabel('Errors only', { exact: true }).check();
  await page.getByRole('button', { name: 'Inspect divergence', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(page.getByLabel('Errors only', { exact: true })).not.toBeChecked();
  await expect(calls(page)).toHaveCount(401);
  await expect(calls(page).nth(400)).toBeFocused();
  await expect(calls(page).nth(400)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('true');
  await page.keyboard.press('Escape');
  await expect(calls(page)).toHaveCount(401);
  await expect(calls(page).nth(400)).toBeFocused();
  await expect(calls(page).nth(400)).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Show next 100 calls', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(calls(page)).toHaveCount(501);
  await expect(calls(page).nth(401)).toBeFocused();
  await expect(calls(page).nth(400)).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Tab');
  await expect(calls(page).nth(402)).toBeFocused();
});

test('v2 closure, provenance, and policy warnings are visible and distinguish incomplete recordings', async ({ page, browser }, testInfo) => {
  const original = await fs.readFile(sessionFixture, 'utf8');
  const metadataField = original.split('\n')[1].split('\t')[1];
  const metadata = JSON.parse(Buffer.from(metadataField, 'base64url').toString('utf8'));
  await page.locator('#open-file').setInputFiles(sessionFixture);
  await expect(page.locator('#recording-schema')).toHaveText('TOOLTAPE v2');
  await expect(page.locator('#recording-completeness')).toHaveText('SESSION CLOSED');
  await expect(page.locator('#recording-source')).toHaveText(metadata.source);
  await expect(page.locator('#recording-policy')).toHaveAttribute('title', metadata.policyId);
  await expect(page.locator('#recording-note')).toContainText('does not prove business success');
  await expect(calls(page)).toHaveCount(2);
  if (testInfo.project.name.startsWith('desktop')) {
    await capture(page, browser, testInfo, 'viewer-session',
      'fixtures/weather-retry-v2.tape, unchanged, showing session closure and recorded provenance', sessionFixture);
  }
  const changedMetadata = encode(JSON.stringify({ policyId: 'sha256:' + '0'.repeat(64), source: metadata.source }));
  await page.locator('#compare-file').setInputFiles(file('different-policy.tape', original.replace(metadataField, changedMetadata)));
  await expect(page.locator('#compare-title')).toContainText('all 2 calls match');
  await expect(page.locator('#compare-policy-warning')).toContainText('Different capture policies');
  await page.locator('#open-file').setInputFiles(file('incomplete-session.tape', original.replace(/END\t2\n$/, '')));
  await expect(page.locator('#recording-completeness')).toHaveText('INCOMPLETE');
  await expect(page.locator('#recording-note')).toContainText('cannot be used for safe replay');
  await expect(calls(page)).toHaveCount(2);
  await expect(page.locator('#comparison')).toBeHidden();
  await openFixture(page);
  await expect(page.locator('#recording-completeness')).toHaveText('LEGACY · UNKNOWN');
});

test('layout fits its viewport and produces the actual baseline screenshot', async ({ page, browser }, testInfo) => {
  await openFixture(page);
  await expect(page.getByLabel('recorded exception', { exact: true })).toHaveText('java.io.IOException');
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    timeline: document.getElementById('timeline-pane').getBoundingClientRect().toJSON(),
    inspector: document.getElementById('inspector').getBoundingClientRect().toJSON()
  }));
  expect(dimensions.document, 'no horizontal page overflow').toBeLessThanOrEqual(dimensions.viewport);
  if (testInfo.project.name.startsWith('mobile')) {
    expect(dimensions.inspector.top).toBeGreaterThanOrEqual(dimensions.timeline.bottom - 1);
  } else {
    expect(dimensions.inspector.left).toBeGreaterThanOrEqual(dimensions.timeline.right - 1);
  }
  await capture(page, browser, testInfo,
    testInfo.project.name.startsWith('mobile') ? 'viewer-mobile' : 'viewer-baseline',
    'fixtures/weather-retry.tape, unchanged, first recorded exception selected');
});

test('produces an actual semantic-diff screenshot', async ({ page, browser }, testInfo) => {
  test.skip(testInfo.project.name.startsWith('mobile'), 'The mobile artifact is captured in the responsive baseline test.');
  await openFixture(page);
  const original = await fs.readFile(fixture, 'utf8');
  const changed = original.replace(encode('{"temperature":22}'), encode('{"temperature":23}'));
  await page.locator('#compare-file').setInputFiles(file('weather-changed-result.tape', changed));
  await expect(page.locator('#compare-title')).toContainText('position 2');
  await page.locator('#jump-divergence').click();
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('23');
  await capture(page, browser, testInfo, 'viewer-diff',
    'fixtures/weather-retry.tape compared with a synthetic one-field regression: successful temperature output changed from 22 to 23');
});
