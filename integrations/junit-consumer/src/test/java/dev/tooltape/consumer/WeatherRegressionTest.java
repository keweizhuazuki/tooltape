package dev.tooltape.consumer;

import dev.tooltape.CapturePolicy;
import dev.tooltape.Replay;
import dev.tooltape.junit.ReplayFixture;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The getting-started documentation's complete example, using this project's resource name. */
class WeatherRegressionTest {
  private static final CapturePolicy POLICY =
      CapturePolicy.allow(Set.of("city"), Set.of("temperature"));
  private static final ReplayFixture FIXTURE = ReplayFixture.fromResource(
      WeatherRegressionTest.class, "/dev/tooltape/consumer/retry.tape", POLICY);

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
