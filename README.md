<div align="center">

# ToolTape
### One failure. A repeatable regression.

**Record serial tool calls · Replay offline · Find the first divergence**

[English](README.md) | [简体中文](README.zh-CN.md)

[Quick start](#quick-start) · [API](#use-it-in-your-code) · [Security](SECURITY.md) · [Format](docs/FORMAT.md) · [Roadmap](#next-upgrade)

</div>

ToolTape turns a Java tool boundary into a reusable test fixture. Record the order, selected arguments, results and exception types once; rerun your orchestration against an offline stub that fails at the first unexpected tool call or argument. No server, account, API key or runtime dependency.

> **Scope:** v0.1 is for serial tool orchestration. It does not record/replay an LLM, stream tokens, reproduce concurrency, or sandbox your Java process. Route external effects through `Tool`. The default policy captures no input/output fields; explicitly allow safe scalar fields.

## Why use it?

- **Reproduce a failure without a live service.** A recorded timeout becomes a controlled `RecordedToolException`; the next call returns the recorded response.
- **Catch regressions at the boundary.** Tool name, allowlisted arguments, missing calls and extra calls are checked; closing a replay asserts complete consumption.
- **Review fixtures locally.** CLI validation/diff and a standalone timeline viewer need no backend.
- **Keep existing tests.** A zero-dependency core works with ordinary Java and JUnit; Spring AI integration guidance is based on a real project's dispatch path.

## Quick start

Install a **full JDK 17 or 21** and Git. These are source builds; no Maven Central artifact or downloadable release is claimed yet.

### Linux / macOS

```sh
git clone https://github.com/keweizhuazuki/tooltape.git
cd tooltape
./build.sh
java -cp target/tooltape.jar:target/example-classes RetryRegression
java -jar target/tooltape.jar inspect target/weather.tape
```

### Windows PowerShell

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
First divergence at event 0: arguments
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

Recorded exceptions use `Replay.RecordedToolException.originalType()`, not the original Java subclass. Normalize errors in a domain adapter when business code branches on exception type. The executable [retry example](examples/RetryRegression.java) shows this explicitly.

### JUnit

```sh
mvn test
```

[RegressionJUnitTest](src/test/java/dev/tooltape/RegressionJUnitTest.java) contains a checked-in failure fixture, complete replay assertion and changed-argument failure. `mvn install` installs the library locally for another Maven project; coordinates are `dev.tooltape:tooltape:0.1.0-SNAPSHOT` and are **not** remotely published.

[Spring AI / kewei-ai-agent adapter guide →](docs/SPRING_AI.md)

## Inspect and compare

```sh
java -jar target/tooltape.jar validate fixtures/weather-retry.tape
java -jar target/tooltape.jar inspect fixtures/weather-retry.tape
java -jar target/tooltape.jar diff fixtures/weather-retry.tape target/weather.tape
```

Exit codes: `0` valid/equal, `1` first semantic difference, `2` malformed file, I/O or usage error. Timing is shown but ignored in comparisons.

Open [viewer/index.html](viewer/index.html) from your local clone. Choose a `.tape`, filter failures, inspect arguments/results, or load a second tape to find the first difference. The viewer uses local File APIs and blocks network connections with CSP. On mobile, viewing the repository is possible but local file selection/browser behavior has not been validated; no mobile app is claimed.

## Where it fits

[Langfuse](https://github.com/langfuse/langfuse) provides LLM observability and evaluation; [Promptfoo](https://github.com/promptfoo/promptfoo) provides prompt/agent testing and evaluation workflows; [WireMock](https://github.com/wiremock/wiremock) mocks HTTP services. ToolTape is a small Java tool-boundary fixture/replay library. It does not replace these projects, claim a novel category, or import their formats in v0.1.

## Verification

- Local Linux/JDK 21: **45 dependency-free acceptance checks + 3 JUnit tests passed**; CLI and retry example executed. The environment lacks a complete `javac` launcher/`ct.sym`, so local compilation used the JDK compiler module with source/target 17; this alone is not proof of Java 17 API compatibility.
- [Verified CI run](https://github.com/keweizhuazuki/tooltape/actions/runs/38032949639): all six Linux/Windows/macOS × JDK 17/21 builds, Maven/JUnit and viewer tests passed. The first Windows run exposed Git CRLF conversion of fixtures; `.gitattributes` now preserves LF and the rerun passed.
- Viewer: 78 parser/canonicalization checks and 23 DOM-model interaction checks passed. Real-browser rendering and screenshots remain unverified because the available browser launch was blocked; no simulated screenshot is presented.

## Completed in v0.1

- Versioned tape reader/writer, canonical JSON and bounded file parsing
- Privacy-first scalar projection, credential-name exclusions, message-free exception records
- Serial recorder, offline replay, missing/extra-call detection, first divergence
- CLI inspect/validate/diff, JUnit fixture, executable retry regression
- Local timeline viewer, cross-platform build scripts and verified CI matrix
- Fixed Windows fixture line endings; English default README with a separate Chinese guide

## Next upgrade

1. Compile and integration-test a dedicated Spring AI ToolCallback adapter against pinned framework versions; preserve ToolContext behavior and define error-string projection.
2. Persist a capture-policy identifier and fixture provenance; warn about policy drift and add an explicit completion marker without breaking v1 readers.
3. Add precise JSON-path differences, viewer accessibility/browser CI and reproducible release archives with checksums.
4. Validate with a second real orchestration project before expanding into model or parallel replay.

Maintain this completed/next section with every meaningful release. Roadmap items are not implemented features.

## License

No license has been selected yet. Public visibility is not an open-source license grant. Please ask the owner before reuse or redistribution beyond rights already provided by applicable law and GitHub's terms.
