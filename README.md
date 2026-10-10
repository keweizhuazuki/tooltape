<div align="center">

# ToolTape
### One failure. A repeatable regression.

**Record serial tool calls · Replay offline · Find the first divergence**

[English](README.md) | [简体中文](README.zh-CN.md)

[![Regression CI](https://github.com/keweizhuazuki/tooltape/actions/workflows/ci.yml/badge.svg)](https://github.com/keweizhuazuki/tooltape/actions/workflows/ci.yml) [![Browser QA](https://github.com/keweizhuazuki/tooltape/actions/workflows/browser.yml/badge.svg)](https://github.com/keweizhuazuki/tooltape/actions/workflows/browser.yml)

[Quick start](#quick-start) · [API](#use-it-in-your-code) · [Security](SECURITY.md) · [Format](docs/FORMAT.md) · [Roadmap](#next-upgrade)

</div>

ToolTape turns a Java tool boundary into a reusable test fixture. Record the order, selected arguments, results and exception types once; rerun your orchestration against an offline stub that fails at the first unexpected tool call or argument. No server, account, API key or runtime dependency.

> **Scope:** v0.2 is for serial tool orchestration. It does not record/replay an LLM, stream tokens, reproduce concurrency, or sandbox your Java process. Route external effects through `Tool`. The default policy captures no input/output fields; explicitly allow safe scalar fields.

## A real recording, in context

![ToolTape v2 session timeline and recorded tool failure](docs/images/viewer-session.png)

Actual Chromium capture of the checked-in v2 retry fixture. [Screenshot provenance](docs/images/viewer-session.json) records the exact source commit, browser version and file hashes; [the browser run](https://github.com/keweizhuazuki/tooltape/actions/runs/38039531019) passed all 153 active checks across five engine/viewport projects.

<details>
<summary>See the first-divergence comparison and mobile layout</summary>

![First differing tool result in the offline comparison viewer](docs/images/viewer-diff.png)

<img src="docs/images/viewer-mobile.png" alt="ToolTape viewer in 390px Chromium mobile emulation" width="390">

The mobile image is browser emulation, not a physical-device claim. PNGs are direct browser screenshots with no compositing.

</details>

## Why use it?

- **Reproduce a failure without a live service.** A recorded timeout becomes a controlled `RecordedToolException`; the next call returns the recorded response.
- **Catch regressions at the boundary.** Tool name, allowlisted arguments, missing calls and extra calls are checked; closing a replay asserts complete consumption.
- **Review fixtures locally.** CLI validation/diff and a standalone timeline viewer need no backend.
- **Keep existing tests.** A zero-dependency core works with ordinary Java and JUnit; Spring AI integration guidance is based on a real project's dispatch path.

## Quick start

### Prebuilt CLI (Java 17+)

CI produces a verified JAR and SHA-256 checksums. It is not published on Maven Central. Tagged prereleases, when available, are listed under [Releases](https://github.com/keweizhuazuki/tooltape/releases).

The tested v0.2 build can currently be downloaded from its successful CI run:

```sh
gh run download 38042920703 --repo keweizhuazuki/tooltape --name tooltape-dist-9813415d22ccc406a47a64cdfe254093a00536c7
sha256sum -c SHA256SUMS              # Linux; macOS: shasum -a 256 -c SHA256SUMS
java -jar tooltape-0.2.0.jar --version
java -jar tooltape-0.2.0.jar inspect path/to/your.tape
```

CI artifacts have 30-day retention and may require GitHub CLI sign-in; source builds below remain available. On Windows, compare `(Get-FileHash tooltape-0.2.0.jar -Algorithm SHA256).Hash` with the value in `SHA256SUMS` before running the JAR.

### Build from source

Install a **full JDK 17 or 21** and Git. The local viewer is included in the clone.

#### Linux / macOS

```sh
git clone https://github.com/keweizhuazuki/tooltape.git
cd tooltape
./build.sh
java -cp target/tooltape.jar:target/example-classes RetryRegression
java -jar target/tooltape.jar inspect target/weather.tape
```

#### Windows PowerShell

For portable Windows CLI jobs, use ASCII-only full paths. Hosted JDK 17/21 launchers can replace characters outside the active code page before ToolTape receives them; quoting alone does not fix this. UTF-8 tape contents remain supported. [Measured filename limitation →](docs/BATCH_REPORTS.md#windows-filename-transport)

```powershell
git clone https://github.com/keweizhuazuki/tooltape.git
cd tooltape
./build.ps1
java -cp 'target/tooltape.jar;target/example-classes' RetryRegression
java -jar target/tooltape.jar inspect target/weather.tape
```

The demo creates `target/weather.tape` with CREATE_NEW. To rerun, choose a fresh path: `RetryRegression target/weather-2.tape`. Existing fixtures are never silently overwritten.

### Actual demo output

```text
PASS: recorded timeout + retry, replayed offline; live calls=2
First divergence at event 0: arguments at /city
expected: {"city":"Hangzhou"}
actual:   {"city":"Shanghai"}
```

The two live calls happen during recording. Replay adds zero live calls. The changed-city variant intentionally demonstrates a regression.

## CI-friendly reports

```sh
java -jar target/tooltape.jar validate --json fixtures/weather-retry-v2.tape
java -jar target/tooltape.jar diff --json expected.tape actual.tape > report.json
```

JSON reports have a versioned contract and exit codes: **0** valid/matching, **1** first event divergence, **2** invalid input, incomplete session or incompatible v2 capture policies. Reports omit captured values, tool names, file paths and JSON key names, including on errors. Legacy v1 comparisons explicitly report unverified policy/completion. Human-readable commands remain unchanged and can expose recorded values.

For v2-only CI jobs, add `--require-v2` immediately after `--json` in either command. Legacy inputs then fail with exit 2 and `LEGACY_TAPE`, while default behavior and the report v1 structure stay unchanged. This checks schema eligibility, not authenticity or business success.

For an explicit fixture inventory, `validate-batch --json [--require-v2] <file>...` checks up to 64 files serially, collects ordinary failures and returns one index-only report with aggregate exit 0/2. Existing single-file commands stay unchanged. [Batch contract and portable CI examples →](docs/BATCH_REPORTS.md)

[Report schema, compatibility rules and shell examples →](docs/CLI_REPORTS.md)

## Use it in your code

```java
CapturePolicy policy = CapturePolicy.allow(
    Set.of("city"),                 // explicitly reviewed input fields
    Set.of("temperature")           // output fields needed by the test
);

Tool live = (name, arguments) -> weatherService.lookup(arguments);
try (Recorder recorder = new Recorder(Path.of("failure.tape"), live, policy)) {
    runMyWorkflow(recorder);         // your orchestration, including retries
}

try (Replay replay = new Replay(Tape.read(Path.of("failure.tape")), policy)) {
    runMyWorkflow(replay);           // no live implementation is passed in
}                                   // fails if recorded calls remain unused
```

`Tool` accepts and returns `Map<String,Object>` of JSON-compatible data. Recorded output is sanitized, so allow the fields your workflow requires. Only explicitly allowed top-level scalars are captured; nested values become `[REDACTED]`. Credential-like field names are always excluded. Excluded input fields cannot be regression-checked.

New recordings are schema v2. The hardened `scalar-v2` policy deliberately rejects earlier v2 fingerprints; [migration details](docs/SPRING_AI.md#policy-hardening-migration) explain why fixtures must be reviewed and re-recorded. They store a SHA-256 identity of the allowlist configuration and an optional static source label (`new Recorder(path, tool, policy, "my-workflow")`). Replay rejects policy changes and recordings without a valid closed-session marker before executing your workflow. Closing a recording is not proof of business success. Existing v1 fixtures remain readable with policy/completeness checks unavailable.

Recorded exceptions use `Replay.RecordedToolException.originalType()`, not the original Java subclass. Normalize errors in a domain adapter when business code branches on exception type. The executable [retry example](examples/RetryRegression.java) shows this explicitly.

### JUnit

```sh
mvn test
```

[RegressionJUnitTest](src/test/java/dev/tooltape/RegressionJUnitTest.java) contains a checked-in failure fixture, complete replay assertion and changed-argument failure. `mvn install` installs the library locally for another Maven project; coordinates are `dev.tooltape:tooltape:0.2.0` and are **not** remotely published.

[Verified Spring AI / kewei-ai-agent integration →](docs/SPRING_AI.md)

The optional application adapter is merged and tested through its real `think → act` dispatch with a fake model: 24 focused integration tests and packaging passed. It stays off by default, pins ToolTape source, preserves ToolContext in memory and fails clearly if output was not captured. It is not a separately published framework module.

## Inspect and compare

```sh
java -jar target/tooltape.jar validate fixtures/weather-retry.tape
java -jar target/tooltape.jar inspect fixtures/weather-retry.tape
java -jar target/tooltape.jar diff fixtures/weather-retry.tape target/weather.tape
```

Exit codes: `0` valid/equal, `1` first semantic difference, `2` malformed file, I/O or usage error. Timing and metadata are shown but ignored in event comparisons. Validate first; a semantic diff does not establish completeness. Input/output differences include a JSON pointer such as `/city`.

Open [viewer/index.html](viewer/index.html) from your local clone. Choose a `.tape`, filter failures, inspect arguments/results, or load a second tape to find the first difference. The viewer uses local File APIs and blocks network connections with CSP. CI exercises Chromium, Firefox and WebKit on Ubuntu, including 390px Chromium/WebKit mobile emulation. Linux WebKit is not branded Safari or a physical iOS device; native OS file-picker dialogs are not validated and no mobile app is claimed.

## Where it fits

[Langfuse](https://github.com/langfuse/langfuse) provides LLM observability and evaluation; [Promptfoo](https://github.com/promptfoo/promptfoo) provides prompt/agent testing and evaluation workflows; [WireMock](https://github.com/wiremock/wiremock) mocks HTTP services. ToolTape is a small Java tool-boundary fixture/replay library. It does not replace these projects, claim a novel category, or import their formats in v0.2.

## Verification

- Local Linux/JDK 21: **45 dependency-free acceptance checks + 24 JUnit tests passed**; 164 single-command and 100 batch CLI subprocess cases also passed, including JSON privacy and exit codes; CLI and retry example executed. The environment lacks a complete `javac` launcher/`ct.sym`, so local compilation used the JDK compiler module with source/target 17; this alone is not proof of Java 17 API compatibility.
- [Verified v0.2 CI run](https://github.com/keweizhuazuki/tooltape/pull/10/checks): all six Linux/Windows/macOS × JDK 17/21 builds, Maven/JUnit and viewer tests passed. The first Windows run exposed Git CRLF conversion of fixtures; `.gitattributes` now preserves LF and the rerun passed.
- Viewer: 232 parser/canonicalization checks and 72 DOM-model interaction checks passed. [Real browser CI](https://github.com/keweizhuazuki/tooltape/actions/runs/38039531019) passed 153 checks across Chromium, Firefox and WebKit desktop/mobile-emulation projects, with two intentional mobile duplicate screenshot skips and no failures or flakes. Screenshots are real browser captures, not illustrations.

## Completed in v0.2

- Bounded, ordered fixture-inventory validation with an independent JSON envelope, safe per-input failures and aggregate CI status

- Opt-in `--require-v2` gate for JSON validation/comparison, with safe legacy rejection and unchanged defaults

- Cross-engine offline viewer checks with explicit Chromium/Firefox/WebKit projects and isolated screenshot provenance

- Increased small-text contrast with computed-color browser checks across default, hover, selected and focus states

- Keyboard focus retained through timeline pagination, filter reset and comparison dismissal; hidden file inputs no longer create invisible Tab stops

- CI action runtimes upgraded to reviewed official releases and full commit pins; checkout credentials are not persisted ([maintenance notes](docs/CI_MAINTENANCE.md))

- Versioned, privacy-conscious JSON validate/diff reports with subprocess exit-code checks on all supported CI platforms

- Hardening: normalized credential keys, bounded exponent expansion/node counts, preflight input budgets and owner-only new POSIX recordings
- Real opt-in kewei-ai-agent integration, with reviewed-output capture and no live callback during replay
- Policy-bound v2 recording sessions, explicit source labels and close markers; incomplete/mismatched-policy replay fails before calls
- Backward-compatible v1 reading, clear legacy/incomplete states in CLI and viewer
- RFC 6901 JSON-pointer locations for the first differing input/output
- Real desktop/mobile Chromium tests and screenshot provenance generated in CI
- Deterministic JAR timestamps, rebuild byte-comparison and checksummed CI distribution artifacts
- Pinned-source integration route for the actual Spring AI project; no fake Maven Central coordinates

### v0.1 foundation

- Zero-dependency serial recorder/replayer with privacy-first scalar capture
- Strict tape parsing, CLI inspect/validate/diff, JUnit and runnable retry fixtures
- Local timeline viewer and verified Linux/macOS/Windows × Java 17/21 matrix
- Windows fixture line-ending fix and separate English/Chinese documentation

## Next upgrade

1. Extract the tested application adapter into a versioned Spring AI integration module; validate a second application and framework version before promising broad compatibility.
2. Evaluate paired-comparison inventories after demonstrating multiple independently generated regression scenarios; keep candidate recording and baseline updates explicit.
3. Extend cross-engine checks with assistive-technology review and physical-device testing; engine emulation does not establish native Safari/iOS or Android compatibility.
4. Design explicit safe nested-field projections and per-tool policies without exposing arbitrary strings or silently weakening existing fingerprints.
5. Add authenticated fixture provenance only with a clear key-management design; current source labels/checksums do not establish origin authenticity.

Keep completed/next sections synchronized in English and Chinese. These future items are not shipped features. Model and parallel replay remain out of scope.

## License

No license has been selected yet. Public visibility is not an open-source license grant. Please ask the owner before reuse or redistribution beyond rights already provided by applicable law and GitHub's terms.
