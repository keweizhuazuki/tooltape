package dev.tooltape.junit;

import dev.tooltape.CapturePolicy;
import dev.tooltape.Replay;
import dev.tooltape.Tape;
import dev.tooltape.Tool;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.ThrowingConsumer;

/**
 * An immutable, reusable configuration for scoped, offline JUnit Jupiter replay tests.
 *
 * <p>Every execution reads the fixture anew and creates a separate replay. No live tool is
 * accepted, and no fixture is recorded or updated. Only complete schema-v2 fixtures with the
 * explicitly supplied capture policy are eligible. Use the core {@link Replay} API directly for
 * legacy fixtures.
 *
 * <p>A configuration may be shared by independent parallel tests. Each individual workflow must
 * remain serial, finish all its work before the assertion callback returns, and not retain the
 * supplied tool. Keep shared fixture files read-only during a test run. This helper does not
 * sandbox application code or isolate mutable state captured by the callback.
 *
 * <p>Failures retain the core's human-readable diagnostics: captured values, tool names, keys, file
 * paths, and application failure details can appear in JUnit reports. Reports are not payload-free.
 */
public final class ReplayFixture {
  @FunctionalInterface
  private interface Source {
    Tape read() throws IOException;
  }

  private final Source source;
  private final CapturePolicy policy;

  private ReplayFixture(Source source, CapturePolicy policy) {
    this.source = source;
    this.policy = Objects.requireNonNull(policy, "Capture policy is required");
  }

  /**
   * Configure a filesystem fixture. Reading and eligibility checks occur during each execution, not
   * at configuration time. Relative paths use the process's working directory.
   */
  public static ReplayFixture fromPath(Path path, CapturePolicy policy) {
    Objects.requireNonNull(path, "Fixture path is required");
    return new ReplayFixture(() -> Tape.read(path), policy);
  }

  /**
   * Configure a classpath fixture, including a resource inside a JAR. Names follow {@link
   * Class#getResourceAsStream(String)}: a leading slash selects the classpath root; otherwise the
   * name is relative to the anchor class's package. No URL or filesystem fallback is attempted.
   * Streams opened by this fixture are closed on both successful and failed reads.
   */
  public static ReplayFixture fromResource(
      Class<?> anchor, String resourceName, CapturePolicy policy) {
    Objects.requireNonNull(anchor, "Resource anchor is required");
    Objects.requireNonNull(resourceName, "Resource name is required");
    if (resourceName.isEmpty())
      throw new IllegalArgumentException("Resource name must not be empty");
    return new ReplayFixture(
        () -> {
          try (InputStream in = anchor.getResourceAsStream(resourceName)) {
            if (in == null) throw new FileNotFoundException("Replay fixture resource not found");
            return Tape.read(in);
          }
        },
        policy);
  }

  /**
   * Run assertions against one fresh replay and require every expected call to be consumed.
   *
   * <p>The fixture is loaded and checked before the callback runs. If the callback fails, its
   * original {@code Throwable} remains primary; any completion failure is suppressed on it by
   * ordinary try-with-resources semantics. A swallowed replay divergence still fails completion.
   *
   * <p>Assert expected workflow or recorded exceptions <em>inside</em> the callback. Wrapping this
   * whole method in {@code assertThrows} can overlook a suppressed completion failure. Recorded
   * errors use {@link Replay.RecordedToolException}, not the original exception subclass.
   *
   * @throws Throwable the original callback failure, fixture loading/eligibility failure, or replay
   *     divergence; completion failures may be suppressed on a callback failure
   */
  public void assertReplay(ThrowingConsumer<? super Tool> assertion) throws Throwable {
    Objects.requireNonNull(assertion, "Replay assertion is required");
    Tape tape = source.read();
    if (tape.version() != 2)
      throw new IllegalArgumentException("JUnit replay fixtures require schema v2");
    try (Replay replay = new Replay(tape, policy)) {
      assertion.accept(replay);
    }
  }

  /**
   * Create a named dynamic test with its own complete replay scope. Loading happens when the child
   * executes, so a missing or ineligible fixture fails that child rather than its test factory.
   * Dynamic children do not receive individual {@code BeforeEach}/{@code AfterEach} callbacks.
   */
  public DynamicTest test(String displayName, ThrowingConsumer<? super Tool> assertion) {
    Objects.requireNonNull(assertion, "Replay assertion is required");
    return DynamicTest.dynamicTest(displayName, () -> assertReplay(assertion));
  }
}
