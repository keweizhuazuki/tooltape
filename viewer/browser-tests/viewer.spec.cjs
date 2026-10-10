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
const callAt = (page, position) => page.locator('#timeline button[data-position="' + position + '"]');
const previousPage = page => page.getByRole('button', { name: 'Previous page', exact: true });
const nextPage = page => page.getByRole('button', { name: 'Next page', exact: true });
const skipPages = page => page.getByRole('button', { name: 'Skip to page controls', exact: true });
const skipDetails = page => page.getByRole('button', { name: 'Skip to call details', exact: true });

async function expectVisibleKeyboardFocus(locator) {
  await expect(locator).toBeFocused();
  await expect(locator).toBeVisible();
  await expect(locator).toBeInViewport();
  const focus = await locator.evaluate(element => {
    const style = getComputedStyle(element);
    return {
      visible: element.matches(':focus-visible'),
      outlineStyle: style.outlineStyle,
      outlineWidth: parseFloat(style.outlineWidth),
      outlineColor: style.outlineColor
    };
  });
  expect(focus.visible, 'keyboard focus is visibly indicated').toBe(true);
  expect(focus.outlineStyle, 'focus has a real outline').not.toBe('none');
  expect(focus.outlineWidth, 'focus outline is at least 2px').toBeGreaterThanOrEqual(2);
  expect(focus.outlineColor, 'focus outline is not transparent').not.toMatch(/^(transparent|rgba\([^)]*,\s*0\))$/);
}

async function passNativeTimelineStop(page, key) {
  // Firefox can put the overflowing list itself in the native Tab sequence.
  // Permit only that exact, actually overflowing container, never an arbitrary
  // extra stop or a direct focus jump that could hide a long keyboard path.
  if (await page.locator('#timeline').evaluate(element => element === document.activeElement)) {
    expect(await page.locator('#timeline').evaluate(element => element.scrollHeight > element.clientHeight),
      'the native timeline Tab stop belongs to an overflowing scroll container').toBe(true);
    await page.keyboard.press(key);
  }
}

async function shortcutFromFirstCall(page, destination) {
  await expect(calls(page).first()).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await passNativeTimelineStop(page, 'Shift+Tab');
  await expectVisibleKeyboardFocus(skipDetails(page));
  if (destination === 'pages') {
    await page.keyboard.press('Shift+Tab');
    await expectVisibleKeyboardFocus(skipPages(page));
  }
}

async function detailsFromFilters(page, key) {
  await page.getByRole('searchbox').focus();
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Errors only', { exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expectVisibleKeyboardFocus(skipDetails(page));
  await page.keyboard.press(key);
  await expectVisibleKeyboardFocus(page.locator('#inspector'));
}


async function expectTimelinePage(page, start, end, total, firstPosition = start - 1, lastPosition = end - 1) {
  const count = total ? end - start + 1 : 0;
  await expect(calls(page)).toHaveCount(count);
  expect(await calls(page).count(), 'at most 250 call cards are mounted').toBeLessThanOrEqual(250);
  await expect(page.locator('#visible-count')).toHaveText(total
    ? `${start}–${end} of ${total} matching calls`
    : '0 of 0 matching calls');
  if (count) {
    await expect(calls(page).first()).toHaveAttribute('data-position', String(firstPosition));
    await expect(calls(page).last()).toHaveAttribute('data-position', String(lastPosition));
  }
  if (total > 250) {
    await expect(page.locator('#timeline-pagination')).toBeVisible();
    if (start === 1) await expect(previousPage(page)).toBeDisabled();
    else await expect(previousPage(page)).toBeEnabled();
    if (end === total) await expect(nextPage(page)).toBeDisabled();
    else await expect(nextPage(page)).toBeEnabled();
  } else {
    await expect(page.locator('#timeline-pagination')).toBeHidden();
  }
}

// Evaluate actual computed colors, including transparent ancestor backgrounds and
// group opacity. This is intentionally limited to the viewer's solid backgrounds;
// fail loudly if a gradient, image, blend, or filter makes this model unsuitable.
function measureTextContrast(element, pseudo = null) {
  const describe = node => node.tagName.toLowerCase() + (node.id ? '#' + node.id : '') +
    (node.classList.length ? '.' + [...node.classList].join('.') : '');
  const parse = value => {
    const match = value.match(/^rgba?\(([^)]+)\)$/);
    if (!match) throw new Error('Unsupported computed color: ' + value);
    const parts = match[1].trim().split(/\s*[,/]\s*|\s+/);
    if (parts.length !== 3 && parts.length !== 4) throw new Error('Unsupported computed color: ' + value);
    return parts.map((part, index) => {
      const number = Number(part.replace(/%$/, ''));
      const result = part.endsWith('%') ? number / 100 : index < 3 ? number / 255 : number;
      if (!Number.isFinite(result) || result < 0 || result > 1) throw new Error('Invalid computed color: ' + value);
      return result;
    }).concat(parts.length === 3 ? [1] : []);
  };
  const over = (front, back) => {
    const alpha = front[3] + back[3] * (1 - front[3]);
    return alpha === 0 ? [0, 0, 0, 0] : [0, 1, 2].map(index =>
      (front[index] * front[3] + back[index] * back[3] * (1 - front[3])) / alpha).concat(alpha);
  };
  const layers = [];
  const addLayer = (style, label) => {
    for (const property of ['backgroundImage', 'filter', 'backdropFilter', 'textShadow']) {
      if (style[property] && style[property] !== 'none') {
        throw new Error('Unsupported ' + property + ' at ' + label + ': ' + style[property]);
      }
    }
    for (const property of ['mixBlendMode', 'backgroundBlendMode']) {
      if (style[property] && style[property] !== 'normal') {
        throw new Error('Unsupported ' + property + ' at ' + label + ': ' + style[property]);
      }
    }
    if (style.backgroundClip === 'text') throw new Error('Unsupported backgroundClip at ' + label + ': text');
    const opacity = Number(style.opacity);
    if (!Number.isFinite(opacity) || opacity < 0 || opacity > 1) throw new Error('Invalid opacity at ' + label);
    layers.push({ label, background: parse(style.backgroundColor), opacity });
  };
  const textStyle = getComputedStyle(element, pseudo);
  if (pseudo) addLayer(textStyle, describe(element) + pseudo);
  for (let node = element; node; node = node.parentElement) addLayer(getComputedStyle(node), describe(node));
  const sourceForeground = parse(textStyle.webkitTextFillColor || textStyle.color);
  let foreground = sourceForeground, background = [0, 0, 0, 0];
  // Paint the text and an adjacent background pixel through the same opacity
  // groups, from the innermost element to the opaque page background.
  for (const layer of layers) {
    foreground = over(foreground, layer.background);
    background = over(background, layer.background);
    foreground[3] *= layer.opacity;
    background[3] *= layer.opacity;
  }
  if (foreground[3] !== 1 || background[3] !== 1) {
    throw new Error('No opaque page background for ' + describe(element) + '; cannot assume a canvas color');
  }
  const luminance = color => color.slice(0, 3).map(channel =>
    channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
    .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
  const first = luminance(foreground), second = luminance(background);
  return {
    text: pseudo === '::placeholder' ? element.getAttribute('placeholder') : element.value || element.textContent,
    sourceForeground, foreground, background, layers,
    ratio: (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
  };
}

async function expectTextContrast(locator, label, samples, { count = 1, pseudo = null } = {}) {
  await expect(locator, label + ' exists').toHaveCount(count);
  for (let index = 0; index < count; index++) {
    const item = locator.nth(index);
    await expect(item, label + ' is visible').toBeVisible();
    const measurement = await item.evaluate(measureTextContrast, pseudo);
    expect(measurement.text.trim(), label + ' contains real text').not.toBe('');
    samples.push({ label, index, ...measurement });
    // Compare the unrounded result: 4.499... is a failure, not a rounded pass.
    expect(measurement.ratio, label + ': ' + JSON.stringify(measurement)).toBeGreaterThanOrEqual(4.5);
  }
}

async function attachContrastMeasurements(testInfo, samples) {
  await testInfo.attach('computed-text-contrast', {
    body: Buffer.from(JSON.stringify({ project: testInfo.project.name, minimum: 4.5, samples }, null, 2)),
    contentType: 'application/json'
  });
}

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

async function deferFileReads(page, names) {
  await page.evaluate(names => {
    const original = File.prototype.arrayBuffer;
    const reads = new Map();
    window.__tooltapeDeferredReads = reads;
    File.prototype.arrayBuffer = function () {
      const bytes = original.call(this);
      if (!names.includes(this.name)) return bytes;
      let release;
      const gate = new Promise(resolve => { release = resolve; });
      const result = Promise.all([bytes, gate]).then(([buffer, outcome]) => {
        if (outcome === 'reject') throw new Error('Deferred file read failed: ' + this.name);
        return buffer;
      });
      // Observe both outcomes immediately. Awaiting settled and one microtask
      // after release also lets readFile's already-attached consumer finish.
      reads.set(this.name, { release, settled: result.then(() => {}, () => {}) });
      return result;
    };
  }, names);
}

async function releaseFileRead(page, name, outcome = 'resolve') {
  await page.evaluate(async ({ name, outcome }) => {
    const read = window.__tooltapeDeferredReads.get(name);
    if (!read) throw new Error('The deferred File read did not start: ' + name);
    read.release(outcome);
    await read.settled;
    await Promise.resolve();
    window.__tooltapeDeferredReads.delete(name);
  }, { name, outcome });
}

async function capture(page, browser, testInfo, name, fixtureDescription, sourceFixture = fixture, fixtureEvidence = null) {
  const directory = path.join(__dirname, 'artifacts/screenshots', testInfo.project.name);
  await fs.mkdir(directory, { recursive: true });
  await page.evaluate(() => {
    document.activeElement?.blur();
    window.scrollTo(0, 0);
  });
  const screenshot = path.join(directory, name + '.png');
  const screenshotBytes = await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' });
  const sha256 = data => createHash('sha256').update(data).digest('hex');
  const browserName = browser.browserType().name();
  const rendering = await page.evaluate(() => ({
    deviceScaleFactor: window.devicePixelRatio,
    userAgent: navigator.userAgent
  }));
  const provenance = path.join(directory, name + '.json');
  await fs.writeFile(provenance, JSON.stringify({
    screenshot: name + '.png',
    screenshotSha256: sha256(screenshotBytes),
    kind: 'actual ' + browserName + ' rendering, no image compositing',
    viewer: 'viewer/index.html',
    viewerSha256: sha256(await fs.readFile(viewer)),
    fixture: fixtureDescription,
    sourceFixture: path.relative(root, sourceFixture).split(path.sep).join('/'),
    sourceFixtureSha256: sha256(await fs.readFile(sourceFixture)),
    ...(fixtureEvidence ? { fixtureEvidence } : {}),
    commit: process.env.GITHUB_SHA || null,
    workflowRun: process.env.GITHUB_RUN_ID || null,
    playwright: require('@playwright/test/package.json').version,
    browserName,
    browser: browser.version(),
    project: testInfo.project.name,
    viewport: page.viewportSize(),
    isMobile: Boolean(testInfo.project.use.isMobile),
    hasTouch: Boolean(testInfo.project.use.hasTouch),
    ...rendering
  }, null, 2) + '\n');
  await testInfo.attach(name, { path: screenshot, contentType: 'image/png' });
  await testInfo.attach(name + '-provenance', { path: provenance, contentType: 'application/json' });
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

test('contrast measurement composites alpha and group opacity and rejects background images', async ({ page }) => {
  await page.evaluate(() => {
    const outer = document.createElement('div');
    outer.style.backgroundColor = 'rgb(0, 0, 0)';
    const group = document.createElement('div');
    group.style.cssText = 'background-color: rgba(255, 255, 255, .5); opacity: .5';
    const text = document.createElement('span');
    text.id = 'contrast-probe';
    text.textContent = 'Measured text';
    text.style.cssText = 'background-color: rgba(0, 0, 0, .5); color: rgba(255, 255, 255, .5); opacity: .5';
    group.append(text);
    outer.append(group);
    document.body.append(outer);
  });
  const probe = page.locator('#contrast-probe');
  const translucent = await probe.evaluate(measureTextContrast);
  expect(translucent.foreground).toEqual([0.28125, 0.28125, 0.28125, 1]);
  expect(translucent.background).toEqual([0.1875, 0.1875, 0.1875, 1]);
  expect(translucent.ratio).toBeCloseTo(1.4405324327872426, 12);
  await probe.evaluate(element => {
    element.style.cssText = 'color: white';
    element.parentElement.style.cssText = '';
  });
  expect((await probe.evaluate(measureTextContrast)).ratio).toBe(21);
  await probe.evaluate(element => {
    element.parentElement.style.backgroundImage = 'linear-gradient(black, white)';
  });
  await expect(probe.evaluate(measureTextContrast)).rejects.toThrow(/Unsupported backgroundImage/);
});

test('small labels and supporting text clear 4.5:1 on their rendered dark backgrounds', async ({ page }, testInfo) => {
  const samples = [];
  const check = (selector, count = 1, state = 'loaded') =>
    expectTextContrast(page.locator(selector), state + ' ' + selector, samples, { count });
  for (const selector of ['.empty-hint', '.empty p', '#load-example', '#empty-open']) {
    await check(selector, 1, 'empty');
  }
  await page.locator('#open-file').setInputFiles(sessionFixture);
  await expect(page.locator('#stat-calls')).toHaveText('2');
  // Disabled Compare on the empty screen is an inactive control, not sampled.
  // All text below is visible and enabled. The mobile layout hides .version.
  if (testInfo.project.name.startsWith('mobile')) await expect(page.locator('.version')).toBeHidden();
  else await check('.version');
  for (const [selector, count] of [
    ['.local', 1], ['.eyebrow', 1], ['.intro', 1], ['#open-button', 1], ['#compare-button', 1],
    ['.recording-label', 4], ['.recording-value', 4], ['#recording-note', 1], ['.recording-provenance', 1],
    ['.stat-label', 4], ['.stat-note', 4], ['.stat-unit', 2], ['#filename', 1], ['#timeline-count', 1],
    ['.error-toggle', 1], ['.timeline-foot > span', 2], ['.inspect-label', 1], ['.inspect-meta > span', 2],
    ['.payload-heading h3', 2], ['.payload-kind', 2], ['.code', 2], ['.inspect-top .status', 1],
    ['.footer > span', 2], ['.footer strong', 1], ['.footer .key', 1], ['summary', 1]
  ]) await check(selector, count);

  await page.locator('#compare-file').setInputFiles(sessionFixture);
  await expect(page.locator('#comparison')).toBeVisible();
  await expect(page.locator('#comparison')).not.toHaveClass(/different/);
  await check('#compare-title', 1, 'matching comparison');
  await check('#comparison .compare-note', 2, 'matching comparison');
  await check('#clear-compare', 1, 'matching comparison');
  await check('#inspector .compare-note', 1, 'matching comparison');

  const original = await fs.readFile(sessionFixture, 'utf8');
  const metadataField = original.split('\n')[1].split('\t')[1];
  const metadata = JSON.parse(Buffer.from(metadataField, 'base64url').toString('utf8'));
  const changed = original.replace(metadataField, encode(JSON.stringify({ ...metadata, policyId: 'sha256:' + '0'.repeat(64) })))
    .replace(encode('{"temperature":22}'), encode('{"temperature":23}'));
  await page.locator('#compare-file').setInputFiles(file('contrast-drift.tape', changed));
  await expect(page.locator('#comparison')).toHaveClass(/different/);
  await expect(page.locator('#compare-policy-warning')).toBeVisible();
  await check('#compare-title', 1, 'different comparison');
  await check('#comparison .compare-note', 2, 'different comparison');
  await check('#compare-policy-warning', 1, 'different comparison');
  await check('#jump-divergence', 1, 'different comparison');
  await check('#clear-compare', 1, 'different comparison');

  await page.locator('#open-file').setInputFiles(file('incomplete.tape', original.replace(/END\t2\n$/, '')));
  await expect(page.locator('#recording')).toHaveClass(/incomplete/);
  await check('#recording-completeness', 1, 'incomplete recording');
  await check('#recording-note', 1, 'incomplete recording');
  await check('.recording-provenance', 1, 'incomplete recording');
  await page.locator('#open-file').setInputFiles(file('invalid.tape', 'TOOLTAPE\t1'));
  await expect(page.locator('#notice')).toBeVisible();
  await check('#notice', 1, 'validation error');
  await attachContrastMeasurements(testInfo, samples);
});

test('call preview, index, duration, and status clear 4.5:1 in normal, hover, selected, and focus states', async ({ page }, testInfo) => {
  await openFixture(page);
  const samples = [];
  const first = calls(page).first(), second = calls(page).nth(1);
  const check = async (row, state) => {
    for (const selector of ['.event-index', '.event-name', '.event-summary', '.duration', '.status']) {
      await expectTextContrast(row.locator(selector), state + ' ' + selector, samples);
    }
  };
  await page.mouse.move(0, 0);
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await expect(second).toHaveAttribute('aria-pressed', 'false');
  await check(first, 'selected error row');
  await check(second, 'unselected success row');
  await second.hover();
  expect(await second.evaluate(element => element.matches(':hover'))).toBe(true);
  await expect(second).toHaveAttribute('aria-pressed', 'false');
  await check(second, 'unselected:hover');

  await page.mouse.move(0, 0);
  await first.focus();
  await page.keyboard.press('Tab');
  await expect(second).toBeFocused();
  expect(await second.evaluate(element => element.matches(':focus-visible'))).toBe(true);
  await expect(second).toHaveAttribute('aria-pressed', 'false');
  await check(second, 'unselected:focus-visible');
  await page.keyboard.press('Enter');
  await expect(second).toHaveAttribute('aria-pressed', 'true');
  await expect(second).toBeFocused();
  expect(await second.evaluate(element => element.matches(':focus-visible'))).toBe(true);
  await check(second, 'selected:focus-visible');
  await second.hover();
  expect(await second.evaluate(element => element.matches(':hover'))).toBe(true);
  await check(second, 'selected:hover');
  await page.getByRole('searchbox').focus();
  await page.mouse.move(0, 0);
  await check(second, 'selected success row');
  await expect(first).toHaveAttribute('aria-pressed', 'false');
  await check(first, 'unselected error row');
  await attachContrastMeasurements(testInfo, samples);
});

test('search placeholder and disclosure text clear 4.5:1 during hover and keyboard focus', async ({ page }, testInfo) => {
  await openFixture(page);
  const samples = [];
  const search = page.getByRole('searchbox'), summary = page.locator('summary');
  const checkPlaceholder = state => expectTextContrast(search, state + ' search::placeholder', samples, { pseudo: '::placeholder' });
  await checkPlaceholder('normal');
  await search.hover();
  expect(await search.evaluate(element => element.matches(':hover'))).toBe(true);
  await checkPlaceholder('hover');
  await page.mouse.move(0, 0);
  await page.locator('#open-button').focus();
  await page.keyboard.press('Tab');
  await expect(search).toBeFocused();
  expect(await search.evaluate(element => element.matches(':focus-visible'))).toBe(true);
  await checkPlaceholder('focus-visible');
  await search.fill('weather');
  await expectTextContrast(search, 'entered search text', samples);
  await search.fill('no matching tool');
  await expectTextContrast(page.locator('.no-results'), 'empty filter result', samples);
  await search.press('Escape');

  await summary.hover();
  expect(await summary.evaluate(element => element.matches(':hover'))).toBe(true);
  await expectTextContrast(summary, 'summary:hover', samples);
  await page.mouse.move(0, 0);
  await page.getByLabel('recorded exception', { exact: true }).focus();
  await page.keyboard.press('Tab');
  await expect(summary).toBeFocused();
  expect(await summary.evaluate(element => element.matches(':focus-visible'))).toBe(true);
  await expectTextContrast(summary, 'summary:focus-visible', samples);
  await page.keyboard.press('Enter');
  await expect(page.locator('details')).toHaveAttribute('open', '');
  await expectTextContrast(summary, 'expanded summary:focus-visible', samples);
  await expectTextContrast(page.locator('details p'), 'expanded disclosure text', samples);
  await attachContrastMeasurements(testInfo, samples);
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

test('Unicode payload search decodes JSON strings once and preserves canonical text and exact numbers', async ({ page, browser }, testInfo) => {
  const rows = [
    { name: 'unicode.bmp', input: JSON.stringify({ '城市': '杭州' }), output: JSON.stringify({ answer: 'CAFÉ' }) },
    { name: 'unicode.emoji', input: JSON.stringify({ route: '🚀 👩‍💻' }), output: JSON.stringify({ '🧭': '🌍' }) },
    { name: 'unicode.literal', input: JSON.stringify({ literal: String.raw`\ud83d\ude80` }) },
    { name: 'unicode.escaped', input: String.raw`{"message":"\uD83D\uDE80"}`, output: String.raw`{"city":"\u6771\u4eac"}` },
    { name: 'unicode.case', input: JSON.stringify({ word: 'ÉCOLE', letter: '𐐀' }), output: JSON.stringify({ word: 'Straße' }) },
    { name: 'unicode.composed', input: JSON.stringify({ value: 'café' }) },
    { name: 'unicode.decomposed', input: JSON.stringify({ value: 'cafe\u0301' }) },
    { name: 'unicode.numbers', input: '{"exact":9223372036854775807,"marker":"🚀"}', output: '{"exact":9223372036854775806}' },
    { name: 'unicode.key', input: JSON.stringify({ '🔍': 'value' }) }
  ];
  // Ordinary UTF-8 file input, with no parser/state replacement or search helper calls.
  await page.getByLabel('Open primary tape', { exact: true }).setInputFiles(file('unicode-search.tape', tape(rows)));
  await expect(page.locator('#stat-calls')).toHaveText('9');
  await expect(page.getByRole('alert')).toBeHidden();
  const search = page.getByRole('searchbox');
  for (const [query, positions] of [
    ['城市', [0]], ['杭州', [0]], ['東京', [3]],
    ['🚀', [1, 3, 7]], ['👩‍💻', [1]], ['🧭', [1]], ['🌍', [1]], ['🔍', [8]],
    // The legacy escaped spelling is still searchable, including case folding.
    [String.raw`"route":"\uD83D\uDE80`, [1]],
    [String.raw`"literal":"\\ud83d\\ude80"`, [2]],
    // JavaScript lowercase matching, without full case folding or normalization.
    ['école', [4]], ['𐐨', [4]], ['straße', [4]], ['STRASSE', []],
    ['café', [0, 5]], ['cafe\u0301', [6]],
    ['9223372036854775807', [7]], ['9223372036854775806', [7]],
    ['9223372036854775807,"marker":"🚀"', [7]], ['9223372036854776000', []]
  ]) {
    await search.fill(query);
    await expect(calls(page), 'matching calls for ' + JSON.stringify(query)).toHaveCount(positions.length);
    expect(await calls(page).evaluateAll(elements => elements.map(element => Number(element.dataset.position))),
      'matching positions for ' + JSON.stringify(query)).toEqual(positions);
    await expect(page.locator('#visible-count')).toHaveText(positions.length
      ? `1–${positions.length} of ${positions.length} matching calls`
      : '0 of 0 matching calls');
  }
  await search.fill('🚀');
  await callAt(page, 1).focus();
  await page.keyboard.press('Enter');
  await expect(callAt(page, 1)).toHaveAttribute('aria-pressed', 'true');
  const input = page.getByLabel('input', { exact: true });
  // Search must not change the inspector into a decoded or reserialized payload.
  await expect(input).toContainText(String.raw`\ud83d\ude80`);
  await expect(input).not.toContainText('🚀');
  await expect(page.getByLabel('output', { exact: true })).toContainText(String.raw`\ud83e\udded`);
  if (testInfo.project.name === 'desktop-chromium') {
    const directory = path.join(__dirname, 'artifacts/screenshots', testInfo.project.name);
    await fs.mkdir(directory, { recursive: true });
    const fixturePath = path.join(directory, 'unicode-search.tape');
    await fs.writeFile(fixturePath, tape(rows));
    await capture(page, browser, testInfo, 'viewer-unicode-search',
      'Synthetic nine-call v1 tape; rocket query matches three original Unicode payloads while the selected inspector preserves canonical escaped JSON.',
      fixturePath, { query: '🚀', matchingPositions: [1, 3, 7], selectedPosition: 1 });
  }
  await callAt(page, 7).click();
  await expect(input).toContainText('9223372036854775807');
  await expect(input).toContainText(String.raw`\ud83d\ude80`);
  await expect(page.getByLabel('output', { exact: true })).toContainText('9223372036854775806');
  const equivalent = rows.map((row, index) => index === 1 ? {
    ...row, input: String.raw`{"route":"\ud83d\ude80 \ud83d\udc69\u200d\ud83d\udcbb"}`
  } : row);
  await page.getByLabel('Open comparison tape', { exact: true }).setInputFiles(file('unicode-equivalent.tape', tape(equivalent)));
  await expect(page.locator('#compare-title')).toHaveText('Same recorded behavior · all 9 calls match');
  await expect(page.locator('#jump-divergence')).toBeHidden();
  await expect(input).toContainText('9223372036854775807');
});

test('Unicode search keeps bounded pages, selection, focus, Errors only, and Escape across 501 matches', async ({ page }) => {
  // 501 actual emoji payloads, 250 literal-backslash traps, and 251 matching
  // errors ensure both search and Errors only exercise more than one page.
  const rows = Array.from({ length: 751 }, (_, index) => ({
    name: 'unicode.step.' + index,
    input: JSON.stringify({ message: index % 3 === 2 ? String.raw`\ud83d\ude80` : '杭州 🚀 👩‍💻', sequence: index }),
    status: index % 3 === 1 ? 'OK' : 'ERROR',
    output: index % 3 === 1 ? '{}' : 'java.io.IOException'
  }));
  await page.getByLabel('Open primary tape', { exact: true }).setInputFiles(file('unicode-pages.tape', tape(rows)));
  await expect(page.locator('#stat-calls')).toHaveText('751');
  await expect(page.getByRole('alert')).toBeHidden();
  const search = page.getByRole('searchbox');
  const errorsOnly = page.getByLabel('Errors only', { exact: true });
  const inspectorName = page.locator('#inspector h2');
  await search.fill('🚀');
  await expectTimelinePage(page, 1, 250, 501, 0, 373);
  await nextPage(page).focus();
  await page.keyboard.press('Enter');
  await expectTimelinePage(page, 251, 500, 501, 375, 748);
  await expect(callAt(page, 375)).toBeFocused();
  await expect(inspectorName).toHaveText('unicode.step.0');
  await nextPage(page).focus();
  await page.keyboard.press('Space');
  await expectTimelinePage(page, 501, 501, 501, 750, 750);
  await expect(callAt(page, 750)).toBeFocused();
  await expect(callAt(page, 750)).toHaveAttribute('aria-pressed', 'false');
  await page.keyboard.press('Enter');
  await expect(callAt(page, 750)).toHaveAttribute('aria-pressed', 'true');
  await expect(inspectorName).toHaveText('unicode.step.750');
  await page.keyboard.press('Tab');
  await expect(previousPage(page)).toBeFocused();
  await page.keyboard.press('Space');
  await expectTimelinePage(page, 251, 500, 501, 375, 748);
  await expect(callAt(page, 375)).toBeFocused();
  await expect(page.locator('#timeline button[aria-pressed="true"]')).toHaveCount(0);
  await expect(inspectorName).toHaveText('unicode.step.750');

  await search.fill('👩‍💻');
  await expectTimelinePage(page, 1, 250, 501, 0, 373);
  await errorsOnly.check();
  await expectTimelinePage(page, 1, 250, 251, 0, 747);
  await nextPage(page).focus();
  await page.keyboard.press('Enter');
  await expectTimelinePage(page, 251, 251, 251, 750, 750);
  await expect(callAt(page, 750)).toBeFocused();
  await expect(callAt(page, 750)).toHaveAttribute('aria-pressed', 'true');
  await errorsOnly.uncheck();
  await expectTimelinePage(page, 1, 250, 501, 0, 373);
  await expect(inspectorName).toHaveText('unicode.step.750');

  await nextPage(page).click();
  await callAt(page, 748).focus();
  await page.keyboard.press('Escape');
  await expect(search).toHaveValue('');
  await expect(errorsOnly).not.toBeChecked();
  await expectTimelinePage(page, 501, 750, 751);
  await expect(callAt(page, 748)).toBeFocused();
  await expect(callAt(page, 750)).toHaveCount(0);
  await expect(page.locator('#timeline button[aria-pressed="true"]')).toHaveCount(0);
  await expect(inspectorName).toHaveText('unicode.step.750');
  await nextPage(page).focus();
  await page.keyboard.press('Space');
  await expectTimelinePage(page, 751, 751, 751);
  await expect(callAt(page, 750)).toBeFocused();
  await expect(callAt(page, 750)).toHaveAttribute('aria-pressed', 'true');

  for (const focusedControl of [search, errorsOnly]) {
    await search.fill('🚀');
    await errorsOnly.check();
    await expectTimelinePage(page, 1, 250, 251, 0, 747);
    if (focusedControl === search) {
      await search.fill('no-unicode-match');
      await expectTimelinePage(page, 0, 0, 0);
      await expect(page.locator('#timeline')).toContainText('No calls match these filters.');
    }
    await focusedControl.focus();
    await page.keyboard.press('Escape');
    await expect(search).toHaveValue('');
    await expect(errorsOnly).not.toBeChecked();
    await expect(focusedControl).toBeFocused();
    await expectTimelinePage(page, 751, 751, 751);
    await expect(callAt(page, 750)).toHaveAttribute('aria-pressed', 'true');
    await expect(inspectorName).toHaveText('unicode.step.750');
  }
  await expect(page.getByLabel('input', { exact: true })).toContainText(String.raw`\ud83d\ude80`);
  await expect(page.getByLabel('input', { exact: true })).not.toContainText('🚀');
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

test.describe('comparison-only inspection selection', () => {
  // Every scenario runs in all five hosted projects. Drive only the public file
  // inputs and native controls; do not write the viewer's internal state.
  const primaryRows = [
    { name: 'primary.first', input: '{"position":0}', output: '{"primary":"first"}' },
    { name: 'primary.saved', input: '{"position":1}', output: '{"primary":"saved"}' }
  ];
  const extraRow = { name: 'comparison.extra', output: '{"comparison":"original extra"}' };
  const primaryInput = page => page.getByLabel('Open primary tape', { exact: true });
  const comparisonInput = page => page.getByLabel('Open comparison tape', { exact: true });
  const jump = page => page.getByRole('button', { name: 'Inspect divergence', exact: true });
  const clear = page => page.getByRole('button', { name: 'Clear comparison', exact: true });
  const pressedCalls = page => page.locator('#timeline button[aria-pressed="true"]');

  async function inspectExtraCall(page) {
    await primaryInput(page).setInputFiles(file('bookmark-primary.tape', tape(primaryRows)));
    await callAt(page, 1).click();
    await comparisonInput(page).setInputFiles(file('bookmark-extension.tape', tape([...primaryRows, extraRow])));
    await expect(page.locator('#compare-title')).toContainText('position 3');
    await jump(page).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#inspector')).toBeFocused();
    await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
    await expect(page.getByLabel('comparison output', { exact: true })).toContainText('original extra');
    await expect(pressedCalls(page)).toHaveCount(0);
  }

  async function expectSavedPrimary(page) {
    await expect(page.locator('#inspector h2')).toHaveText('primary.saved');
    await expect(page.getByLabel('output', { exact: true })).toContainText('saved');
    await expect(callAt(page, 1)).toHaveAttribute('aria-pressed', 'true');
    await expect(callAt(page, 1)).toHaveClass(/selected/);
    await expect(callAt(page, 0)).toHaveAttribute('aria-pressed', 'false');
    await expect(pressedCalls(page)).toHaveCount(1);
    await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
  }

  test('clearing after repeated missing-primary jumps restores the last primary call', async ({ page }) => {
    await inspectExtraCall(page);
    for (const key of ['Space', 'Enter']) {
      await jump(page).focus();
      await page.keyboard.press(key);
      await expect(page.locator('#inspector')).toBeFocused();
      await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
      await expect(pressedCalls(page)).toHaveCount(0);
    }
    await clear(page).focus();
    await page.keyboard.press('Enter');
    await expectSavedPrimary(page);
    await expect(page.locator('#comparison')).toBeHidden();
    await expect(page.getByRole('button', { name: 'Compare tape', exact: true })).toBeFocused();
    await expect(page.locator('#announcer')).toHaveText('Comparison cleared.');
  });

  test('an equal-length replacement restores the primary call without moving row focus', async ({ page }) => {
    await inspectExtraCall(page);
    await callAt(page, 0).focus();
    const focusedRow = await callAt(page, 0).elementHandle();
    await comparisonInput(page).setInputFiles(file('bookmark-equal.tape', tape(primaryRows)));
    await expect(page.locator('#compare-title')).toContainText('all 2 calls match');
    await expectSavedPrimary(page);
    await expect(callAt(page, 0)).toBeFocused();
    expect(await focusedRow.evaluate(element => element.isConnected), 'replacement preserves mounted row identity').toBe(true);
    await expect(page.locator('#inspector')).toContainText('Matches the comparison tape.');
  });

  test.describe('async payload focus', () => {
    // The read-completion barrier and committed comparison name precede each
    // final focus assertion. Keep intentional regression failures bounded in all five
    // browser projects without adding sleeps or modifying application state.
    async function expectCompletedComparison(page, name) {
      await releaseFileRead(page, name);
      await expect(page.locator('#compare-note')).toContainText(name);
    }

    async function expectRetainedKeyboardFocus(locator) {
      await expect(locator).toBeFocused({ timeout: 2000 });
      await expectVisibleKeyboardFocus(locator);
    }

    test('a removed comparison input falls back to the inspector and restores the primary bookmark', async ({ page }) => {
      const replacementName = 'focus-equal-replacement.tape';
      await deferFileReads(page, [replacementName]);
      await inspectExtraCall(page);
      await comparisonInput(page).setInputFiles(file(replacementName, tape(primaryRows)));
      await expect(page.locator('#compare-note')).toContainText('bookmark-extension.tape');
      await page.keyboard.press('Tab');
      await expectVisibleKeyboardFocus(page.getByLabel('comparison input', { exact: true }));

      await expectCompletedComparison(page, replacementName);
      await expect(page.locator('#compare-title')).toContainText('all 2 calls match');
      await expectSavedPrimary(page);
      await expect(page.getByLabel('comparison input', { exact: true })).toHaveCount(0);
      await expectRetainedKeyboardFocus(page.locator('#inspector'));
      // The fallback remains a useful keyboard starting point after restoration.
      await page.keyboard.press('Tab');
      await expectVisibleKeyboardFocus(page.getByLabel('input', { exact: true }));
    });

    for (const label of ['input', 'output']) {
      test(`a surviving primary ${label} keeps keyboard focus after a delayed comparison replacement`, async ({ page }) => {
        const replacementName = 'focus-primary-' + label + '-replacement.tape';
        await deferFileReads(page, [replacementName]);
        await primaryInput(page).setInputFiles(file('focus-primary.tape', tape(primaryRows)));
        await callAt(page, 1).click();
        await comparisonInput(page).setInputFiles(file('focus-primary-drift.tape', tape([
          primaryRows[0], { ...primaryRows[1], output: '{"comparison":"old drift"}' }
        ])));
        await expect(page.getByLabel('comparison output', { exact: true })).toContainText('old drift');
        await comparisonInput(page).setInputFiles(file(replacementName, tape(primaryRows)));
        await expect(page.locator('#compare-note')).toContainText('focus-primary-drift.tape');
        // Move to the payload while the file is still pending, so restoration
        // must follow current focus rather than focus captured when loading.
        await detailsFromFilters(page, 'Enter');
        await page.keyboard.press('Tab');
        if (label === 'output') await page.keyboard.press('Tab');
        await expectVisibleKeyboardFocus(page.getByLabel(label, { exact: true }));

        await expectCompletedComparison(page, replacementName);
        await expect(page.locator('#compare-title')).toContainText('all 2 calls match');
        await expectSavedPrimary(page);
        await expectRetainedKeyboardFocus(page.getByLabel(label, { exact: true }));
      });
    }

    test('a delayed comparison completion does not steal newer focus outside the inspector', async ({ page }) => {
      const replacementName = 'focus-outside-replacement.tape';
      await deferFileReads(page, [replacementName]);
      await inspectExtraCall(page);
      await page.keyboard.press('Tab');
      await expectVisibleKeyboardFocus(page.getByLabel('comparison input', { exact: true }));
      await comparisonInput(page).setInputFiles(file(replacementName, tape(primaryRows)));
      await expect(page.locator('#compare-note')).toContainText('bookmark-extension.tape');
      await page.getByRole('searchbox').focus();
      await page.keyboard.press('Tab');
      const outsideControl = page.getByLabel('Errors only', { exact: true });
      await expectVisibleKeyboardFocus(outsideControl);

      await expectCompletedComparison(page, replacementName);
      await expect(page.locator('#compare-title')).toContainText('all 2 calls match');
      await expectSavedPrimary(page);
      await expectRetainedKeyboardFocus(outsideControl);
      await expect(outsideControl).not.toBeChecked();
      await expect(page.getByRole('searchbox')).toHaveValue('');
    });

    test('a stale comparison completion cannot replace the newer payload or its keyboard focus', async ({ page }) => {
      const staleName = 'focus-stale-replacement.tape';
      await deferFileReads(page, [staleName]);
      await inspectExtraCall(page);
      await page.keyboard.press('Tab');
      await expectVisibleKeyboardFocus(page.getByLabel('comparison input', { exact: true }));
      await comparisonInput(page).setInputFiles(file(staleName, tape(primaryRows)));
      await expect(page.locator('#compare-note')).toContainText('bookmark-extension.tape');
      const newerName = 'focus-newer-extension.tape';
      await comparisonInput(page).setInputFiles(file(newerName, tape([
        ...primaryRows, { ...extraRow, output: '{"comparison":"newer extra"}' }
      ])));
      await expect(page.locator('#compare-note')).toContainText(newerName);
      await expect(page.getByLabel('comparison output', { exact: true })).toContainText('newer extra');
      await detailsFromFilters(page, 'Enter');
      await page.keyboard.press('Tab');
      await page.keyboard.press('Tab');
      const currentPayload = page.getByLabel('comparison output', { exact: true });
      await expectVisibleKeyboardFocus(currentPayload);
      const focusedNode = await currentPayload.elementHandle();

      // An obsolete result must finish before checking the unchanged visible
      // state; checking only the new filename could pass before it was consumed.
      await releaseFileRead(page, staleName);
      await expect(page.locator('#compare-note')).toContainText(newerName);
      await expect(page.locator('#compare-title')).toContainText('position 3');
      await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
      await expect(currentPayload).toContainText('newer extra');
      await expect(pressedCalls(page)).toHaveCount(0);
      expect(await focusedNode.evaluate(element => element.isConnected), 'a stale read does not remount the inspector').toBe(true);
      await expectRetainedKeyboardFocus(currentPayload);
    });
  });

  test('longer and shorter still-valid replacements retain the comparison-only position', async ({ page }) => {
    await inspectExtraCall(page);
    const changedPrefix = primaryRows.map((row, index) => index ? row : { ...row, output: '{"different":true}' });
    await comparisonInput(page).setInputFiles(file('bookmark-longer.tape', tape([
      ...changedPrefix, { ...extraRow, output: '{"comparison":"replacement extra"}' },
      { name: 'comparison.later', output: '{"comparison":"later call"}' }
    ])));
    // The new first divergence is elsewhere. Loading a comparison must neither
    // jump to it nor abandon the still-present call being inspected.
    await expect(page.locator('#compare-title')).toContainText('position 1');
    await expect(page.locator('#inspector')).toBeFocused();
    await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
    await expect(page.getByLabel('comparison output', { exact: true })).toContainText('replacement extra');
    await expect(pressedCalls(page)).toHaveCount(0);
    await comparisonInput(page).setInputFiles(file('bookmark-shorter-valid.tape', tape([
      ...primaryRows, { ...extraRow, output: '{"comparison":"shorter valid extra"}' }
    ])));
    await expect(page.locator('#compare-title')).toContainText('position 3');
    await expect(page.locator('#inspector')).toBeFocused();
    await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
    await expect(page.getByLabel('comparison output', { exact: true })).toContainText('shorter valid extra');
    await expect(pressedCalls(page)).toHaveCount(0);
    await clear(page).click();
    await expectSavedPrimary(page);
  });

  test('a failed replacement retains comparison-only details and the primary bookmark', async ({ page }) => {
    await inspectExtraCall(page);
    await comparisonInput(page).setInputFiles(file('bookmark-invalid.tape', 'TOOLTAPE\t1'));
    await expect(page.getByRole('alert')).toContainText('Could not open bookmark-invalid.tape');
    await expect(page.locator('#compare-note')).toContainText('bookmark-extension.tape');
    await expect(page.locator('#inspector')).toBeFocused();
    await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
    await expect(page.getByLabel('comparison output', { exact: true })).toContainText('original extra');
    await expect(pressedCalls(page)).toHaveCount(0);
    await clear(page).focus();
    await page.keyboard.press('Space');
    await expectSavedPrimary(page);
    await expect(page.getByRole('alert')).toBeHidden();
    await expect(page.getByRole('button', { name: 'Compare tape', exact: true })).toBeFocused();
  });

  test('valid-primary jumps and later row selections each update the restored call', async ({ page }) => {
    await primaryInput(page).setInputFiles(file('bookmark-primary.tape', tape(primaryRows)));
    await callAt(page, 1).click();
    await comparisonInput(page).setInputFiles(file('bookmark-primary-drift.tape', tape([
      { ...primaryRows[0], output: '{"different":true}' }, primaryRows[1]
    ])));
    await expect(page.locator('#compare-title')).toContainText('position 1');
    await jump(page).focus();
    await page.keyboard.press('Enter');
    await expect(callAt(page, 0)).toBeFocused();
    await expect(callAt(page, 0)).toHaveAttribute('aria-pressed', 'true');
    for (const position of [0, 1]) {
      if (position === 1) await callAt(page, 1).click();
      await comparisonInput(page).setInputFiles(file('bookmark-extension.tape', tape([...primaryRows, extraRow])));
      await expect(jump(page)).toBeVisible();
      await expect(page.locator('#compare-title')).toContainText('position 3');
      await jump(page).focus();
      await page.keyboard.press('Enter');
      await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
      await clear(page).click();
      await expect(page.locator('#inspector h2')).toHaveText(primaryRows[position].name);
      await expect(callAt(page, position)).toHaveAttribute('aria-pressed', 'true');
      await expect(pressedCalls(page)).toHaveCount(1);
    }
  });

  test('clear and replacement retain a valid-primary selection even when comparison lacks it', async ({ page }) => {
    await primaryInput(page).setInputFiles(file('bookmark-primary.tape', tape(primaryRows)));
    await callAt(page, 1).click();
    for (const comparisonRows of [[primaryRows[0]], [], primaryRows]) {
      const name = 'bookmark-replacement-' + comparisonRows.length + '.tape';
      await comparisonInput(page).setInputFiles(file(name, tape(comparisonRows)));
      await expect(page.locator('#compare-note')).toContainText(name);
      await expectSavedPrimary(page);
      if (comparisonRows.length < 2) {
        await expect(page.locator('#inspector')).toContainText('This call is missing from the comparison tape.');
      }
    }
    await clear(page).click();
    await expectSavedPrimary(page);
  });

  for (const completion of ['clear', 'equal replacement']) {
    for (const bookmark of [100, 300, 600]) {
      const visibility = bookmark === 100 ? 'filtered-out' : bookmark === 300 ? 'off-page' : 'visible';
      test(`${completion} preserves filtered pagination with a ${visibility} primary bookmark`, async ({ page }) => {
        const rows = Array.from({ length: 751 }, (_, index) => ({
          name: (index < 250 ? 'other.step.' : 'kept.step.') + index,
          status: 'ERROR', output: 'java.io.IOException'
        }));
        await primaryInput(page).setInputFiles(file('bookmark-paginated.tape', tape(rows)));
        for (let start = 0; start + 250 <= bookmark; start += 250) await nextPage(page).click();
        await callAt(page, bookmark).click();
        await comparisonInput(page).setInputFiles(file('bookmark-paginated-extension.tape', tape([...rows, extraRow])));
        await expect(page.locator('#compare-title')).toContainText('position 752');
        await jump(page).focus();
        await page.keyboard.press('Enter');
        await expectTimelinePage(page, 751, 751, 751);
        await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
        await page.getByRole('searchbox').fill('kept.step.');
        await page.getByLabel('Errors only', { exact: true }).check();
        await nextPage(page).click();
        await expectTimelinePage(page, 251, 500, 501, 500, 749);
        await callAt(page, 601).focus();
        const mountedRow = await callAt(page, 601).elementHandle();
        if (completion === 'clear') {
          await clear(page).focus();
          await page.keyboard.press('Enter');
          await expect(page.locator('#comparison')).toBeHidden();
          await expect(page.getByRole('button', { name: 'Compare tape', exact: true })).toBeFocused();
        } else {
          await comparisonInput(page).setInputFiles(file('bookmark-paginated-equal.tape', tape(rows)));
          await expect(page.locator('#compare-title')).toContainText('all 751 calls match');
          await expect(callAt(page, 601)).toBeFocused();
        }
        await expect(page.getByRole('searchbox')).toHaveValue('kept.step.');
        await expect(page.getByLabel('Errors only', { exact: true })).toBeChecked();
        await expectTimelinePage(page, 251, 500, 501, 500, 749);
        expect(await mountedRow.evaluate(element => element.isConnected), 'restoration does not remount timeline rows').toBe(true);
        await expect(page.locator('#inspector h2')).toHaveText(rows[bookmark].name);
        await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
        await expect(pressedCalls(page)).toHaveCount(bookmark === 600 ? 1 : 0);
        if (bookmark === 600) {
          await expect(callAt(page, 600)).toHaveAttribute('aria-pressed', 'true');
          await expect(callAt(page, 600)).toHaveClass(/selected/);
        } else {
          // Reveal the saved call without selecting it, whether it was merely
          // on another page or excluded by the still-active search.
          if (bookmark === 100) {
            await page.getByRole('searchbox').fill('');
            await expectTimelinePage(page, 1, 250, 751);
          } else {
            await previousPage(page).click();
            await expectTimelinePage(page, 1, 250, 501, 250, 499);
          }
          await expect(callAt(page, bookmark)).toHaveAttribute('aria-pressed', 'true');
          await expect(page.locator('#inspector h2')).toHaveText(rows[bookmark].name);
        }
      });
    }
  }

  test('a new primary resets the saved call and never reuses a previous tape bookmark', async ({ page }) => {
    await inspectExtraCall(page);
    const replacement = Array.from({ length: 3 }, (_, index) => ({ name: 'new.primary.' + index }));
    await primaryInput(page).setInputFiles(file('bookmark-new-primary.tape', tape(replacement)));
    await expect(page.locator('#comparison')).toBeHidden();
    await expect(page.locator('#inspector h2')).toHaveText('new.primary.0');
    await expect(callAt(page, 0)).toHaveAttribute('aria-pressed', 'true');
    await comparisonInput(page).setInputFiles(file('bookmark-new-extension.tape', tape([...replacement, extraRow])));
    await expect(page.locator('#compare-title')).toContainText('position 4');
    await jump(page).click();
    await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
    await clear(page).click();
    await expect(page.locator('#inspector h2')).toHaveText('new.primary.0');
    await expect(callAt(page, 0)).toHaveAttribute('aria-pressed', 'true');
    await expect(callAt(page, 1)).toHaveAttribute('aria-pressed', 'false');
    await expect(pressedCalls(page)).toHaveCount(1);
  });

  test('empty-primary clear and replacement remain meaningful without a stale bookmark', async ({ page }) => {
    await inspectExtraCall(page);
    await primaryInput(page).setInputFiles(file('bookmark-empty-primary.tape', tape([])));
    await expectTimelinePage(page, 0, 0, 0);
    await expect(page.locator('#timeline')).toContainText('This tape has no recorded calls.');
    await expect(page.locator('#inspector')).toContainText('Select a recorded call to inspect its input and output.');
    for (const completion of ['clear', 'equal replacement']) {
      await comparisonInput(page).setInputFiles(file('bookmark-empty-extension.tape', tape([extraRow])));
      await expect(jump(page)).toBeVisible();
      await jump(page).focus();
      await page.keyboard.press('Enter');
      await expect(page.locator('#inspector')).toBeFocused();
      await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
      await expect(page.getByLabel('comparison output', { exact: true })).toContainText('original extra');
      if (completion === 'clear') {
        await clear(page).click();
        await expect(page.locator('#comparison')).toBeHidden();
      } else {
        await comparisonInput(page).setInputFiles(file('bookmark-empty-equal.tape', tape([])));
        await expect(page.locator('#compare-title')).toContainText('all 0 calls match');
        await expect(page.locator('#inspector')).toBeFocused();
      }
      await expectTimelinePage(page, 0, 0, 0);
      await expect(page.locator('#inspector h2')).toHaveCount(0);
      await expect(page.locator('#inspector')).toContainText('Select a recorded call to inspect its input and output.');
      await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
      await expect(pressedCalls(page)).toHaveCount(0);
    }
    await primaryInput(page).setInputFiles(file('bookmark-after-empty.tape', tape([{ name: 'after.empty' }])));
    await expect(page.locator('#inspector h2')).toHaveText('after.empty');
    await comparisonInput(page).setInputFiles(file('bookmark-after-empty-extension.tape', tape([{ name: 'after.empty' }, extraRow])));
    await expect(page.locator('#compare-title')).toContainText('position 2');
    await jump(page).click();
    await clear(page).click();
    await expect(page.locator('#inspector h2')).toHaveText('after.empty');
    await expect(callAt(page, 0)).toHaveAttribute('aria-pressed', 'true');
  });
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
  await expectTimelinePage(page, 0, 0, 0);
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
  await page.locator('body').drop({ files: file('dropped.tape', content) });
  await expect(page.locator('#filename')).toHaveText('dropped.tape');
  await expect(calls(page)).toHaveCount(2);
  await expect(page.locator('body')).not.toHaveClass(/drop-active/);
  await page.locator('body').drop({ files: [file('first.tape', content), file('second.tape', content)] });
  await expect(page.getByRole('alert')).toContainText('Drop one .tape file at a time');
  await expect(page.locator('#filename')).toHaveText('dropped.tape');
  await expect(calls(page)).toHaveCount(2);
  await expect(page.locator('body')).not.toHaveClass(/drop-active/);
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

test('clearing comparison dismisses its failed replacement notice and permits same-file recovery', async ({ page }) => {
  const primary = file('primary.tape', tape([{ name: 'primary.run', output: '{"value":1}' }]));
  const comparison = file('comparison.tape', tape([{ name: 'primary.run', output: '{"value":2}' }]));
  const invalidComparison = file('invalid-comparison.tape', 'TOOLTAPE\t1');
  await page.locator('#open-file').setInputFiles(primary);
  await expect(page.locator('#filename')).toHaveText(primary.name);
  await page.locator('#compare-file').setInputFiles(comparison);
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('2');
  await page.locator('#compare-file').setInputFiles(invalidComparison);
  await expect(page.getByRole('alert')).toContainText('Could not open invalid-comparison.tape');
  await expect(page.locator('#comparison')).toBeVisible();
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('2');
  await page.getByRole('button', { name: 'Clear comparison', exact: true }).click();
  await expect(page.locator('#comparison')).toBeHidden();
  await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
  await expect(page.locator('#notice')).toBeHidden();
  await expect(page.locator('#notice')).toHaveText('');
  await expect(page.locator('#filename')).toHaveText(primary.name);
  await expect(page.getByLabel('output', { exact: true })).toContainText('1');

  // Re-select the exact same payload after dismissal, then recover by choosing
  // the same valid File twice. The public inputs must remain reusable.
  for (let attempt = 0; attempt < 2; attempt++) {
    await page.locator('#compare-file').setInputFiles(invalidComparison);
    await expect(page.getByRole('alert')).toContainText('Could not open invalid-comparison.tape');
    await page.locator('#compare-file').setInputFiles(comparison);
    await expect(page.locator('#compare-file')).toHaveValue('');
    await expect(page.locator('#comparison')).toBeVisible();
    await expect(page.locator('#notice')).toBeHidden();
    await expect(page.locator('#notice')).toHaveText('');
    await expect(page.getByLabel('comparison output', { exact: true })).toContainText('2');
    if (attempt === 0) {
      await page.getByRole('button', { name: 'Clear comparison', exact: true }).click();
      await expect(page.locator('#comparison')).toBeHidden();
    }
  }
});

test('a pending primary success dismisses an intervening comparison failure against the old primary', async ({ page }) => {
  await deferFileReads(page, ['next-primary.tape']);
  await page.locator('#open-file').setInputFiles(file('visible-primary.tape', tape([{ name: 'visible.run' }])));
  await expect(page.locator('#filename')).toHaveText('visible-primary.tape');
  await page.locator('#compare-file').setInputFiles(file('visible-comparison.tape', tape([{ name: 'visible.run', output: '{"value":2}' }])));
  await expect(page.locator('#comparison')).toBeVisible();
  await page.locator('#open-file').setInputFiles(file('next-primary.tape', tape([{ name: 'next.run' }])));
  await expect(page.locator('#filename')).toHaveText('visible-primary.tape');
  await page.locator('#compare-file').setInputFiles(file('invalid-comparison.tape', 'TOOLTAPE\t1'));
  await expect(page.getByRole('alert')).toContainText('Could not open invalid-comparison.tape');
  await expect(page.locator('#comparison')).toBeVisible();
  await releaseFileRead(page, 'next-primary.tape');
  await expect(page.locator('#filename')).toHaveText('next-primary.tape');
  await expect(calls(page)).toHaveCount(1);
  await expect(calls(page).first()).toHaveAccessibleName('Call 0: next.run, OK');
  await expect(page.locator('#comparison')).toBeHidden();
  await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
  await expect(page.locator('#notice')).toBeHidden();
  await expect(page.locator('#notice')).toHaveText('');
});

test('comparison success and clearing retain an intervening primary failure notice', async ({ page }) => {
  await deferFileReads(page, ['pending-comparison.tape']);
  await page.locator('#open-file').setInputFiles(file('primary.tape', tape([{ name: 'primary.run' }])));
  await expect(page.locator('#filename')).toHaveText('primary.tape');
  await page.locator('#compare-file').setInputFiles(file('pending-comparison.tape', tape([{ name: 'primary.run', output: '{"value":2}' }])));
  await page.locator('#open-file').setInputFiles(file('invalid-primary.tape', 'TOOLTAPE\t1'));
  await expect(page.getByRole('alert')).toContainText('Could not open invalid-primary.tape');
  const notice = await page.locator('#notice').textContent();
  await releaseFileRead(page, 'pending-comparison.tape');
  await expect(page.locator('#comparison')).toBeVisible();
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('2');
  await expect(page.getByRole('alert')).toHaveText(notice);
  await page.getByRole('button', { name: 'Clear comparison', exact: true }).click();
  await expect(page.locator('#comparison')).toBeHidden();
  await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveText(notice);
  await expect(page.locator('#filename')).toHaveText('primary.tape');
  await expect(calls(page).first()).toHaveAccessibleName('Call 0: primary.run, OK');
});

for (const completion of ['clear comparison', 'primary success']) {
  test(`${completion} retains an intervening general multiple-file drop notice`, async ({ page }) => {
    await deferFileReads(page, ['next-primary.tape']);
    const content = tape([{ name: 'primary.run' }]);
    await page.locator('#open-file').setInputFiles(file('primary.tape', content));
    await expect(page.locator('#filename')).toHaveText('primary.tape');
    await page.locator('#compare-file').setInputFiles(file('comparison.tape', content));
    await expect(page.locator('#comparison')).toBeVisible();
    if (completion === 'primary success') {
      await page.locator('#open-file').setInputFiles(file('next-primary.tape', tape([{ name: 'next.run' }])));
    }
    await page.locator('body').drop({ files: [file('first.tape', content), file('second.tape', content)] });
    await expect(page.getByRole('alert')).toContainText('Drop one .tape file at a time');
    const notice = await page.locator('#notice').textContent();
    if (completion === 'primary success') await releaseFileRead(page, 'next-primary.tape');
    else await page.getByRole('button', { name: 'Clear comparison', exact: true }).click();
    await expect(page.locator('#comparison')).toBeHidden();
    await expect(page.getByRole('alert')).toHaveText(notice);
    await expect(page.locator('#filename')).toHaveText(completion === 'primary success' ? 'next-primary.tape' : 'primary.tape');
    await expect(calls(page)).toHaveCount(1);
    await expect(calls(page).first()).toHaveAccessibleName(completion === 'primary success'
      ? 'Call 0: next.run, OK' : 'Call 0: primary.run, OK');
  });
}

for (const replacement of ['clear', 'comparison replacement', 'primary replacement']) {
  for (const outcome of ['resolve', 'reject']) {
    test(`stale comparison read ${outcome} after ${replacement} cannot restore state or obsolete notices`, async ({ page }) => {
      await deferFileReads(page, ['stale-comparison.tape']);
      for (const retainNotice of [false, true]) {
        await test.step(retainNotice ? 'retain the newer primary notice' : 'keep the notice dismissed', async () => {
          const content = tape([{ name: 'primary.run' }]);
          const primaryName = retainNotice ? 'primary-with-notice.tape' : 'primary.tape';
          await page.locator('#open-file').setInputFiles(file(primaryName, content));
          await expect(page.locator('#filename')).toHaveText(primaryName);
          await page.locator('#compare-file').setInputFiles(file('original-comparison.tape', content));
          await expect(page.locator('#comparison')).toBeVisible();
          await page.locator('#compare-file').setInputFiles(file('stale-comparison.tape', tape([{ name: 'stale.run', output: '{"value":"stale"}' }])));
          if (replacement === 'clear') {
            await page.getByRole('button', { name: 'Clear comparison', exact: true }).click();
          } else if (replacement === 'comparison replacement') {
            await page.locator('#compare-file').setInputFiles(file('newer-comparison.tape', tape([{ name: 'primary.run', output: '{"value":"newer"}' }])));
            await expect(page.getByLabel('comparison output', { exact: true })).toContainText('newer');
          } else {
            await page.locator('#open-file').setInputFiles(file('newer-primary.tape', tape([{ name: 'newer.run' }])));
            await expect(page.locator('#filename')).toHaveText('newer-primary.tape');
          }
          await expect(page.locator('#notice')).toBeHidden();
          let notice = '';
          if (retainNotice) {
            // This ordinary failed primary read owns the current notice. Stale
            // success must not clear it; stale rejection must not replace it.
            await page.locator('#open-file').setInputFiles(file('current-primary-error.tape', 'TOOLTAPE\t1'));
            await expect(page.getByRole('alert')).toContainText('Could not open current-primary-error.tape');
            notice = await page.locator('#notice').textContent();
          }
          await releaseFileRead(page, 'stale-comparison.tape', outcome);
          if (retainNotice) await expect(page.getByRole('alert')).toHaveText(notice);
          else {
            await expect(page.locator('#notice')).toBeHidden();
            await expect(page.locator('#notice')).toHaveText('');
          }
          await expect(page.locator('#filename')).toHaveText(replacement === 'primary replacement' ? 'newer-primary.tape' : primaryName);
          await expect(calls(page)).toHaveCount(1);
          await expect(calls(page).first()).toHaveAccessibleName(replacement === 'primary replacement'
            ? 'Call 0: newer.run, OK' : 'Call 0: primary.run, OK');
          if (replacement === 'comparison replacement') {
            await expect(page.locator('#comparison')).toBeVisible();
            await expect(page.locator('#compare-note')).toContainText('newer-comparison.tape');
            await expect(page.getByLabel('comparison output', { exact: true })).toContainText('newer');
          } else {
            await expect(page.locator('#comparison')).toBeHidden();
            await expect(page.getByLabel('comparison output', { exact: true })).toHaveCount(0);
          }
        });
      }
    });
  }
}

for (const count of [250, 251, 501]) {
  for (const key of ['Enter', 'Space']) {
    test(`${key} short keyboard routes preserve bounded pages and selection across ${count} calls`, async ({ page, browser }, testInfo) => {
      const rows = Array.from({ length: count }, (_, index) => ({ name: 'step.' + index }));
      const primary = file(`synthetic-${count}-keyboard.tape`, tape(rows));
      await page.locator('#open-file').setInputFiles(primary);
      await expect(page.locator('#stat-calls')).toHaveText(String(count));
      await expectTimelinePage(page, 1, 250, count);
      await expect(page.getByRole('group', { name: 'Timeline shortcuts', exact: true })).toBeVisible();
      await expect(skipDetails(page)).toHaveAttribute('id', 'skip-details');
      await expect(skipDetails(page)).toHaveAttribute('type', 'button');
      await expect(page.locator('#inspector')).toHaveAttribute('tabindex', '-1');
      expect(await page.locator('#timeline').evaluate(element => ({
        tag: element.tagName,
        shortcutIds: [...element.previousElementSibling.querySelectorAll('button')].map(button => button.id),
        callSemantics: [...element.children].every(item => item.tagName === 'LI' &&
          item.firstElementChild.tagName === 'BUTTON' && item.firstElementChild.tabIndex === 0 &&
          item.firstElementChild.hasAttribute('aria-pressed'))
      }))).toEqual({ tag: 'OL', shortcutIds: ['skip-pages', 'skip-details'], callSemantics: true });

      // The only initial focus placement is the first row. Every shortcut and
      // page change below is reached by actual keyboard traversal, never by
      // focusing call 249/499 or jumping directly to a pager button.
      await calls(page).first().focus();
      await page.keyboard.press('Tab');
      await expect(callAt(page, 1)).toBeFocused();
      await page.keyboard.press('Shift+Tab');
      await expect(callAt(page, 0)).toBeFocused();
      await shortcutFromFirstCall(page, 'details');
      await page.keyboard.press(key);
      await expectVisibleKeyboardFocus(page.locator('#inspector'));
      await expect(page.locator('#inspector h2')).toHaveText('step.0');
      await expect(callAt(page, 0)).toHaveAttribute('aria-pressed', 'true');
      await page.keyboard.press('Tab');
      await expect(page.getByLabel('input', { exact: true })).toBeFocused();

      if (count === 250) {
        await expect(page.locator('#skip-pages')).toBeHidden();
        await calls(page).first().focus();
        await shortcutFromFirstCall(page, 'details');
        await page.keyboard.press('Shift+Tab');
        await expect(page.getByLabel('Errors only', { exact: true })).toBeFocused();
        await expectTimelinePage(page, 1, 250, count);
        return;
      }

      await expect(skipPages(page)).toHaveAttribute('id', 'skip-pages');
      await expect(skipPages(page)).toHaveAttribute('type', 'button');
      await calls(page).first().focus();
      await shortcutFromFirstCall(page, 'pages');
      await page.keyboard.press(key);
      // Previous is disabled on the first page, so the route chooses Next.
      await expectVisibleKeyboardFocus(nextPage(page));
      await page.keyboard.press(key);
      await expectTimelinePage(page, 251, Math.min(500, count), count);
      await expectVisibleKeyboardFocus(callAt(page, 250));
      await expect(callAt(page, 250)).toHaveAttribute('aria-pressed', 'false');
      await expect(page.locator('#timeline button[aria-pressed="true"]')).toHaveCount(0);
      await expect(page.locator('#inspector h2')).toHaveText('step.0');
      await shortcutFromFirstCall(page, 'details');
      await page.keyboard.press(key);
      await expectVisibleKeyboardFocus(page.locator('#inspector'));
      await expect(page.locator('#inspector h2')).toHaveText('step.0');

      if (count === 501) {
        await calls(page).first().focus();
        await shortcutFromFirstCall(page, 'pages');
        await page.keyboard.press(key);
        // Both are enabled on the middle page; Previous is the first choice.
        await expectVisibleKeyboardFocus(previousPage(page));
        await page.keyboard.press('Tab');
        await expectVisibleKeyboardFocus(nextPage(page));
        await page.keyboard.press(key);
        await expectTimelinePage(page, 501, 501, count);
        await expectVisibleKeyboardFocus(callAt(page, 500));
      } else {
        await calls(page).first().focus();
      }

      // Select the final call, then keep inspecting it after paging away.
      await page.keyboard.press(key);
      await expect(callAt(page, count - 1)).toHaveAttribute('aria-pressed', 'true');
      await expect(page.locator('#inspector h2')).toHaveText('step.' + (count - 1));
      await shortcutFromFirstCall(page, 'details');
      await page.keyboard.press(key);
      await expectVisibleKeyboardFocus(page.locator('#inspector'));
      await expect(page.locator('#inspector h2')).toHaveText('step.' + (count - 1));
      await calls(page).first().focus();
      await shortcutFromFirstCall(page, 'pages');
      await page.keyboard.press(key);
      await expectVisibleKeyboardFocus(previousPage(page));
      await expect(nextPage(page)).toBeDisabled();
      await page.keyboard.press('Tab');
      // Disabled Next and tabindex=-1 inspector are skipped during normal Tab.
      await expect(page.getByLabel('input', { exact: true })).toBeFocused();
      await page.keyboard.press('Shift+Tab');
      await expect(previousPage(page)).toBeFocused();
      await page.keyboard.press(key);
      await expectTimelinePage(page, count === 501 ? 251 : 1, count === 501 ? 500 : 250, count);
      await expect(calls(page).first()).toBeFocused();
      await expect(callAt(page, count - 1)).toHaveCount(0);
      await expect(page.locator('#timeline button[aria-pressed="true"]')).toHaveCount(0);
      await shortcutFromFirstCall(page, 'details');
      await page.keyboard.press(key);
      await expectVisibleKeyboardFocus(page.locator('#inspector'));
      await expect(page.locator('#inspector h2')).toHaveText('step.' + (count - 1));

      if (count === 501 && key === 'Enter' && testInfo.project.name === 'desktop-chromium') {
        const directory = path.join(__dirname, 'artifacts/screenshots', testInfo.project.name);
        await fs.mkdir(directory, { recursive: true });
        const fixturePath = path.join(directory, primary.name);
        await fs.writeFile(fixturePath, primary.buffer);
        await testInfo.attach(primary.name, { path: fixturePath, contentType: 'text/plain' });
        const evidence = {
          kind: 'deterministic synthetic TOOLTAPE v1, public file-input loading',
          generator: 'viewer/browser-tests/viewer.spec.cjs',
          callCount: count,
          primary: { name: primary.name, bytes: primary.buffer.length,
            sha256: createHash('sha256').update(primary.buffer).digest('hex') },
          displayedMatchingRange: [251, 500],
          renderedCallCards: await calls(page).count(),
          selectedPosition: 500,
          selectedCallIsOffPage: true,
          pageSizeLimit: 250,
          captureNote: 'The shared capture helper blurs focus; focus-visible and outline assertions run separately.'
        };
        await capture(page, browser, testInfo, 'viewer-keyboard-shortcuts',
          'Deterministic synthetic 501-call v1 tape; middle 250-call page, visible keyboard shortcuts, selected off-page call 500 retained in details.',
          fixturePath, evidence);
      }

      if (count === 501) {
        await calls(page).first().focus();
        await shortcutFromFirstCall(page, 'pages');
        await page.keyboard.press(key);
        await expect(previousPage(page)).toBeFocused();
        await page.keyboard.press(key);
      }
      await expectTimelinePage(page, 1, 250, count);
      await expect(page.locator('#timeline button[aria-pressed="true"]')).toHaveCount(0);
      await expect(page.locator('#inspector h2')).toHaveText('step.' + (count - 1));
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
        'visible shortcuts do not create horizontal page overflow').toBe(true);
    });
  }
}

for (const key of ['Enter', 'Space']) {
  test(`${key} details shortcut stays usable across filtered pagination boundaries and no results`, async ({ page }) => {
    const rows = Array.from({ length: 501 }, (_, index) => ({
      name: (index < 251 ? 'match.' : 'other.') + index,
      input: index < 250 ? '{"group":"first-250"}' : '{}'
    }));
    await page.locator('#open-file').setInputFiles(file('filtered-shortcuts.tape', tape(rows)));
    const search = page.getByRole('searchbox');
    await search.fill('match.');
    await expectTimelinePage(page, 1, 250, 251);
    await calls(page).first().focus();
    await shortcutFromFirstCall(page, 'pages');
    await page.keyboard.press(key);
    await expect(nextPage(page)).toBeFocused();
    await page.keyboard.press(key);
    await expectTimelinePage(page, 251, 251, 251);
    await expect(callAt(page, 250)).toBeFocused();
    await page.keyboard.press(key);
    await expect(callAt(page, 250)).toHaveAttribute('aria-pressed', 'true');

    await search.fill('first-250');
    await expectTimelinePage(page, 1, 250, 250);
    await expect(page.locator('#skip-pages')).toBeHidden();
    await calls(page).first().focus();
    await shortcutFromFirstCall(page, 'details');
    await page.keyboard.press(key);
    await expectVisibleKeyboardFocus(page.locator('#inspector'));
    await expect(page.locator('#inspector h2')).toHaveText('match.250');
    await expect(page.locator('#timeline button[aria-pressed="true"]')).toHaveCount(0);

    await search.fill('no-recorded-call-matches');
    await expectTimelinePage(page, 0, 0, 0);
    await expect(page.locator('#skip-pages')).toBeHidden();
    await expect(page.locator('#timeline')).toContainText('No calls match these filters.');
    await detailsFromFilters(page, key);
    await expect(page.locator('#inspector h2')).toHaveText('match.250');

    await search.fill('');
    await expectTimelinePage(page, 1, 250, 501);
    await page.getByLabel('Errors only', { exact: true }).focus();
    await page.keyboard.press('Space');
    await expectTimelinePage(page, 0, 0, 0);
    await expect(page.locator('#skip-pages')).toBeHidden();
    await page.keyboard.press('Tab');
    await expectVisibleKeyboardFocus(skipDetails(page));
    await page.keyboard.press(key);
    await expectVisibleKeyboardFocus(page.locator('#inspector'));
    await expect(page.locator('#inspector h2')).toHaveText('match.250');
  });

  test(`${key} details shortcut reaches loaded-empty and missing-primary comparison explanations`, async ({ page }) => {
    await expect(page.locator('#skip-details')).toBeHidden();
    await expect(page.locator('#skip-pages')).toBeHidden();
    await page.locator('#open-file').setInputFiles(file('empty-shortcuts.tape', tape([])));
    await expectTimelinePage(page, 0, 0, 0);
    await expect(page.locator('#skip-pages')).toBeHidden();
    await expect(skipDetails(page)).toBeVisible();
    await detailsFromFilters(page, key);
    await expect(page.locator('#inspector')).toHaveAttribute('tabindex', '-1');
    await expect(page.locator('#inspector')).toContainText('Select a recorded call to inspect its input and output.');

    await page.locator('#compare-file').setInputFiles(file('comparison-only.tape', tape([
      { name: 'comparison.only', input: '{"source":"comparison"}' }
    ])));
    await expect(page.locator('#compare-title')).toContainText('call missing from primary');
    await detailsFromFilters(page, key);
    await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
    await expect(page.getByLabel('comparison input', { exact: true })).toContainText('comparison');
    await expectTimelinePage(page, 0, 0, 0);
    await expect(page.locator('#skip-pages')).toBeHidden();
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('comparison input', { exact: true })).toBeFocused();
  });
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
  test(`Escape clears filters while preserving ${focusedControl} focus and a selected call beyond 250`, async ({ page }, testInfo) => {
    const rows = Array.from({ length: 251 }, (_, index) => ({
      name: 'step.' + index,
      status: index === 250 ? 'ERROR' : 'OK',
      output: index === 250 ? 'java.io.IOException' : '{}'
    }));
    await page.locator('#open-file').setInputFiles(file('filtered.tape', tape(rows)));
    await expect(page.locator('#stat-calls')).toHaveText('251');
    await expect(page.getByRole('alert')).toBeHidden();
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
    await expectTimelinePage(page, 251, 251, 251);
    await expect(callAt(page, 250)).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('#inspector h2')).toHaveText('step.250');
    await page.keyboard.press('Tab');
    const firstTabTarget = await page.evaluate(() => ({
      tag: document.activeElement.tagName,
      id: document.activeElement.id,
      callPosition: document.activeElement.getAttribute('data-position')
    }));
    await testInfo.attach('post-escape-first-tab', {
      body: Buffer.from(JSON.stringify({ project: testInfo.project.name, focusedControl, firstTabTarget }, null, 2)),
      contentType: 'application/json'
    });
    if (focusedControl === 'search') {
      await expect(page.locator('#errors-only')).toBeFocused();
    } else {
      await expectVisibleKeyboardFocus(skipPages(page));
      await page.keyboard.press('Tab');
      await expectVisibleKeyboardFocus(skipDetails(page));
      await page.keyboard.press('Tab');
      await passNativeTimelineStop(page, 'Tab');
      await expect(calls(page).first()).toBeFocused();
      await page.keyboard.press('Tab');
      await expect(previousPage(page)).toBeFocused();
      await page.keyboard.press('Shift+Tab');
      await expect(callAt(page, 250)).toBeFocused();
      await expect(callAt(page, 250)).toHaveAttribute('aria-pressed', 'true');
      await expect(page.locator('#inspector h2')).toHaveText('step.250');
    }
  });
}

test('Escape from a filtered row keeps its bounded page and an off-page selected inspector', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({
    name: (index >= 499 ? 'tail.' : 'step.') + index,
    status: index >= 499 ? 'ERROR' : 'OK',
    output: index >= 499 ? 'java.io.IOException' : '{}'
  }));
  await page.locator('#open-file').setInputFiles(file('filtered-tail.tape', tape(rows)));
  await expect(page.locator('#stat-calls')).toHaveText('501');
  await expect(page.getByRole('alert')).toBeHidden();
  await page.getByRole('searchbox').fill('tail.');
  await page.getByLabel('Errors only', { exact: true }).check();
  await expectTimelinePage(page, 1, 2, 2, 499, 500);
  await callAt(page, 500).focus();
  await page.keyboard.press('Enter');
  await callAt(page, 499).focus();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(page.getByLabel('Errors only', { exact: true })).not.toBeChecked();
  await expectTimelinePage(page, 251, 500, 501);
  await expect(callAt(page, 499)).toBeFocused();
  await expect(callAt(page, 499)).toHaveAttribute('aria-pressed', 'false');
  await expect(callAt(page, 500)).toHaveCount(0);
  await expect(page.locator('#inspector h2')).toHaveText('tail.500');
  await page.keyboard.press('Tab');
  await expect(previousPage(page)).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(nextPage(page)).toBeFocused();
  await page.keyboard.press('Enter');
  await expectTimelinePage(page, 501, 501, 501);
  await expect(callAt(page, 500)).toBeFocused();
  await expect(callAt(page, 500)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#inspector h2')).toHaveText('tail.500');
});

test('Escape clears filters and preserves focus on an available Next page button', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({ name: 'step.' + index }));
  await page.locator('#open-file').setInputFiles(file('filtered-pagination.tape', tape(rows)));
  await page.getByRole('searchbox').fill('step.');
  await expectTimelinePage(page, 1, 250, 501);
  await nextPage(page).focus();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expectTimelinePage(page, 1, 250, 501);
  await expect(nextPage(page)).toBeFocused();
  await expect(callAt(page, 0)).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Space');
  await expectTimelinePage(page, 251, 500, 501);
  await expect(callAt(page, 250)).toBeFocused();
});

test('Escape preserves focus on an available Previous page button', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({ name: 'step.' + index }));
  await page.locator('#open-file').setInputFiles(file('filtered-previous.tape', tape(rows)));
  await nextPage(page).click();
  await callAt(page, 400).click();
  await page.getByRole('searchbox').fill('step.');
  await nextPage(page).click();
  await previousPage(page).focus();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expectTimelinePage(page, 251, 500, 501);
  await expect(previousPage(page)).toBeFocused();
  await expect(callAt(page, 400)).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Space');
  await expectTimelinePage(page, 1, 250, 501);
  await expect(callAt(page, 0)).toBeFocused();
  await expect(page.locator('#inspector h2')).toHaveText('step.400');
});

test('Escape moves focus off a pagination button that becomes disabled', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({ name: 'step.' + index }));
  await page.locator('#open-file').setInputFiles(file('filtered-last-selection.tape', tape(rows)));
  await nextPage(page).click();
  await nextPage(page).click();
  await callAt(page, 500).click();
  await page.getByRole('searchbox').fill('step.');
  await expectTimelinePage(page, 1, 250, 501);
  await nextPage(page).focus();
  await page.keyboard.press('Escape');
  await expectTimelinePage(page, 501, 501, 501);
  await expect(nextPage(page)).toBeDisabled();
  await expect(callAt(page, 500)).toBeFocused();
  await expect(callAt(page, 500)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#inspector h2')).toHaveText('step.500');
});

test('changing search or Errors only resets the page and preserves the selected inspector', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({ name: 'step.' + index }));
  await page.locator('#open-file').setInputFiles(file('reset-page.tape', tape(rows)));
  await nextPage(page).click();
  await nextPage(page).click();
  await callAt(page, 500).click();
  await expectTimelinePage(page, 501, 501, 501);
  await page.getByRole('searchbox').fill('step.');
  await expectTimelinePage(page, 1, 250, 501);
  await expect(page.locator('#inspector h2')).toHaveText('step.500');
  await nextPage(page).click();
  await expectTimelinePage(page, 251, 500, 501);
  await page.getByLabel('Errors only', { exact: true }).check();
  await expectTimelinePage(page, 0, 0, 0);
  await expect(page.locator('#timeline')).toContainText('No calls match these filters.');
  await expect(page.locator('#inspector h2')).toHaveText('step.500');
  await page.getByLabel('Errors only', { exact: true }).uncheck();
  await expectTimelinePage(page, 1, 250, 501);
  await expect(page.locator('#inspector h2')).toHaveText('step.500');
});

test('keyboard divergence navigation beyond 250 survives Escape and continues in both directions', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({ name: 'step.' + index }));
  await page.locator('#open-file').setInputFiles(file('primary-many.tape', tape(rows)));
  await expectTimelinePage(page, 1, 250, 501);
  const changed = rows.map((row, index) => index === 400 ? { ...row, output: '{"changed":true}' } : row);
  await page.locator('#compare-file').setInputFiles(file('comparison-many.tape', tape(changed)));
  await expect(page.locator('#compare-title')).toContainText('position 401');
  await page.getByRole('searchbox').fill('no-matches');
  await page.getByLabel('Errors only', { exact: true }).check();
  await page.getByRole('button', { name: 'Inspect divergence', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(page.getByLabel('Errors only', { exact: true })).not.toBeChecked();
  await expectTimelinePage(page, 251, 500, 501);
  await expect(callAt(page, 400)).toBeFocused();
  await expect(callAt(page, 400)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('true');
  await page.keyboard.press('Escape');
  await expectTimelinePage(page, 251, 500, 501);
  await expect(callAt(page, 400)).toBeFocused();
  await expect(callAt(page, 400)).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Tab');
  await expect(callAt(page, 401)).toBeFocused();
  await callAt(page, 499).focus();
  await page.keyboard.press('Tab');
  await expect(previousPage(page)).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(nextPage(page)).toBeFocused();
  await page.keyboard.press('Enter');
  await expectTimelinePage(page, 501, 501, 501);
  await expect(callAt(page, 500)).toBeFocused();
  await expect(callAt(page, 400)).toHaveCount(0);
  await expect(page.locator('#inspector h2')).toHaveText('step.400');
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('true');
  await page.keyboard.press('Tab');
  await expect(previousPage(page)).toBeFocused();
  await page.keyboard.press('Space');
  await expectTimelinePage(page, 251, 500, 501);
  await expect(callAt(page, 250)).toBeFocused();
  await expect(callAt(page, 400)).toHaveAttribute('aria-pressed', 'true');
});

test('missing-primary divergence clamps to the last bounded page and focuses the inspector', async ({ page }) => {
  const rows = Array.from({ length: 501 }, (_, index) => ({ name: 'step.' + index }));
  await page.locator('#open-file').setInputFiles(file('shorter-primary.tape', tape(rows.slice(0, 500))));
  await page.locator('#compare-file').setInputFiles(file('longer-comparison.tape', tape(rows)));
  await expect(page.locator('#compare-title')).toContainText('position 501');
  await expect(page.locator('#compare-title')).toContainText('call missing from primary');
  await page.getByRole('searchbox').fill('no-matches');
  await page.locator('#jump-divergence').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expectTimelinePage(page, 251, 500, 500);
  await expect(page.locator('#inspector')).toBeFocused();
  await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
  await expect(page.locator('#timeline button[aria-pressed="true"]')).toHaveCount(0);
  await expect(page.getByLabel('comparison input', { exact: true })).toHaveText('{}');
  await previousPage(page).click();
  await expectTimelinePage(page, 1, 250, 500);
  await expect(callAt(page, 0)).toBeFocused();
  await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
  await nextPage(page).click();
  await expectTimelinePage(page, 251, 500, 500);
  await expect(callAt(page, 250)).toBeFocused();
  await expect(page.locator('#inspector')).toContainText('This call is missing from the primary tape.');
  await openFixture(page);
  await expectTimelinePage(page, 1, 2, 2);
  await expect(page.locator('#comparison')).toBeHidden();
});

test('50,000-call file inputs keep last-divergence inspection and navigation within 250 rendered cards', async ({ page, browser }, testInfo) => {
  // A finite functional-regression budget, not a speed benchmark or timing claim.
  // This scenario runs in every configured desktop/mobile engine project.
  test.setTimeout(120000);
  const count = 50000;
  const rows = Array.from({ length: count }, (_, index) => ({
    name: 'step.' + index,
    nanos: '1000',
    input: JSON.stringify({ sequence: index, source: 'synthetic' }),
    output: JSON.stringify({ value: index })
  }));
  const primary = file('synthetic-50000-primary.tape', tape(rows));
  const comparison = file('synthetic-50000-last-drift.tape', tape(rows.map((row, index) =>
    index === count - 1 ? { ...row, output: '{"value":-1}' } : row)));
  for (const input of [primary, comparison]) {
    expect(input.buffer.length, 'the ordinary input fits the documented 16 MiB limit').toBeLessThanOrEqual(16 * 1024 * 1024);
    expect(input.buffer.toString('utf8').split('\n').length - 2).toBe(count);
  }
  await page.evaluate(() => {
    const timeline = document.getElementById('timeline');
    window.__tooltapePageSamples = [];
    const observer = new MutationObserver(() => {
      window.__tooltapePageSamples.push({
        renderedCallCards: timeline.querySelectorAll('button[data-position]').length,
        range: document.getElementById('visible-count').textContent
      });
    });
    observer.observe(timeline, { childList: true });
  });
  // Use the public file inputs, without replacing the parser or viewer state.
  await page.locator('#open-file').setInputFiles(primary);
  await expect(page.locator('#stat-calls')).toHaveText('50,000', { timeout: 30000 });
  await expect(page.getByRole('alert')).toBeHidden();
  await expectTimelinePage(page, 1, 250, count);
  await page.locator('#compare-file').setInputFiles(comparison);
  await expect(page.locator('#compare-title')).toHaveText('First divergence · position 50000 · output / error', { timeout: 30000 });
  await page.getByRole('searchbox').fill('no-matching-synthetic-tool');
  await page.getByLabel('Errors only', { exact: true }).check();
  await expectTimelinePage(page, 0, 0, 0);
  await page.locator('#jump-divergence').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(page.getByLabel('Errors only', { exact: true })).not.toBeChecked();
  await expectTimelinePage(page, 49751, 50000, count);
  const last = callAt(page, count - 1);
  await expect(last).toBeFocused();
  await expect(last).toHaveAccessibleName('Call 49999: step.49999, OK');
  await expect(last).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#inspector h2')).toHaveText('step.49999');
  await expect(page.getByLabel('input', { exact: true })).toContainText('49999');
  await expect(page.getByLabel('output', { exact: true })).toContainText('49999');
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('-1');
  await page.keyboard.press('Escape');
  await expectTimelinePage(page, 49751, 50000, count);
  await expect(last).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(previousPage(page)).toBeFocused();
  await page.keyboard.press('Space');
  await expectTimelinePage(page, 49501, 49750, count);
  await expect(callAt(page, 49500)).toBeFocused();
  await expect(last).toHaveCount(0);
  await expect(page.locator('#timeline button[aria-pressed="true"]')).toHaveCount(0);
  await expect(page.locator('#inspector h2')).toHaveText('step.49999');
  await expect(page.getByLabel('comparison output', { exact: true })).toContainText('-1');
  await callAt(page, 49749).focus();
  await page.keyboard.press('Tab');
  await expect(previousPage(page)).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(nextPage(page)).toBeFocused();
  await page.keyboard.press('Enter');
  await expectTimelinePage(page, 49751, 50000, count);
  await expect(callAt(page, 49750)).toBeFocused();
  await expect(last).toHaveAttribute('aria-pressed', 'true');

  // Changing a filter starts from its first bounded page; jumping again restores
  // the true final position rather than retaining an obsolete page offset.
  await page.getByRole('searchbox').fill('step.');
  await expectTimelinePage(page, 1, 250, count);
  await expect(page.locator('#inspector h2')).toHaveText('step.49999');
  await page.locator('#jump-divergence').focus();
  await page.keyboard.press('Space');
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expectTimelinePage(page, 49751, 50000, count);
  await expect(last).toBeFocused();
  await expect(last).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
    'the bounded late-divergence page fits the viewport').toBe(true);
  const samples = await page.evaluate(() => window.__tooltapePageSamples);
  expect(samples.length, 'actual timeline replacements were observed').toBeGreaterThan(0);
  expect(Math.max(...samples.map(sample => sample.renderedCallCards)),
    'no observed timeline replacement mounted more than one page').toBeLessThanOrEqual(250);
  const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
  const evidence = {
    kind: 'deterministic synthetic TOOLTAPE v1, public file-input loading',
    generator: 'viewer/browser-tests/viewer.spec.cjs',
    callCount: count,
    primary: { name: primary.name, bytes: primary.buffer.length, sha256: sha256(primary.buffer) },
    comparison: { name: comparison.name, bytes: comparison.buffer.length, sha256: sha256(comparison.buffer) },
    changedField: 'Only the last output changes from {"value":49999} to {"value":-1}.',
    pageSizeLimit: 250,
    samples
  };
  await testInfo.attach('bounded-50000-call-evidence', {
    body: Buffer.from(JSON.stringify({ project: testInfo.project.name, ...evidence }, null, 2)),
    contentType: 'application/json'
  });
  if (testInfo.project.name === 'desktop-chromium') {
    const directory = path.join(__dirname, 'artifacts/screenshots', testInfo.project.name);
    await fs.mkdir(directory, { recursive: true });
    for (const input of [primary, comparison]) {
      const fixturePath = path.join(directory, input.name);
      await fs.writeFile(fixturePath, input.buffer);
      await testInfo.attach(input.name, { path: fixturePath, contentType: 'text/plain' });
    }
    await capture(page, browser, testInfo, 'viewer-large-divergence',
      'Deterministic synthetic 50,000-call v1 tapes; only the last output differs; final 250-call page with call 49999 selected.',
      path.join(directory, primary.name), evidence);
  }
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
