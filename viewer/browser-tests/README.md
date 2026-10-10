# Actual browser checks and screenshots

English | [简体中文](README.zh-CN.md)

These development-only checks open `viewer/index.html` directly through `file://` in real Chromium, Firefox and WebKit. They do not start a web server, tunnel, deployment, or external service. The viewer still needs no runtime dependencies. Test contexts are offline, and each test additionally rejects unexpected non-file requests and uncaught JavaScript errors.

## Verified run

[Run 38039531019](https://github.com/keweizhuazuki/tooltape/actions/runs/38039531019), source `1d9d113`, passed 153 checks with two intentional mobile duplicate screenshot skips and no failures or retries. All 11 screenshot/provenance pairs were downloaded, hash-checked and visually inspected. README images select Chromium explicitly; other engine captures remain in the workflow artifact.

The keyboard regression records the actual post-Escape Tab target. Firefox 157 gives the overflowing timeline its own native Tab stop; Chromium 156 and WebKit 27.2 enter the first row directly. The test permits only that exact visible overflowing container, then requires forward/backward row traversal while preserving the selected call. It does not skip keyboard checks for any engine.

[Computed contrast evidence](../../docs/contrast-results.json) covers 114 sampled text/state combinations per desktop project and 113 per mobile project. The lowest sampled ratio was 4.89479 in every project; this is scoped evidence, not a full accessibility certification.

## Reproduce

Use Node.js 22 or newer on a machine that permits browser processes:

```sh
cd viewer
npm ci --ignore-scripts --no-audit --no-fund
npx --no-install playwright install --with-deps chromium firefox webkit
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
- Playwright file-drop input through the real drag/drop handlers, including multiple-file rejection
- Deferred file-read races and bounded 250-call pages, including a 50,000-call last-divergence file-input scenario in every project
- Keyboard focus after pagination/filter reset/comparison dismissal, visible Tab stops, and Enter/Space file-chooser activation
- Computed small-text contrast for sampled labels, placeholders and timeline states, including transparency/opacity composition
- v2 session closure, incomplete recording warnings, provenance, and capture-policy drift
- Desktop side-by-side and mobile stacked layout, with no horizontal page overflow

The configured projects are:

| Project | Engine | Viewport | Emulation |
| --- | --- | --- | --- |
| desktop-chromium | Chromium | 1440 × 1000 | Desktop |
| mobile-chromium | Chromium | 390 × 844 | Mobile + touch |
| desktop-firefox | Firefox | 1440 × 1000 | Desktop |
| desktop-webkit | WebKit | 1440 × 1000 | Desktop |
| mobile-webkit | WebKit | 390 × 844 | Mobile + touch |

All hosted projects run on Ubuntu 24.04. Playwright WebKit is not branded Safari, and mobile emulation is not physical Android/iPhone or operating-system file-picker validation. The test runner controls browser file selection; it does not inspect native operating-system dialogs. These assertions and real screenshots are not a full accessibility audit or pixel-baseline visual-regression system.

## Screenshot outputs

The real screenshot tests write these files under `viewer/browser-tests/artifacts/screenshots/<project-name>/`, so engines cannot overwrite each other:

- `viewer-baseline.png`: unchanged checked-in `fixtures/weather-retry.tape`, first exception selected, 1440px viewport
- `viewer-diff.png`: that fixture compared with a synthetic regression changing only the successful temperature output from 22 to 23
- `viewer-mobile.png`: unchanged fixture, 390px mobile emulation
- `viewer-session.png`: unchanged `fixtures/weather-retry-v2.tape`, showing the closed session and self-reported provenance
- `viewer-large-divergence.png`: Chromium desktop only; two deterministic synthetic 50,000-call v1 tapes differ at the last output, showing the final 250-call page. Both generated tapes and their hashes are included in the artifact.

Each PNG has an attached JSON sidecar with screenshot/source/fixture SHA-256 hashes, commit/run IDs, actual engine and browser/test-runner versions, viewport, mobile/touch settings, observed DPR/user-agent, and input description. Captures come directly from the named engine without compositing or image replacement. Three desktop projects each capture baseline/session/diff; two mobile projects each capture the mobile baseline (11 PNG/JSON pairs), plus the Chromium large-recording capture (12 pairs total). The large-recording test has a finite 120-second functional-test budget and checks observed DOM replacements; it is not a latency benchmark.

Use the named project when selecting README images; never copy whichever engine happens to finish last. Current hero images explicitly identify their source engine.

Download the workflow artifact and inspect the actual PNGs before copying them into `docs/images/` and referencing them from the README. The artifact also contains `artifacts/test-results.json`, `report/index.html`, and failure screenshots/traces under `results/`. A screenshot proves the pictured state rendered; the accompanying successful test result establishes the interaction assertions for that commit.
