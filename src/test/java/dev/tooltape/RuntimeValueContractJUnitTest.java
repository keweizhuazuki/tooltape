package dev.tooltape;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeValueContractJUnitTest {
  @TempDir Path temporary;

  @Test
  void recorderReturnsOriginalValuesWhileReplayReturnsCanonicalJsonNumbers() throws Exception {
    Map<String, Object> arguments = Map.of("requested", 2);
    Map<String, Object> live = new LinkedHashMap<>();
    live.put("count", Integer.valueOf(3));
    live.put("large", Long.valueOf(9_007_199_254_740_993L));
    live.put("rate", Float.valueOf(0.1f));
    live.put("distance", Double.valueOf(1.25));
    live.put("amount", new BigDecimal("12.3400"));
    live.put("zero", new BigDecimal("0.000"));
    live.put("label", "synthetic-stock");
    live.put("available", true);
    live.put("optional", null);
    live.put("notCaptured", new Object());
    Set<String> captured =
        Set.of(
            "count",
            "large",
            "rate",
            "distance",
            "amount",
            "zero",
            "label",
            "available",
            "optional");
    CapturePolicy policy = CapturePolicy.allow(Set.of("requested"), captured);
    Path path = temporary.resolve("numbers.tape");
    try (Recorder recorder =
        new Recorder(
            path,
            (name, actualArguments) -> {
              assertSame(arguments, actualArguments);
              return live;
            },
            policy)) {
      Map<String, Object> recorded = recorder.call("inventory.lookup", arguments);
      assertSame(live, recorded, "Recording must not replace the live tool's map");
      live.forEach((field, value) -> assertSame(value, recorded.get(field), field));
      assertInstanceOf(Integer.class, recorded.get("count"));
      assertInstanceOf(Long.class, recorded.get("large"));
      assertInstanceOf(Float.class, recorded.get("rate"));
      assertInstanceOf(Double.class, recorded.get("distance"));
      assertEquals(4, ((BigDecimal) recorded.get("amount")).scale());
    }

    Tape tape = Tape.read(path);
    assertEquals(
        "{\"amount\":12.34,\"available\":true,\"count\":3,\"distance\":1.25,"
            + "\"label\":\"synthetic-stock\",\"large\":9007199254740993,\"optional\":null,\"rate\":0.1,\"zero\":0}",
        tape.events().get(0).result());
    try (Replay replay = new Replay(tape, policy)) {
      Map<String, Object> restored = replay.call("inventory.lookup", arguments);
      assertNotSame(live, restored);
      assertEquals(captured, restored.keySet());
      for (String field : List.of("count", "large", "rate", "distance", "amount", "zero")) {
        BigDecimal number = assertInstanceOf(BigDecimal.class, restored.get(field), field);
        assertEquals(0, new BigDecimal(live.get(field).toString()).compareTo(number), field);
      }
      assertEquals(2, ((BigDecimal) restored.get("amount")).scale());
      assertEquals(0, ((BigDecimal) restored.get("zero")).scale());
      assertEquals("synthetic-stock", restored.get("label"));
      assertEquals(true, restored.get("available"));
      assertNull(restored.get("optional"));
    }
  }

  @Test
  void inputMatchingUsesCanonicalNumericValuesRatherThanWrapperClassOrScale() throws Exception {
    List<Number> equivalent =
        List.of(7, 7L, 7.0f, 7.0d, new BigDecimal("7.000"), BigInteger.valueOf(7));
    CapturePolicy policy = CapturePolicy.allow(Set.of("quantity"), Set.of());
    Path path = temporary.resolve("numeric-inputs.tape");
    try (Recorder recorder = new Recorder(path, (name, arguments) -> Map.of(), policy)) {
      for (Number number : equivalent)
        recorder.call("inventory.reserve", Map.of("quantity", number));
    }
    Tape tape = Tape.read(path);
    for (Tape.Event event : tape.events()) assertEquals("{\"quantity\":7}", event.input());
    try (Replay replay = new Replay(tape, policy)) {
      for (int i = equivalent.size() - 1; i >= 0; i--) {
        assertEquals(
            Map.of(), replay.call("inventory.reserve", Map.of("quantity", equivalent.get(i))));
      }
    }
    Replay changedValue = new Replay(tape, policy);
    assertThrows(
        Replay.Divergence.class,
        () -> changedValue.call("inventory.reserve", Map.of("quantity", new BigDecimal("7.1"))));
    assertEquals(0, changedValue.consumed());
  }

  @Test
  void safePolicyCapturesNoInputOrOutputValuesAndLeavesTheLiveResultUntouched() throws Exception {
    Map<String, Object> input =
        Map.of("query", "Synthetic prompt about an invented private project");
    Map<String, Object> output =
        Map.of("answer", "Synthetic private answer for person@example.invalid");
    Path path = temporary.resolve("safe.tape");
    try (Recorder recorder =
        new Recorder(path, (name, arguments) -> output, CapturePolicy.safe())) {
      assertSame(output, recorder.call("assistant.respond", input));
    }
    Tape tape = Tape.read(path);
    assertEquals("{}", tape.events().get(0).input());
    assertEquals("{}", tape.events().get(0).result());
    try (Replay replay = new Replay(tape, CapturePolicy.safe())) {
      assertEquals(Map.of(), replay.call("assistant.respond", input));
    }
  }

  @Test
  void explicitAllowlistPreservesSyntheticPrivateTextUnderOrdinaryFieldNames() throws Exception {
    // Invented content only: an allowlist is not a classifier for private values.
    Map<String, Object> text =
        Map.of(
            "query", "Synthetic prompt: summarize an invented private project meeting",
            "answer", "Synthetic model completion containing a private conversation",
            "message", "Synthetic exception message: invented confidential project detail",
            "contact", "synthetic.person@example.invalid",
            "detail", "Synthetic internal meeting notes: an invented unreleased plan");
    CapturePolicy policy = CapturePolicy.allow(text.keySet(), text.keySet());
    Path path = temporary.resolve("explicit-text.tape");
    try (Recorder recorder = new Recorder(path, (name, arguments) -> text, policy)) {
      recorder.call("assistant.respond", text);
    }
    Tape tape = Tape.read(path);
    // Inspect decoded event data; base64url in the tape is not a privacy barrier.
    assertEquals(text, Json.object(tape.events().get(0).input()));
    assertEquals(text, Json.object(tape.events().get(0).result()));
    try (Replay replay = new Replay(tape, policy)) {
      assertEquals(text, replay.call("assistant.respond", text));
    }
  }

  @Test
  void secretLikeKeysAreDroppedAndNestedValuesAreRedactedEvenWhenAllowlisted() throws Exception {
    Map<String, Object> values = new LinkedHashMap<>();
    for (String key :
        List.of(
            "password",
            "pass-word",
            "api_key",
            "Authorization",
            "sessionToken",
            "cookie",
            "private-key")) {
      values.put(key, "synthetic-secret-value");
    }
    values.put("object", Map.of("message", "synthetic nested private text"));
    values.put("list", List.of("synthetic nested private text"));
    values.put("array", new String[] {"synthetic nested private text"});
    values.put("status", "synthetic-ok");
    Map<String, Object> expected =
        Map.of(
            "object",
            CapturePolicy.REDACTED,
            "list",
            CapturePolicy.REDACTED,
            "array",
            CapturePolicy.REDACTED,
            "status",
            "synthetic-ok");
    CapturePolicy policy = CapturePolicy.allow(values.keySet(), values.keySet());
    Path path = temporary.resolve("projection.tape");
    try (Recorder recorder = new Recorder(path, (name, arguments) -> values, policy)) {
      assertSame(values, recorder.call("assistant.respond", values));
    }
    Tape tape = Tape.read(path);
    assertEquals(expected, Json.object(tape.events().get(0).input()));
    assertEquals(expected, Json.object(tape.events().get(0).result()));
    try (Replay replay = new Replay(tape, policy)) {
      assertEquals(expected, replay.call("assistant.respond", values));
    }
  }

  @Test
  void thrownExceptionMessageIsNotAutomaticallyRecordedAsAnAllowedMessageField() throws Exception {
    IOException original =
        new IOException("Synthetic private diagnostic for teammate@example.invalid");
    CapturePolicy policy =
        CapturePolicy.allow(Set.of("message"), Set.of("message", "cause", "stackTrace"));
    Path path = temporary.resolve("exception.tape");
    try (Recorder recorder =
        new Recorder(
            path,
            (name, arguments) -> {
              throw original;
            },
            policy)) {
      assertSame(
          original,
          assertThrows(IOException.class, () -> recorder.call("assistant.respond", Map.of())));
    }
    Tape tape = Tape.read(path);
    assertEquals("{}", tape.events().get(0).input());
    assertEquals("ERROR", tape.events().get(0).status());
    assertEquals(IOException.class.getName(), tape.events().get(0).result());
    try (Replay replay = new Replay(tape, policy)) {
      Replay.RecordedToolException restored =
          assertThrows(
              Replay.RecordedToolException.class, () -> replay.call("assistant.respond", Map.of()));
      assertEquals(IOException.class.getName(), restored.originalType());
      assertEquals("Recorded tool failure: java.io.IOException", restored.getMessage());
      assertNull(restored.getCause());
    }
  }
}
