package dev.tooltape;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundaryJUnitTest {
  @TempDir Path dir;

  @Test
  void oversizedCapturedInputNeverExecutesToolAndRecorderRemainsUsable() throws Exception {
    int[] calls = {0};
    Path path = dir.resolve("input.tape");
    try (Recorder recorder =
        new Recorder(
            path,
            (n, a) -> {
              calls[0]++;
              return Map.of();
            },
            CapturePolicy.allow(Set.of("text"), Set.of()))) {
      var error =
          assertThrows(
              java.io.IOException.class,
              () -> recorder.call("tool", Map.of("text", "x".repeat(13 * 1024 * 1024))));
      assertTrue(error.getMessage().contains("tool was not executed"));
      assertEquals(0, calls[0]);
      assertTrue(Files.size(path) < 1024);
      recorder.call("tool", Map.of("text", "small"));
    }
    Tape tape = Tape.read(path);
    assertTrue(tape.complete());
    assertEquals(1, tape.events().size());
    assertEquals(1, calls[0]);
  }

  @Test
  void cumulativeBudgetRejectsNextInputBeforeSideEffect() throws Exception {
    int[] calls = {0};
    Path path = dir.resolve("cumulative.tape");
    String large = "x".repeat(7 * 1024 * 1024);
    try (Recorder recorder =
        new Recorder(
            path,
            (n, a) -> {
              calls[0]++;
              return Map.of();
            },
            CapturePolicy.allow(Set.of("text"), Set.of()))) {
      recorder.call("tool", Map.of("text", large));
      assertThrows(java.io.IOException.class, () -> recorder.call("tool", Map.of("text", large)));
    }
    assertEquals(1, calls[0]);
    assertEquals(1, Tape.read(path).events().size());
    assertTrue(Tape.read(path).complete());
  }

  @Test
  void oversizedResultPoisonsSessionAfterOneLiveCall() throws Exception {
    int[] calls = {0};
    Path path = dir.resolve("output.tape");
    try (Recorder recorder =
        new Recorder(
            path,
            (n, a) -> {
              calls[0]++;
              return Map.of("text", "x".repeat(13 * 1024 * 1024));
            },
            CapturePolicy.allow(Set.of(), Set.of("text")))) {
      assertThrows(java.io.IOException.class, () -> recorder.call("tool", Map.of()));
      assertThrows(IllegalStateException.class, () -> recorder.call("tool", Map.of()));
    }
    assertEquals(1, calls[0]);
    assertFalse(Tape.read(path).complete());
    assertTrue(Files.size(path) < 1024);
  }

  @Test
  void excludedLargeSecretDoesNotConsumeCaptureBudget() throws Exception {
    Path path = dir.resolve("excluded.tape");
    try (Recorder recorder = new Recorder(path, (n, a) -> Map.of(), CapturePolicy.safe())) {
      recorder.call("tool", Map.of("token", "x".repeat(Tape.MAX_BYTES)));
    }
    assertTrue(Tape.read(path).complete());
    assertEquals("{}", Tape.read(path).events().get(0).input());
    assertTrue(Files.size(path) < 1024);
  }

  @Test
  void newPosixRecordingsAreOwnerOnlyWithoutChangingExistingFiles() throws Exception {
    Path path = dir.resolve("private.tape");
    try (Recorder ignored = new Recorder(path, (n, a) -> Map.of(), CapturePolicy.safe())) {}
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
      assertEquals(
          PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
    assertThrows(
        FileAlreadyExistsException.class,
        () -> new Recorder(path, (n, a) -> Map.of(), CapturePolicy.safe()));
  }

  @Test
  void malformedUnicodeEscapesCannotHideCorruptedFixtureData() {
    for (String hex : List.of("+001", "-001", "１２３４", "000g"))
      assertThrows(IllegalArgumentException.class, () -> Json.read("\"\\u" + hex + "\""));
    assertEquals("雪", Json.read("\"\\u96ea\""));
  }

  @Test
  void numberWriterAndReaderBothRejectUnboundedScaleBeforeToolCall() throws Exception {
    for (String number : List.of("1e10001", "1e-10001", "1e2147483647")) {
      assertThrows(IllegalArgumentException.class, () -> Json.read(number));
      assertThrows(IllegalArgumentException.class, () -> Json.write(new BigDecimal(number)));
    }
    int[] calls = {0};
    try (Recorder recorder =
        new Recorder(
            dir.resolve("number.tape"),
            (n, a) -> {
              calls[0]++;
              return Map.of();
            },
            CapturePolicy.allow(Set.of("n"), Set.of()))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> recorder.call("tool", Map.of("n", new BigDecimal("1e2147483647"))));
    }
    assertEquals(0, calls[0]);
  }

  @Test
  void normalizedCredentialNamesAreExcludedFromBothDirections() {
    for (String key :
        List.of(
            "password\n",
            "prefix\rtoken",
            "api\nkey",
            "private\u2028key",
            "api\u200b_key",
            "cookie\r\n",
            "pass-word")) {
      CapturePolicy policy = CapturePolicy.allow(Set.of(key), Set.of(key));
      assertTrue(policy.input(Map.of(key, "secret")).isEmpty(), key);
      assertTrue(policy.output(Map.of(key, "secret")).isEmpty(), key);
    }
    assertEquals(
        Map.of("city", "Hangzhou"),
        CapturePolicy.allow(Set.of("city"), Set.of()).input(Map.of("city", "Hangzhou")));
  }

  @Test
  void numericAmplificationAndAggregateTapeExpansionAreBounded() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> Json.write(Json.read("1e10000")));
    assertThrows(IllegalArgumentException.class, () -> Json.read("1".repeat(10001)));
    String expanded = "{\"n\":[" + String.join(",", Collections.nCopies(2000, "1e9999")) + "]}";
    assertThrows(IllegalArgumentException.class, () -> Json.write(Json.read(expanded)));
    String moderate = "{\"n\":[" + String.join(",", Collections.nCopies(1000, "1e9999")) + "]}";
    String event =
        "0\t0\t"
            + Tape.encode("tool")
            + "\t"
            + Tape.encode("{}")
            + "\tOK\t"
            + Tape.encode(moderate)
            + "\n";
    Path path = dir.resolve("amplification.tape");
    Files.writeString(path, Tape.HEADER + "\n" + event + event.replaceFirst("0\t", "1\t"));
    assertTrue(Files.size(path) < 100000);
    assertThrows(java.io.IOException.class, () -> Tape.read(path));
  }

  @Test
  void parserAndWriterBoundValueCountAndUtf8Bytes() {
    assertThrows(IllegalArgumentException.class, () -> Json.read("[" + "0,".repeat(100000) + "0]"));
    assertThrows(IllegalArgumentException.class, () -> Json.write(Collections.nCopies(100001, 0)));
    assertThrows(
        IllegalArgumentException.class, () -> Json.write("雪".repeat(Json.MAX_BYTES / 3 + 1)));
    List<Object> cycle = new ArrayList<>();
    cycle.add(cycle);
    assertThrows(IllegalArgumentException.class, () -> Json.write(cycle));
  }

  static final class 故障 extends Exception {
    private static final long serialVersionUID = 1L;
  }

  @Test
  void unicodeExceptionClassRoundTripsWithoutInstantiatingItDuringReplay() throws Exception {
    Path path = dir.resolve("unicode-error.tape");
    try (Recorder recorder =
        new Recorder(
            path,
            (n, a) -> {
              throw new 故障();
            },
            CapturePolicy.safe())) {
      assertThrows(故障.class, () -> recorder.call("tool", Map.of()));
    }
    Tape tape = Tape.read(path);
    assertTrue(tape.complete());
    try (Replay replay = new Replay(tape, CapturePolicy.safe())) {
      assertEquals(
          故障.class.getName(),
          assertThrows(Replay.RecordedToolException.class, () -> replay.call("tool", Map.of()))
              .originalType());
    }
  }
}
