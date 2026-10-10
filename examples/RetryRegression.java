import dev.tooltape.*;
import java.nio.file.*;
import java.util.*;

/** A real retry orchestration regression: no HTTP, model, keys or network needed. */
public class RetryRegression {
  static final CapturePolicy POLICY = CapturePolicy.allow(Set.of("city"), Set.of("temperature"));

  static Map<String, Object> forecast(Tool tool, String city) throws Exception {
    try {
      return tool.call("weather.lookup", Map.of("city", city));
    } catch (java.io.IOException | Replay.RecordedToolException e) {
      return tool.call("weather.lookup", Map.of("city", city));
    }
  }

  public static void main(String[] args) throws Exception {
    Path tape = Path.of(args.length == 0 ? "target/weather.tape" : args[0]);
    int[] calls = {0};
    Tool live =
        (name, input) -> {
          if (calls[0]++ == 0)
            throw new java.io.IOException("Sensitive original error is never persisted");
          return Map.of("temperature", 22, "token", "NEVER-PERSISTED");
        };
    try (Recorder record = new Recorder(tape, live, POLICY)) {
      forecast(record, "Hangzhou");
    }
    try (Replay replay = new Replay(Tape.read(tape), POLICY)) {
      if (!forecast(replay, "Hangzhou").get("temperature").toString().equals("22"))
        throw new AssertionError("Forecast changed");
    }
    System.out.println("PASS: recorded timeout + retry, replayed offline; live calls=" + calls[0]);
    Replay changed = new Replay(Tape.read(tape), POLICY);
    try {
      forecast(changed, "Shanghai");
      throw new AssertionError("Expected divergence");
    } catch (Replay.Divergence expected) {
      System.out.println(expected.getMessage());
    }
  }
}
