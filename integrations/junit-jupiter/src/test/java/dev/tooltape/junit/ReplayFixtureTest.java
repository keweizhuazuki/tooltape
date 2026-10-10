package dev.tooltape.junit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import dev.tooltape.CapturePolicy;
import dev.tooltape.Recorder;
import dev.tooltape.Replay;
import dev.tooltape.Tool;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.opentest4j.TestAbortedException;

class ReplayFixtureTest {
  private static final CapturePolicy POLICY =
      CapturePolicy.allow(Set.of("city"), Set.of("temperature"));
  private static final String RESOURCE = "/dev/tooltape/junit/weather-retry-v2.tape";
  @TempDir Path dir;

  private Path record(int count) throws Exception {
    Path path = dir.resolve(UUID.randomUUID() + ".tape");
    try (Recorder recorder =
        new Recorder(path, (name, arguments) -> Map.of("temperature", 22), POLICY)) {
      for (int i = 0; i < count; i++) recorder.call("weather.lookup", Map.of("city", "Hangzhou"));
    }
    return path;
  }

  private static void call(Tool tool) throws Exception {
    assertEquals(
        "22",
        tool.call("weather.lookup", Map.of("city", "Hangzhou")).get("temperature").toString());
  }

  private static void retry(Tool tool) throws Exception {
    Replay.RecordedToolException failure =
        assertThrows(
            Replay.RecordedToolException.class,
            () -> tool.call("weather.lookup", Map.of("city", "Hangzhou")));
    assertEquals("java.io.IOException", failure.originalType());
    call(tool);
  }

  private static ReplayFixture resource() {
    return ReplayFixture.fromResource(ReplayFixtureTest.class, RESOURCE, POLICY);
  }

  @Test
  void completeFilesystemReplayAndEmptySessionPass() throws Throwable {
    ReplayFixture.fromPath(record(1), POLICY).assertReplay(ReplayFixtureTest::call);
    ReplayFixture.fromPath(record(0), POLICY).assertReplay(tool -> {});
  }

  @Test
  void absoluteAndPackageRelativeClasspathFixturesReplayRecordedRetry() throws Throwable {
    resource().assertReplay(ReplayFixtureTest::retry);
    ReplayFixture.fromResource(ReplayFixtureTest.class, "weather-retry-v2.tape", POLICY)
        .assertReplay(ReplayFixtureTest::retry);
  }

  @Test
  void fixtureLoadingIsDeferredUntilEachExecution() throws Throwable {
    Path path = dir.resolve("later.tape");
    ReplayFixture fixture = ReplayFixture.fromPath(path, POLICY);
    DynamicTest child = fixture.test("created before fixture exists", ReplayFixtureTest::call);
    assertThrows(IOException.class, child.getExecutable()::execute);
    Files.copy(record(1), path);
    child.getExecutable().execute();
    Files.delete(path);
    assertThrows(IOException.class, child.getExecutable()::execute);
  }

  @Test
  void missingAndMalformedInputsNeverInvokeCallback() throws Exception {
    AtomicBoolean invoked = new AtomicBoolean();
    ReplayFixture missing =
        ReplayFixture.fromResource(ReplayFixtureTest.class, "missing.tape", POLICY);
    assertThrows(IOException.class, () -> missing.assertReplay(tool -> invoked.set(true)));
    Path bad = dir.resolve("invalid.tape");
    Files.writeString(bad, "not a tape\n");
    assertThrows(
        IOException.class,
        () -> ReplayFixture.fromPath(bad, POLICY).assertReplay(tool -> invoked.set(true)));
    assertFalse(invoked.get());
  }

  @Test
  void legacyIncompleteAndWrongPolicyFailBeforeCallback() throws Exception {
    Path valid = record(1);
    String content = Files.readString(valid);
    Path legacy = dir.resolve("legacy.tape");
    String[] lines = content.split("\n");
    Files.writeString(legacy, "TOOLTAPE\t1\n" + lines[2] + "\n");
    Path incomplete = dir.resolve("incomplete.tape");
    Files.writeString(incomplete, content.replace("END\t1\n", ""));
    AtomicBoolean invoked = new AtomicBoolean();
    for (ReplayFixture fixture :
        List.of(
            ReplayFixture.fromPath(legacy, POLICY),
            ReplayFixture.fromPath(incomplete, POLICY),
            ReplayFixture.fromPath(valid, CapturePolicy.safe()))) {
      assertThrows(
          IllegalArgumentException.class, () -> fixture.assertReplay(tool -> invoked.set(true)));
    }
    assertFalse(invoked.get());
  }

  @Test
  void missingExtraChangedToolAndChangedArgumentsFail() throws Exception {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    assertThrows(Replay.Divergence.class, () -> fixture.assertReplay(tool -> {}));
    assertThrows(
        Replay.Divergence.class,
        () ->
            fixture.assertReplay(
                tool -> {
                  call(tool);
                  call(tool);
                }));
    assertThrows(
        Replay.Divergence.class,
        () -> fixture.assertReplay(tool -> tool.call("wrong.tool", Map.of("city", "Hangzhou"))));
    Replay.Divergence changed =
        assertThrows(
            Replay.Divergence.class,
            () ->
                fixture.assertReplay(
                    tool -> tool.call("weather.lookup", Map.of("city", "Shanghai"))));
    assertTrue(changed.getMessage().contains("event 0"));
    // The Java assertion API deliberately retains the core's human-readable captured values.
    assertTrue(changed.getMessage().contains("Hangzhou"));
    assertTrue(changed.getMessage().contains("Shanghai"));
  }

  @Test
  void swallowedDivergenceStillFailsCompletion() throws Exception {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    Replay.Divergence completion =
        assertThrows(
            Replay.Divergence.class,
            () ->
                fixture.assertReplay(
                    tool -> {
                      assertThrows(
                          Replay.Divergence.class,
                          () -> tool.call("wrong.tool", Map.of("city", "Hangzhou")));
                    }));
    assertTrue(completion.getMessage().contains("already diverged"));
  }

  @Test
  void freshStateAfterSuccessAndFailure() throws Throwable {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    fixture.assertReplay(ReplayFixtureTest::call);
    fixture.assertReplay(ReplayFixtureTest::call);
    assertThrows(Replay.Divergence.class, () -> fixture.assertReplay(tool -> {}));
    fixture.assertReplay(ReplayFixtureTest::call);
    DynamicTest child = fixture.test("reusable execution", ReplayFixtureTest::call);
    child.getExecutable().execute();
    child.getExecutable().execute();
  }

  @Test
  void originalCheckedFailureIsPreservedWithCompletionSuppressed() throws Exception {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    IOException primary = new IOException("original workflow failure");
    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                fixture.assertReplay(
                    tool -> {
                      throw primary;
                    }));
    assertSame(primary, actual);
    assertEquals(1, actual.getSuppressed().length);
    assertInstanceOf(Replay.Divergence.class, actual.getSuppressed()[0]);
  }

  @Test
  void originalAssertionAndArbitraryThrowableArePreserved() throws Exception {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    for (Throwable primary :
        List.of(new AssertionError("original assertion"), new Throwable("direct"))) {
      Throwable actual =
          assertThrows(
              Throwable.class,
              () ->
                  fixture.assertReplay(
                      tool -> {
                        throw primary;
                      }));
      assertSame(primary, actual);
      assertEquals(1, actual.getSuppressed().length);
      assertInstanceOf(Replay.Divergence.class, actual.getSuppressed()[0]);
    }
  }

  @Test
  void originalAbortedTestRemainsPrimaryWithCompletionSuppressed() throws Exception {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    TestAbortedException primary = new TestAbortedException("environment unavailable");
    TestAbortedException actual =
        assertThrows(
            TestAbortedException.class,
            () ->
                fixture.assertReplay(
                    tool -> {
                      throw primary;
                    }));
    assertSame(primary, actual);
    assertEquals(1, actual.getSuppressed().length);
    assertInstanceOf(Replay.Divergence.class, actual.getSuppressed()[0]);
  }

  @Test
  void completedTracePreservesFailureWithoutExtraSuppressedExceptions() throws Exception {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    AssertionError primary = new AssertionError("result assertion");
    AssertionError actual =
        assertThrows(
            AssertionError.class,
            () ->
                fixture.assertReplay(
                    tool -> {
                      call(tool);
                      throw primary;
                    }));
    assertSame(primary, actual);
    assertEquals(0, actual.getSuppressed().length);
  }

  @Test
  void primaryDivergenceRemainsPrimaryWithCompletionSuppressed() throws Exception {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    List<Throwable> observed = new ArrayList<>();
    Replay.Divergence actual =
        assertThrows(
            Replay.Divergence.class,
            () ->
                fixture.assertReplay(
                    tool -> {
                      try {
                        tool.call("wrong.tool", Map.of());
                      } catch (Replay.Divergence failure) {
                        observed.add(failure);
                        throw failure;
                      }
                    }));
    assertSame(observed.get(0), actual);
    assertEquals(1, actual.getSuppressed().length);
    assertInstanceOf(Replay.Divergence.class, actual.getSuppressed()[0]);
  }

  @Test
  void unhandledRecordedFailureIsPreservedWithRemainingRetrySuppressed() {
    Replay.RecordedToolException actual =
        assertThrows(
            Replay.RecordedToolException.class,
            () ->
                resource()
                    .assertReplay(tool -> tool.call("weather.lookup", Map.of("city", "Hangzhou"))));
    assertEquals("java.io.IOException", actual.originalType());
    assertEquals(1, actual.getSuppressed().length);
    assertInstanceOf(Replay.Divergence.class, actual.getSuppressed()[0]);
  }

  @Test
  void expectedFinalRecordedFailureCanBeAssertedInsideScope() throws Throwable {
    Path path = dir.resolve("error.tape");
    try (Recorder recorder =
        new Recorder(
            path,
            (name, arguments) -> {
              throw new IOException("not persisted");
            },
            POLICY)) {
      assertThrows(
          IOException.class, () -> recorder.call("weather.lookup", Map.of("city", "Hangzhou")));
    }
    ReplayFixture fixture = ReplayFixture.fromPath(path, POLICY);
    fixture.assertReplay(
        tool ->
            assertEquals(
                "java.io.IOException",
                assertThrows(
                        Replay.RecordedToolException.class,
                        () -> tool.call("weather.lookup", Map.of("city", "Hangzhou")))
                    .originalType()));
    Replay.RecordedToolException unhandled =
        assertThrows(
            Replay.RecordedToolException.class,
            () ->
                fixture.assertReplay(
                    tool -> tool.call("weather.lookup", Map.of("city", "Hangzhou"))));
    assertEquals(0, unhandled.getSuppressed().length);
  }

  @Test
  void configurationAndCallbacksRequireExplicitNonNullInputs() throws Exception {
    assertThrows(NullPointerException.class, () -> ReplayFixture.fromPath(null, POLICY));
    assertThrows(NullPointerException.class, () -> ReplayFixture.fromPath(record(0), null));
    assertThrows(
        NullPointerException.class, () -> ReplayFixture.fromResource(null, RESOURCE, POLICY));
    assertThrows(
        NullPointerException.class, () -> ReplayFixture.fromResource(getClass(), null, POLICY));
    assertThrows(
        IllegalArgumentException.class, () -> ReplayFixture.fromResource(getClass(), "", POLICY));
    assertThrows(
        NullPointerException.class, () -> ReplayFixture.fromResource(getClass(), RESOURCE, null));
    ReplayFixture fixture = resource();
    assertThrows(NullPointerException.class, () -> fixture.assertReplay(null));
    assertThrows(NullPointerException.class, () -> fixture.test("name", null));
    assertThrows(RuntimeException.class, () -> fixture.test(" ", ReplayFixtureTest::retry));
  }

  @Test
  void sharedConfigurationSupportsIndependentParallelExecutions() throws Exception {
    ReplayFixture fixture = ReplayFixture.fromPath(record(1), POLICY);
    Set<Tool> instances = ConcurrentHashMap.newKeySet();
    CyclicBarrier together = new CyclicBarrier(4);
    var executor = Executors.newFixedThreadPool(4);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 16; i++)
        futures.add(
            executor.submit(
                () -> {
                  try {
                    fixture.assertReplay(
                        tool -> {
                          instances.add(tool);
                          together.await(10, TimeUnit.SECONDS);
                          call(tool);
                        });
                  } catch (Throwable failure) {
                    throw new AssertionError(failure);
                  }
                }));
      for (var future : futures) future.get(20, TimeUnit.SECONDS);
      assertEquals(16, instances.size());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private static byte[] anchorBytes() throws IOException {
    try (InputStream in = JarAnchor.class.getResourceAsStream("JarAnchor.class")) {
      return java.util.Objects.requireNonNull(in).readAllBytes();
    }
  }

  private static byte[] fixtureBytes() throws IOException {
    try (InputStream in = ReplayFixtureTest.class.getResourceAsStream(RESOURCE)) {
      return java.util.Objects.requireNonNull(in).readAllBytes();
    }
  }

  @Test
  void resourceLoadsFromActualJarWithoutFilesystemConversion() throws Throwable {
    Path jar = dir.resolve("fixtures with spaces.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("dev/tooltape/junit/JarAnchor.class"));
      out.write(anchorBytes());
      out.closeEntry();
      out.putNextEntry(new JarEntry("dev/tooltape/junit/weather-retry-v2.tape"));
      out.write(fixtureBytes());
      out.closeEntry();
    }
    try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      Class<?> anchor = Class.forName("dev.tooltape.junit.JarAnchor", true, loader);
      assertEquals("jar", anchor.getResource("weather-retry-v2.tape").getProtocol());
      ReplayFixture.fromResource(anchor, "weather-retry-v2.tape", POLICY)
          .assertReplay(ReplayFixtureTest::retry);
      ReplayFixture.fromResource(anchor, RESOURCE, POLICY).assertReplay(ReplayFixtureTest::retry);
    }
  }

  private static final class TrackingLoader extends ClassLoader {
    private final byte[] content;
    private final boolean failRead;
    private boolean closed;

    TrackingLoader(byte[] content, boolean failRead) {
      super(null);
      this.content = content;
      this.failRead = failRead;
    }

    Class<?> anchor() throws IOException {
      byte[] bytes = anchorBytes();
      return defineClass("dev.tooltape.junit.JarAnchor", bytes, 0, bytes.length);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      return new FilterInputStream(new ByteArrayInputStream(content)) {
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
          if (failRead) throw new IOException("synthetic resource read failure");
          return super.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
          closed = true;
          super.close();
        }
      };
    }
  }

  @Test
  void resourceStreamsCloseBeforeCallbackAndOnParsingOrReadFailure() throws Throwable {
    TrackingLoader good = new TrackingLoader(fixtureBytes(), false);
    ReplayFixture.fromResource(good.anchor(), "fixture.tape", POLICY)
        .assertReplay(
            tool -> {
              assertTrue(good.closed);
              retry(tool);
            });
    for (TrackingLoader bad :
        List.of(
            new TrackingLoader(new byte[] {1, 2, 3}, false),
            new TrackingLoader(fixtureBytes(), true))) {
      ReplayFixture fixture = ReplayFixture.fromResource(bad.anchor(), "fixture.tape", POLICY);
      assertThrows(IOException.class, () -> fixture.assertReplay(tool -> fail("must not execute")));
      assertTrue(bad.closed);
    }
  }

  /** Selected explicitly by the launcher below; not independently discovered by Surefire. */
  static class DynamicCases {
    private static final ReplayFixture SHARED = resource();

    @TestFactory
    Stream<DynamicTest> fixtures() {
      return Stream.of(
          SHARED.test("valid retry one", ReplayFixtureTest::retry),
          ReplayFixture.fromResource(ReplayFixtureTest.class, "missing.tape", POLICY)
              .test("missing fixture child", tool -> fail("must not execute")),
          SHARED.test("incomplete trace child", tool -> {}),
          SHARED.test(
              "aborted trace child",
              tool -> {
                throw new TestAbortedException("synthetic abort");
              }),
          SHARED.test("valid retry two", ReplayFixtureTest::retry));
    }
  }

  @Test
  void actualJupiterEngineReportsNamedChildrenAndRunsIndependentSiblings() {
    var summary = new SummaryGeneratingListener();
    Map<String, TestExecutionResult.Status> results = new ConcurrentHashMap<>();
    Map<String, Throwable> failures = new ConcurrentHashMap<>();
    var listener =
        new TestExecutionListener() {
          @Override
          public void executionFinished(TestIdentifier test, TestExecutionResult result) {
            if (test.isTest()) {
              results.put(test.getDisplayName(), result.getStatus());
              result
                  .getThrowable()
                  .ifPresent(failure -> failures.put(test.getDisplayName(), failure));
            }
          }
        };
    var request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClass(DynamicCases.class))
            .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
            .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
            .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
            .configurationParameter(
                "junit.jupiter.execution.parallel.config.fixed.parallelism", "4")
            .build();
    LauncherFactory.create().execute(request, summary, listener);
    assertEquals(5, summary.getSummary().getTestsFoundCount());
    assertEquals(1, summary.getSummary().getTestsAbortedCount());
    assertEquals(2, summary.getSummary().getTestsSucceededCount());
    assertEquals(2, summary.getSummary().getTestsFailedCount());
    assertEquals(0, summary.getSummary().getContainersFailedCount());
    Throwable aborted = failures.get("aborted trace child");
    assertInstanceOf(TestAbortedException.class, aborted);
    assertEquals(1, aborted.getSuppressed().length);
    assertInstanceOf(Replay.Divergence.class, aborted.getSuppressed()[0]);
    assertEquals(
        Map.of(
            "valid retry one", TestExecutionResult.Status.SUCCESSFUL,
            "missing fixture child", TestExecutionResult.Status.FAILED,
            "incomplete trace child", TestExecutionResult.Status.FAILED,
            "aborted trace child", TestExecutionResult.Status.ABORTED,
            "valid retry two", TestExecutionResult.Status.SUCCESSFUL),
        results);
  }
}
