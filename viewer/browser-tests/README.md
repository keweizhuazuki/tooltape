# Actual browser checks and screenshots

English | [简体中文](README.zh-CN.md)

These development-only checks open `viewer/index.html` directly through `file://` in real Chromium. They do not start a web server, tunnel, deployment, or external service. The viewer still needs no runtime dependencies. Test contexts are offline, and each test additionally rejects unexpected non-file requests and uncaught JavaScript errors.

## Reproduce

Use Node.js 22 or newer on a machine that permits browser processes:

```sh
cd viewer
npm ci --ignore-scripts --no-audit --no-fund
npx --no-install playwright install --with-deps chromium
npm test
npm run test:browser
```

`npm run test:browser:list` enumerates tests without launching a browser. Browser installation can require system-package privileges on Linux. Do not retry a denied browser launch by disabling security settings or changing execution routes.

The committed lockfile pins the Playwright test runner and browser-install version. `.github/workflows/browser.yml` runs these commands on `ubuntu-24.04` for pushes, pull requests, and manual dispatch. The workflow has read-only repository permission and uses no secrets. Its artifact is named `viewer-browser-<commit SHA>` and expires after 14 days.

## Coverage

- Real file chooser and repeated selection of the same fixture
- Built-in demo, error filter, payload search, keyboard selection, and Escape reset
- Timing-insensitive comparison, divergence focus, comparison clearing, and primary replacement
- Canonical JSON equality and exact signed-64-bit integers
- Inline malformed, invalid UTF-8, malformed base64url, and oversized-file errors without losing valid state
- HTML-like payload and filename rendering as inert text
- Empty tapes and inspection of missing primary calls
- Browser `File` / `DataTransfer` drop handling and rejection of multiple dropped files
- Deferred file-read races and timeline pagination
- Keyboard focus after pagination/filter reset/comparison dismissal, visible Tab stops, and Enter/Space file-chooser activation
- v2 session closure, incomplete recording warnings, provenance, and capture-policy drift
- Desktop side-by-side and mobile stacked layout, with no horizontal page overflow

The mobile project uses Chromium emulation at 390 × 844. It is not physical Android/iPhone, Safari, or operating-system file-picker validation. The test runner controls browser file selection; it does not inspect native operating-system dialogs. These assertions and real screenshots are not a full accessibility audit or pixel-baseline visual-regression system.

## Screenshot outputs

The real screenshot tests write these files under `viewer/browser-tests/artifacts/screenshots/`:

- `viewer-baseline.png`: unchanged checked-in `fixtures/weather-retry.tape`, first exception selected, 1440px viewport
- `viewer-diff.png`: that fixture compared with a synthetic regression changing only the successful temperature output from 22 to 23
- `viewer-mobile.png`: unchanged fixture, 390px Chromium mobile emulation
- `viewer-session.png`: unchanged `fixtures/weather-retry-v2.tape`, showing the closed session and self-reported provenance

Each PNG has a JSON sidecar with source and fixture SHA-256 hashes, commit and workflow-run IDs when in CI, browser/test-runner versions, viewport, and a description of its input. Screenshots are captured directly by Chromium without compositing or image replacement.

Download the workflow artifact and inspect the actual PNGs before copying them into `docs/images/` and referencing them from the README. The artifact also contains `artifacts/test-results.json`, `report/index.html`, and failure screenshots/traces under `results/`. A screenshot proves the pictured state rendered; the accompanying successful test result establishes the interaction assertions for that commit.
