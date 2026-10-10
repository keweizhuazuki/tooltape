import dev.tooltape.CapturePolicy;
import dev.tooltape.Recorder;
import dev.tooltape.Replay;
import dev.tooltape.Tape;
import dev.tooltape.Tool;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Normalize JSON numbers before using them as domain values; no external service is called. */
public final class NumericBoundaryRegression {
  record Inventory(int available) {}

  static int exactInt(Object value) {
    if (!(value instanceof Number number))
      throw new IllegalArgumentException("Expected a JSON number");
    return new BigDecimal(number.toString()).intValueExact();
  }

  static Inventory inventory(Tool tool) throws Exception {
    Map<String, Object> result = tool.call("inventory.lookup", Map.of("sku", "demo-item"));
    return new Inventory(exactInt(result.get("available")));
  }

  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("tooltape-numeric-example-");
    Path path = directory.resolve("inventory.tape");
    try {
      CapturePolicy policy = CapturePolicy.allow(Set.of("sku"), Set.of("available"));
      int[] liveCalls = {0};
      Tool live =
          (name, input) -> {
            liveCalls[0]++;
            return Map.of("available", Integer.valueOf(22));
          };
      Inventory recorded;
      try (Recorder recorder = new Recorder(path, live, policy, "numeric-boundary-example")) {
        recorded = inventory(recorder);
      }
      Inventory replayed;
      try (Replay replay = new Replay(Tape.read(path), policy)) {
        replayed = inventory(replay);
      }
      if (!recorded.equals(replayed) || liveCalls[0] != 1)
        throw new AssertionError("Domain result changed or live tool ran during replay");
      rejectInexact(new BigDecimal("22.5"));
      rejectInexact(new BigDecimal("2147483648"));
      System.out.println(
          "PASS: Integer recording and BigDecimal replay produce Inventory[available=22]; live"
              + " calls=1");
      System.out.println(
          "PASS: exact integer conversion rejects fractional and out-of-range values");
    } finally {
      Files.deleteIfExists(path);
      Files.deleteIfExists(directory);
    }
  }

  private static void rejectInexact(BigDecimal value) {
    try {
      exactInt(value);
    } catch (ArithmeticException expected) {
      return;
    }
    throw new AssertionError("Expected exact integer conversion to reject " + value);
  }
}
