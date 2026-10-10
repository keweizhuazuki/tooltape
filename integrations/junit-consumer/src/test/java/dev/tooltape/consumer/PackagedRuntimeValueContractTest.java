package dev.tooltape.consumer;

import static org.junit.jupiter.api.Assertions.*;

import dev.tooltape.CapturePolicy;
import dev.tooltape.Recorder;
import dev.tooltape.Replay;
import dev.tooltape.Tool;
import dev.tooltape.junit.ReplayFixture;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic live tools and replay execute the same application-owned domain boundary. */
class PackagedRuntimeValueContractTest {
  private static final CapturePolicy POLICY =
      CapturePolicy.allow(Set.of("quantity"), Set.of("quantity"));
  @TempDir Path temporary;

  @Test
  void runtimeContractUsesPackagedDependencies() throws Exception {
    for (Class<?> type : List.of(Recorder.class, Replay.class, ReplayFixture.class)) {
      Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
      assertTrue(
          Files.isRegularFile(source),
          () -> type.getName() + " must come from a packaged JAR: " + source);
      assertEquals(
          type == ReplayFixture.class ? "tooltape-junit-jupiter-0.2.0.jar" : "tooltape-0.2.0.jar",
          source.getFileName().toString());
    }
  }

  @Test
  void sameDomainWorkflowNormalizesLiveIntegersAndReplayedBigDecimals() throws Throwable {
    Path path = temporary.resolve("inventory.tape");
    AtomicInteger liveCalls = new AtomicInteger();
    Tool synthetic =
        (name, arguments) -> {
          liveCalls.incrementAndGet();
          assertInstanceOf(Integer.class, arguments.get("quantity"));
          if (name.equals("inventory.lookup")) {
            assertEquals(2, arguments.get("quantity"));
            return Map.of("quantity", Integer.valueOf(3));
          }
          assertEquals("inventory.reserve", name);
          assertEquals(2, arguments.get("quantity"));
          return Map.of("quantity", Integer.valueOf(2));
        };
    try (Recorder recorder = new Recorder(path, synthetic, POLICY)) {
      assertEquals(2, reserveAvailable(recorder));
    }
    assertEquals(2, liveCalls.get());
    ReplayFixture fixture = ReplayFixture.fromPath(path, POLICY);
    fixture.assertReplay(tool -> assertEquals(2, reserveAvailable(tool)));
    fixture.assertReplay(
        tool -> {
          assertInstanceOf(
              BigDecimal.class,
              tool.call("inventory.lookup", Map.of("quantity", 2)).get("quantity"));
          assertInstanceOf(
              BigDecimal.class,
              tool.call("inventory.reserve", Map.of("quantity", 2)).get("quantity"));
        });
    assertEquals(2, liveCalls.get(), "Replay must not execute the synthetic live delegate");
  }

  @Test
  void exactIntegerBoundaryAcceptsWholeNumbersIncludingIntegerLimitsInBothModes() throws Throwable {
    List<Number> values =
        List.of(
            0,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE,
            Long.valueOf(Integer.MAX_VALUE),
            new BigDecimal("2147483647.000"),
            new BigDecimal("-2147483648.00"));
    for (int i = 0; i < values.size(); i++) {
      Number value = values.get(i);
      int expected = new BigDecimal(value.toString()).intValueExact();
      Path path = temporary.resolve("whole-" + i + ".tape");
      try (Recorder recorder =
          new Recorder(path, (name, arguments) -> Map.of("quantity", value), POLICY)) {
        assertEquals(expected, readQuantity(recorder));
      }
      ReplayFixture.fromPath(path, POLICY)
          .assertReplay(tool -> assertEquals(expected, readQuantity(tool)));
    }
  }

  @Test
  void exactIntegerBoundaryRejectsFractionalAndOverflowValuesInBothModes() throws Throwable {
    List<Number> values =
        List.of(
            1.5f,
            2.25d,
            new BigDecimal("3.0001"),
            Long.valueOf(2_147_483_648L),
            Long.valueOf(-2_147_483_649L),
            new BigDecimal("1E+30"));
    for (int i = 0; i < values.size(); i++) {
      Number value = values.get(i);
      Path path = temporary.resolve("invalid-number-" + i + ".tape");
      try (Recorder recorder =
          new Recorder(path, (name, arguments) -> Map.of("quantity", value), POLICY)) {
        assertThrows(ArithmeticException.class, () -> readQuantity(recorder), value.toString());
      }
      ReplayFixture.fromPath(path, POLICY)
          .assertReplay(
              tool ->
                  assertThrows(
                      ArithmeticException.class, () -> readQuantity(tool), value.toString()));
    }
  }

  @Test
  void exactIntegerBoundaryRejectsStringsBooleansAndNullInBothModes() throws Throwable {
    Object[] values = {"3", true, null};
    for (int i = 0; i < values.length; i++) {
      Object value = values[i];
      Path path = temporary.resolve("not-number-" + i + ".tape");
      try (Recorder recorder =
          new Recorder(
              path, (name, arguments) -> Collections.singletonMap("quantity", value), POLICY)) {
        assertThrows(IllegalArgumentException.class, () -> readQuantity(recorder));
      }
      ReplayFixture.fromPath(path, POLICY)
          .assertReplay(
              tool -> assertThrows(IllegalArgumentException.class, () -> readQuantity(tool)));
    }
  }

  private static int reserveAvailable(Tool tool) throws Exception {
    int available = readQuantity(tool);
    int requested = Math.min(available, 2);
    return exactInt(tool.call("inventory.reserve", Map.of("quantity", requested)).get("quantity"));
  }

  private static int readQuantity(Tool tool) throws Exception {
    return exactInt(tool.call("inventory.lookup", Map.of("quantity", 2)).get("quantity"));
  }

  // Application code owns its numeric domain conversion; ToolTape preserves JSON values.
  private static int exactInt(Object value) {
    if (!(value instanceof Number number))
      throw new IllegalArgumentException("Expected a JSON number");
    return new BigDecimal(number.toString()).intValueExact();
  }
}
