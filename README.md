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

## Why use it?

- **Reproduce a failure without a live service.** A recorded timeout becomes a controlled `RecordedToolException`; the next call returns the recorded response.
- **Catch regressions at the boundary.** Tool name, allowlisted arguments, missing calls and extra calls are checked; closing a replay asserts complete consumption.
- **Review fixtures locally.** CLI validation/diff and a standalone timeline viewer need no backend.
- **Keep existing tests.** A zero-dependency core works with ordinary Java and JUnit; Spring AI integration guidance is based on a real project's dispatch path.

## Quick start

### Prebuilt CLI (Java 17+)

The v0.2.0 prerelease includes a JAR and SHA-256 checksums; it is not published on Maven Central.

```sh
gh release download v0.2.0 --repo keweizhuazuki/tooltape --pattern tooltape-0.2.0.jar --pattern SHA256SUMS
sha256sum -c SHA256SUMS              # Linux; macOS: shasum -a 256 -c SHA256SUMS
java -jar tooltape-0.2.0.jar --version
java -jar tooltape-0.2.0.jar inspect path/to/your.tape
```

On Windows, compare `(Get-FileHash tooltape-0.2.0.jar -Algorithm SHA256).Hash` with the value in `SHA256SUMS` before running the JAR. [Release assets and notes](https://github.com/keweizhuazuki/tooltape/releases/tag/v0.2.0).

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

New recordings are schema v2. They store a SHA-256 identity of the allowlist configuration and an optional static source label (`new Recorder(path, tool, policy, "my-workflow")`). Replay rejects policy changes and recordings without a valid closed-session marker before executing your workflow. Closing a recording is not proof of business success. Existing v1 fixtures remain readable with policy/completeness checks unavailable.

Recorded exceptions use `Replay.RecordedToolException.originalType()`, not the original Java subclass. Normalize errors in a domain adapter when business code branches on exception type. The executable [retry example](examples/RetryRegression.java) shows this explicitly.

### JUnit

```sh
mvn test
```

[RegressionJUnitTest](src/test/java/dev/tooltape/RegressionJUnitTest.java) contains a checked-in failure fixture, complete replay assertion and changed-argument failure. `mvn install` installs the library locally for another Maven project; coordinates are `dev.tooltape:tooltape:0.2.0` and are **not** remotely published.

[Spring AI / kewei-ai-agent adapter guide →](docs/SPRING_AI.md)

## Inspect and compare

```sh
java -jar target/tooltape.jar validate fixtures/weather-retry.tape
java -jar target/tooltape.jar inspect fixtures/weather-retry.tape
java -jar target/tooltape.jar diff fixtures/weather-retry.tape target/weather.tape
```

Exit codes: `0` valid/equal, `1` first semantic difference, `2` malformed file, I/O or usage error. Timing and metadata are shown but ignored in event comparisons. Validate first; a semantic diff does not establish completeness. Input/output differences include a JSON pointer such as `/city`.

Open [viewer/index.html](viewer/index.html) from your local clone. Choose a `.tape`, filter failures, inspect arguments/results, or load a second tape to find the first difference. The viewer uses local File APIs and blocks network connections with CSP. Desktop Chromium and 390px Chromium mobile emulation are tested in CI. Physical phones, Safari and native OS file-picker dialogs are not validated; no mobile app is claimed.

## Where it fits

[Langfuse](https://github.com/langfuse/langfuse) provides LLM observability and evaluation; [Promptfoo](https://github.com/promptfoo/promptfoo) provides prompt/agent testing and evaluation workflows; [WireMock](https://github.com/wiremock/wiremock) mocks HTTP services. ToolTape is a small Java tool-boundary fixture/replay library. It does not replace these projects, claim a novel category, or import their formats in v0.2.

## Verification

- Local Linux/JDK 21: **45 dependency-free acceptance checks + 12 JUnit tests passed**; CLI and retry example executed. The environment lacks a complete `javac` launcher/`ct.sym`, so local compilation used the JDK compiler module with source/target 17; this alone is not proof of Java 17 API compatibility.
- [Verified v0.2 CI run](https://github.com/keweizhuazuki/tooltape/actions/runs/38033674971): all six Linux/Windows/macOS × JDK 17/21 builds, Maven/JUnit and viewer tests passed. The first Windows run exposed Git CRLF conversion of fixtures; `.gitattributes` now preserves LF and the rerun passed.
- Viewer: 170 parser/canonicalization checks and 72 DOM-model interaction checks passed. [Real browser CI](https://github.com/keweizhuazuki/tooltape/actions/runs/38033674969) passed 25 desktop/mobile Chromium checks with one intentional duplicate screenshot skip and no failures or flakes. Screenshots are real browser captures, not illustrations.

## Completed in v0.2

- Policy-bound v2 recording sessions, explicit source labels and close markers; incomplete/mismatched-policy replay fails before calls
- Backward-compatible v1 reading, clear legacy/incomplete states in CLI and viewer
- RFC 6901 JSON-pointer locations for the first differing input/output
- Real desktop/mobile Chromium tests and screenshot provenance generated in CI
- Deterministic JAR timestamps, rebuild byte-comparison and checksummed prerelease artifacts
- Pinned-source integration route for the actual Spring AI project; no fake Maven Central coordinates

### v0.1 foundation

- Zero-dependency serial recorder/replayer with privacy-first scalar capture
- Strict tape parsing, CLI inspect/validate/diff, JUnit and runnable retry fixtures
- Local timeline viewer and verified Linux/macOS/Windows × Java 17/21 matrix
- Windows fixture line-ending fix and separate English/Chinese documentation

## Next upgrade

1. Extract the tested application adapter into a versioned Spring AI integration module; validate a second application and framework version before promising broad compatibility.
2. Add browser accessibility checks and Firefox/WebKit coverage; current real-browser scope is Chromium desktop and mobile emulation.
3. Design explicit safe nested-field projections and per-tool policies without exposing arbitrary strings or silently weakening existing fingerprints.
4. Add authenticated fixture provenance only with a clear key-management design; current source labels/checksums do not establish origin authenticity.

Keep completed/next sections synchronized in English and Chinese. These future items are not shipped features. Model and parallel replay remain out of scope.

## License

No license has been selected yet. Public visibility is not an open-source license grant. Please ask the owner before reuse or redistribution beyond rights already provided by applicable law and GitHub's terms.
