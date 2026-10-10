package dev.tooltape;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionJUnitTest {
  @TempDir Path dir;
  final CapturePolicy policy = CapturePolicy.allow(Set.of("city"), Set.of("temperature"));

  Path record() throws Exception {
    Path path = dir.resolve(UUID.randomUUID() + ".tape");
    try (Recorder recorder =
        new Recorder(path, (n, a) -> Map.of("temperature", 22), policy, "weather-test")) {
      recorder.call("weather.lookup", Map.of("city", "Hangzhou"));
    }
    return path;
  }

  @Test
  void v2RoundtripPreservesProvenanceAndPolicy() throws Exception {
    Tape tape = Tape.read(record());
    assertEquals(2, tape.version());
    assertTrue(tape.complete());
    assertEquals("weather-test", tape.metadata().source());
    assertEquals(policy.identifier(), tape.metadata().policyId());
    try (Replay replay = new Replay(tape, policy)) {
      assertEquals(
          "22",
          replay.call("weather.lookup", Map.of("city", "Hangzhou")).get("temperature").toString());
    }
  }

  @Test
  void changingEitherAllowlistIsRejectedBeforeReplay() throws Exception {
    Tape tape = Tape.read(record());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Replay(tape, CapturePolicy.allow(Set.of("city", "extra"), Set.of("temperature"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Replay(tape, CapturePolicy.allow(Set.of("city"), Set.of())));
    assertEquals(
        CapturePolicy.allow(Set.of("b", "a"), Set.of("d", "c")).identifier(),
        CapturePolicy.allow(new LinkedHashSet<>(List.of("a", "b")), Set.of("c", "d")).identifier());
  }

  @Test
  void interruptedSessionInspectableButNotReplayable() throws Exception {
    Path path = record();
    Files.writeString(path, Files.readString(path).replace("END\t1\n", ""));
    Tape tape = Tape.read(path);
    assertFalse(tape.complete());
    assertEquals(1, tape.events().size());
    assertThrows(IllegalArgumentException.class, () -> new Replay(tape, policy));
    assertEquals(0, Main.run(new String[] {"inspect", path.toString()}));
    assertEquals(2, Main.run(new String[] {"validate", path.toString()}));
  }

  @Test
  void invalidFooterAndMetadataRejected() throws Exception {
    String valid = Files.readString(record());
    Path bad = dir.resolve("invalid.tape");
    for (String content :
        List.of(
            valid.replace("END\t1", "END\t2"),
            valid + "END\t1\n",
            valid.replace("END\t1", "END\t01"),
            valid.replace("END\t1", "END\t-1"),
            "TOOLTAPE\t2\nEND\t0\n",
            "TOOLTAPE\t3\n")) {
      Files.writeString(bad, content);
      assertThrows(java.io.IOException.class, () -> Tape.read(bad));
    }
    for (Map<String, Object> metadata :
        List.of(
            Map.<String, Object>of("policyId", policy.identifier()),
            Map.<String, Object>of(
                "policyId", policy.identifier(), "source", "test", "extra", true),
            Map.<String, Object>of("policyId", "bad", "source", "test"),
            Map.<String, Object>of(
                "policyId", policy.identifier(), "source", "a secret in source"))) {
      Files.writeString(
          bad, "TOOLTAPE\t2\nMETA\t" + Tape.encode(Json.write(metadata)) + "\nEND\t0\n");
      assertThrows(java.io.IOException.class, () -> Tape.read(bad));
    }
  }

  @Test
  void emptyClosedSessionIsValidAndCloseIdempotent() throws Exception {
    Path path = dir.resolve("empty.tape");
    Recorder recorder = new Recorder(path, (n, a) -> Map.of(), policy);
    recorder.close();
    recorder.close();
    Tape tape = Tape.read(path);
    assertTrue(tape.complete());
    assertEquals(0, tape.events().size());
    try (Replay replay = new Replay(tape, policy)) {
      replay.assertComplete();
    }
    assertEquals(1, Files.readString(path).split("END", -1).length - 1);
  }

  @Test
  void failedPersistenceDoesNotSealSession() throws Exception {
    Path path = dir.resolve("failed.tape");
    try (Recorder recorder = new Recorder(path, (n, a) -> null, policy)) {
      assertThrows(NullPointerException.class, () -> recorder.call("tool", Map.of()));
    }
    assertFalse(Tape.read(path).complete());
    assertThrows(IllegalArgumentException.class, () -> new Replay(Tape.read(path), policy));
  }

  @Test
  void fatalToolErrorLeavesSessionIncomplete() throws Exception {
    Path path = dir.resolve("fatal.tape");
    try (Recorder recorder =
        new Recorder(
            path,
            (n, a) -> {
              throw new AssertionError("private fatal message");
            },
            policy)) {
      assertThrows(AssertionError.class, () -> recorder.call("tool", Map.of()));
      assertThrows(IllegalStateException.class, () -> recorder.call("tool", Map.of()));
    }
    Tape tape = Tape.read(path);
    assertFalse(tape.complete());
    assertTrue(tape.events().isEmpty());
    assertFalse(Files.readString(path).contains("private fatal message"));
    assertThrows(IllegalArgumentException.class, () -> new Replay(tape, policy));
  }

  @Test
  void legacyFixtureRemainsReadableAndReplayable() throws Exception {
    Tape tape = Tape.read(Path.of("fixtures/weather-retry.tape"));
    assertEquals(1, tape.version());
    assertNull(tape.metadata());
    assertFalse(tape.complete());
    try (Replay replay = new Replay(tape, policy)) {
      assertThrows(
          Replay.RecordedToolException.class,
          () -> replay.call("weather.lookup", Map.of("city", "Hangzhou")));
      replay.call("weather.lookup", Map.of("city", "Hangzhou"));
    }
  }

  @Test
  void jsonPointerDistinguishesNestedMissingAndEscapedKeys() {
    assertEquals(
        "/items/1/a~1b~0",
        Json.firstDifference(
                Json.read("{\"items\":[0,{\"a/b~\":1}]}"),
                Json.read("{\"items\":[0,{\"a/b~\":2}]}"))
            .orElseThrow());
    assertEquals(
        "/a", Json.firstDifference(Json.read("{}"), Json.read("{\"a\":null}")).orElseThrow());
    assertEquals("/1", Json.firstDifference(Json.read("[1]"), Json.read("[1,2]")).orElseThrow());
    assertTrue(Json.firstDifference(Json.read("{\"n\":1.0}"), Json.read("{\"n\":1}")).isEmpty());
  }

  @Test
  void divergenceIncludesPointerAndDiffIgnoresSessionMetadata() throws Exception {
    Tape a = Tape.read(record());
    Replay replay = new Replay(a, policy);
    assertTrue(
        assertThrows(
                Replay.Divergence.class,
                () -> replay.call("weather.lookup", Map.of("city", "Shanghai")))
            .getMessage()
            .contains("at /city"));
    Tape changed =
        new Tape(
            List.of(
                new Tape.Event(
                    0,
                    0,
                    "weather.lookup",
                    "{\"city\":\"Shanghai\"}",
                    "OK",
                    "{\"temperature\":22}")));
    assertTrue(a.diff(changed).orElseThrow().contains("input at /city"));
    assertTrue(a.diff(new Tape(a.events())).isEmpty());
  }
}
