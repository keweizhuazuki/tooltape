package dev.tooltape;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RegressionJUnitTest {
    @Test void acceptanceSuite() throws Exception {CoreTest.main(new String[0]);}
    @Test void replayCapturedFailureAndRetry() throws Exception {
        CapturePolicy policy=CapturePolicy.allow(Set.of("city"),Set.of("temperature"));
        try(Replay replay=new Replay(Tape.read(Path.of("fixtures/weather-retry.tape")),policy)){
            Replay.RecordedToolException error=assertThrows(Replay.RecordedToolException.class,()->replay.call("weather.lookup",Map.of("city","Hangzhou")));
            assertEquals("java.io.IOException",error.originalType());
            assertEquals("22",replay.call("weather.lookup",Map.of("city","Hangzhou")).get("temperature").toString());
            // close() asserts every expected call was consumed.
        }
    }
    @Test void changedArgumentsFailAtFirstDivergence() throws Exception {
        Replay replay=new Replay(Tape.read(Path.of("fixtures/weather-retry.tape")),CapturePolicy.allow(Set.of("city"),Set.of("temperature")));
        Replay.Divergence mismatch=assertThrows(Replay.Divergence.class,()->replay.call("weather.lookup",Map.of("city","Shanghai")));
        assertTrue(mismatch.getMessage().contains("event 0"));
    }
}
