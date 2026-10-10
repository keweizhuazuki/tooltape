# Replay fixtures for JUnit Jupiter

[English](JUNIT.md) | [简体中文](JUNIT.zh-CN.md)

The optional `integrations/junit-jupiter` module turns a reviewed v2 recording into a repeatable Jupiter test. The core library remains dependency-free; the adapter requires Jupiter API and your test runner. Neither artifact is published to Maven Central.

## Build and install locally

From a source checkout with a full JDK 17 or 21 and Maven:

```sh
mvn -B clean install
mvn -B -f integrations/junit-jupiter/pom.xml clean install
```

The same command syntax can be used in PowerShell; this adapter’s hosted compatibility jobs currently run on Ubuntu, not native Windows/macOS. They install this checkout's artifacts into your local Maven repository; they do not publish a release. Pin the source commit used by your team. Since development builds currently share version `0.2.0`, reinstall both artifacts together after upgrading and do not mix builds from different commits.

In your application's test dependencies, add locally installed `dev.tooltape:tooltape-junit-jupiter:0.2.0` and a matching Jupiter API/engine version, all with test scope. The adapter's Jupiter API dependency is `provided`: your project controls its runner. Use your existing JUnit BOM rather than combining unrelated engine/platform versions. Maven must resolve ordinary test dependencies during setup; replay itself needs no network.

For a project without an existing JUnit setup, the minimal dependency section is below. Use a Jupiter-capable Surefire runner (the tested consumer pins 3.5.4); the [standalone consumer POM](../integrations/junit-consumer/pom.xml) is a complete runnable reference.

```xml
<dependencies>
  <dependency>
    <groupId>dev.tooltape</groupId>
    <artifactId>tooltape-junit-jupiter</artifactId>
    <version>0.2.0</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.14.4</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

## One workflow, one fixture

Store a reviewed, closed v2 tape in `src/test/resources/tapes/weather-retry.tape`. Use the same capture policy that created it:

```java
import dev.tooltape.CapturePolicy;
import dev.tooltape.Replay;
import dev.tooltape.junit.ReplayFixture;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherRegressionTest {
  private static final CapturePolicy POLICY =
      CapturePolicy.allow(Set.of("city"), Set.of("temperature"));
  private static final ReplayFixture FIXTURE = ReplayFixture.fromResource(
      WeatherRegressionTest.class, "/tapes/weather-retry.tape", POLICY);

  @Test
  void retriesRecordedTimeout() throws Throwable {
    FIXTURE.assertReplay(tool -> {
      Map<String, Object> result;
      try {
        result = tool.call("weather.lookup", Map.of("city", "Hangzhou"));
      } catch (Replay.RecordedToolException recordedFailure) {
        result = tool.call("weather.lookup", Map.of("city", "Hangzhou"));
      }
      assertEquals("22", result.get("temperature").toString());
    });
  }
}
```

Normally pass `tool` to your own orchestration method instead of spelling the calls out in the test. A workflow must receive its side-effect boundary explicitly. The helper has no live delegate and never invokes an external tool for you, but arbitrary code inside your callback is still ordinary Java, not a sandbox.

## Numeric assertions and domain results

Replay numbers are `BigDecimal` regardless of whether the live tool returned an `Integer`, `Long`, `Float` or another supported JSON number. Decimal scale is canonicalized too. `assertEquals(22, result.get("temperature"))` compares Java types and will fail for a replayed `BigDecimal`; the string assertion above intentionally checks canonical JSON numeric text.

For production workflows, normalize values at the domain boundary in both recording and replay. The [executable numeric example](../examples/NumericBoundaryRegression.java) converts a `Number` through `new BigDecimal(number.toString()).intValueExact()`, so fractional or out-of-range counts fail rather than truncate. Do not replace this with `Number.intValue()` when exactness matters. For decimal equality, use a domain-specific comparison/scale policy rather than relying on `BigDecimal.equals`, which also compares scale. The helper does not restore original Java wrapper classes or omitted fields.

## Named dynamic tests

```java
@TestFactory
Stream<DynamicTest> recordedScenarios() {
  return Stream.of(
      FIXTURE.test("timeout then successful retry", tool -> {
        assertEquals("22", forecast(tool, "Hangzhou").get("temperature").toString());
      }));
}
```

Import `org.junit.jupiter.api.TestFactory`, `org.junit.jupiter.api.DynamicTest` and `java.util.stream.Stream`; `forecast` is your orchestration method. Each child's execution loads and validates its own tape and creates its own replay. A missing or malformed fixture therefore fails that named child when executed. Creating a fixture or `DynamicTest` does not read the recording.

JUnit does not run `@BeforeEach`/`@AfterEach` separately for each dynamic child. Keep mutable workflow state inside each callback. The helper itself is immutable and reusable, including across independent parallel test executions; calls within one replay must remain serial. Do not let replay references escape into background work.

## Eligibility, ownership and failure behavior

- `fromPath(Path, CapturePolicy)` uses a filesystem fixture. `fromResource(Class<?>, String, CapturePolicy)` follows `Class.getResourceAsStream`: a leading `/` is classpath-root-relative; other names are relative to the anchor class's package. Resources inside JARs are supported without temporary extraction.
- Every execution requires v2, a complete end marker and the exact policy fingerprint before the callback starts. V1 remains readable through the core API and CLI; re-record reviewed v2 fixtures to use this helper. It does not upgrade fingerprints or infer policies.
- Every execution checks that all recorded calls were consumed. Changed arguments, missing/extra calls and swallowed divergence still fail. A legitimately empty closed tape succeeds only with zero calls.
- Expected recorded failures must be caught/asserted **inside** the callback, then the workflow must consume any remaining events. Do not wrap the entire helper in `assertThrows`: accepting the primary exception can hide a suppressed incomplete-replay failure.
- If the callback throws, that exact throwable stays primary. A close-time replay failure is suppressed, matching Java try-with-resources semantics. This includes assertion failures and aborted tests. A Jupiter abort remains an abort even if an incomplete-replay failure is suppressed on it; avoid assumptions inside the replay callback when every trace must count as a passing/failing regression. Inspect suppressed failures when debugging.
- The helper closes streams it opens. The additive core `Tape.read(InputStream)` never closes a caller-owned stream, even on failure, and uses the same bounded parser as `Tape.read(Path)`. The path overload continues to close its own stream.
- Neither factory caches file contents. Keep fixtures immutable during a test run if you need reproducible inputs; a later invocation intentionally observes a replaced file.

## Diagnostics and privacy

JUnit output can contain tool names, field names, allowlisted argument/result values, paths and arbitrary messages from your workflow. It is **not** the payload-free CLI JSON report contract. Review fixtures, test names, assertion text and CI log/artifact access before sharing. Default capture omits values; allowlisting a field is an explicit decision to retain it, not proof that its contents are safe.

## Verification and scope

The adapter is compiled against Jupiter 5.14.4. Compatibility is checked by consuming the same packaged adapter JAR under Jupiter 5.14.4 and 6.0.3, with isolated consumer projects on Java 17 and 21. See the [Jupiter workflow](../.github/workflows/jupiter.yml) for the exact build and consumer contract. The [verified run for commit 279f46d](https://github.com/keweizhuazuki/tooltape/actions/runs/38044661449) passed 20 adapter tests in the build job, then 7 consumer tests in each of those four combinations. Consumer jobs have no checkout and verify actual JAR origins and selected API/engine/launcher versions. These are Ubuntu-hosted results, not a claim about other Jupiter versions or every IDE runner.

Tests cover real JAR resources, named dynamic-child outcomes, fresh replay reuse, parallel isolation, policy/format rejection before callback, complete consumption, recorded retry failures and throwable preservation. Core parser tests cover stream limits, parity and ownership. There is no recording annotation, directory discovery, mutable extension registry or automatic baseline update.

JUnit lifecycle reference: [official dynamic-test guide](https://docs.junit.org/6.0.3/writing-tests/dynamic-tests.html).
