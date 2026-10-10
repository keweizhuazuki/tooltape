package dev.tooltape;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free acceptance suite; uses explicit checks, not disabled Java assertions. */
public final class CoreTest {
  static int checks;

  interface Action {
    void run() throws Exception;
  }

  static void check(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  static void rejects(Class<? extends Throwable> type, Action action) {
    checks++;
    try {
      action.run();
      throw new AssertionError("Expected " + type);
    } catch (Throwable e) {
      if (!type.isInstance(e)) throw new AssertionError("Wrong failure: " + e, e);
    }
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("tooltape-test-");
    try {
      check(
          Json.write(Json.read("{\"z\":1.00,\"a\":[null,true,\"\\n雪\"]}"))
              .equals("{\"a\":[null,true,\"\\n雪\"],\"z\":1}"),
          "Canonical JSON");
      for (String bad :
          List.of("{\"a\":1,\"a\":2}", "[1,]", "01", "true false", "\"\n\"", "1e999999999"))
        rejects(IllegalArgumentException.class, () -> Json.read(bad));
      rejects(IllegalArgumentException.class, () -> Json.write(Double.NaN));
      rejects(
          IllegalArgumentException.class, () -> Json.read("[".repeat(66) + "0" + "]".repeat(66)));
      CapturePolicy p =
          CapturePolicy.allow(Set.of("city", "token", "nested"), Set.of("temperature", "password"));
      check(
          p.input(
                  Map.of(
                      "city",
                      "雪",
                      "token",
                      "secret",
                      "other",
                      "secret2",
                      "nested",
                      Map.of("key", "secret3")))
              .equals(Map.of("city", "雪", "nested", CapturePolicy.REDACTED)),
          "Allowlist and denylist");
      check(CapturePolicy.safe().output(Map.of("freeText", "private")).isEmpty(), "Safe defaults");
      Path file = dir.resolve("weather.tape");
      int[] live = {0};
      try (Recorder recorder =
          new Recorder(
              file,
              (name, input) -> {
                if (live[0]++ == 0) throw new java.io.IOException("secret-message");
                return Map.of("temperature", 22, "password", "secret-password");
              },
              p)) {
        rejects(
            java.io.IOException.class,
            () -> recorder.call("weather", Map.of("city", "雪", "token", "secret-token")));
        recorder.call("weather", Map.of("city", "雪"));
      }
      String raw = Files.readString(file);
      check(!raw.contains("secret"), "No plaintext secret");
      Tape tape = Tape.read(file);
      check(tape.events().size() == 2, "Error and success recorded");
      check(
          tape.events().get(0).result().equals("java.io.IOException"),
          "Exception messages omitted");
      check(tape.events().get(1).result().equals("{\"temperature\":22}"), "Output redacted");
      try (Replay replay = new Replay(tape, p)) {
        rejects(
            Replay.RecordedToolException.class, () -> replay.call("weather", Map.of("city", "雪")));
        check(
            replay.call("weather", Map.of("city", "雪")).get("temperature").toString().equals("22"),
            "Replay output");
      }
      check(live[0] == 2, "Replay never calls live implementation");
      rejects(Replay.Divergence.class, () -> new Replay(tape, p).call("wrong", Map.of()));
      rejects(
          Replay.Divergence.class,
          () -> new Replay(tape, p).call("weather", Map.of("city", "changed")));
      rejects(Replay.Divergence.class, () -> new Replay(tape, p).assertComplete());
      rejects(
          Replay.Divergence.class,
          () -> new Replay(new Tape(List.of()), p).call("extra", Map.of()));
      rejects(FileAlreadyExistsException.class, () -> new Recorder(file, (n, a) -> Map.of(), p));
      check(
          tape.diff(
                  new Tape(
                      tape.events().stream()
                          .map(
                              e ->
                                  new Tape.Event(
                                      e.index(), 99, e.tool(), e.input(), e.status(), e.result()))
                          .toList()))
              .isEmpty(),
          "Diff ignores timing");
      check(tape.diff(new Tape(List.of())).isPresent(), "Diff detects truncation");
      Path bad = dir.resolve("bad.tape");
      for (String content :
          List.of(
              "TOOLTAPE\t2\n",
              raw.substring(0, raw.length() - 1),
              "TOOLTAPE\t1\n0\t0\t?\te30\tOK\te30\n",
              raw.replaceFirst("0\\t", "7\t"))) {
        Files.writeString(bad, content);
        rejects(java.io.IOException.class, () -> Tape.read(bad));
      }
      Files.write(bad, new byte[] {(byte) 0xff, 10});
      rejects(java.io.IOException.class, () -> Tape.read(bad));
      Files.write(bad, new byte[Tape.MAX_BYTES + 1]);
      rejects(java.io.IOException.class, () -> Tape.read(bad));
      check(Main.run(new String[] {"validate", file.toString()}) == 0, "CLI validate");
      check(Main.run(new String[] {"diff", file.toString(), file.toString()}) == 0, "CLI diff");
      check(Main.run(new String[] {"unknown"}) == 2, "CLI usage");
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try (Recorder recorder =
          new Recorder(
              dir.resolve("concurrent.tape"),
              (n, a) -> {
                entered.countDown();
                release.await();
                return Map.of();
              },
              p)) {
        Future<?> running =
            pool.submit(
                () -> {
                  try {
                    recorder.call("slow", Map.of());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
        check(entered.await(3, TimeUnit.SECONDS), "Live call entered");
        rejects(IllegalStateException.class, () -> recorder.call("race", Map.of()));
        rejects(IllegalStateException.class, recorder::close);
        release.countDown();
        running.get(3, TimeUnit.SECONDS);
      } finally {
        release.countDown();
        pool.shutdownNow();
      }
      Recorder closed = new Recorder(dir.resolve("closed.tape"), (n, a) -> Map.of(), p);
      closed.close();
      rejects(IllegalStateException.class, () -> closed.call("tool", Map.of()));
      int[] afterFailure = {0};
      try (Recorder poisoned =
          new Recorder(
              dir.resolve("poisoned.tape"),
              (n, a) -> {
                afterFailure[0]++;
                return null;
              },
              p)) {
        rejects(NullPointerException.class, () -> poisoned.call("tool", Map.of()));
        rejects(IllegalStateException.class, () -> poisoned.call("tool", Map.of()));
        check(afterFailure[0] == 1, "Persistence failure prevents more side effects");
      }
      rejects(
          IllegalArgumentException.class,
          () -> new Tape.Event(0, 0, "secret in name", "{}", "OK", "{}"));
      rejects(
          IllegalArgumentException.class,
          () -> new Tape.Event(0, 0, "tool", "{}", "ERROR", "message with secrets"));
      rejects(IllegalArgumentException.class, () -> Tape.decode("Zh"));
      System.out.println(
          "PASS: " + checks + " checks (privacy, replay, format, failures, concurrency, CLI)");
    } finally {
      try (var paths = Files.walk(dir)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }
}
