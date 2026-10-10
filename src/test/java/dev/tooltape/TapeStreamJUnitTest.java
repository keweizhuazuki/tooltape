package dev.tooltape;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TapeStreamJUnitTest {
  @TempDir Path dir;

  @Test
  void legacyAndCurrentFixturesHaveIdenticalPathAndStreamResults() throws Exception {
    for (String name : List.of("weather-retry.tape", "weather-retry-v2.tape")) {
      Path path = Path.of("fixtures", name);
      TrackingInputStream in = new TrackingInputStream(Files.readAllBytes(path), 3);
      Tape expected = Tape.read(path);
      assertEquals(expected, Tape.read(in), name);
      assertEquals(Files.size(path), in.bytesRead);
      assertFalse(in.closed, name);
      assertEquals(-1, in.read());
    }
  }

  @Test
  void emptyAndInterruptedSessionsPreserveCompletionAndMetadata() throws Exception {
    String metadata =
        new Tape.Metadata(CapturePolicy.safe().identifier(), "stream-test").line() + "\n";
    for (String text :
        List.of(
            Tape.HEADER + "\n",
            Tape.HEADER_V2 + "\n" + metadata,
            Tape.HEADER_V2 + "\n" + metadata + "END\t0\n")) {
      TrackingInputStream in = new TrackingInputStream(bytes(text), 1);
      assertEquals(readPath(bytes(text)), Tape.read(in));
      assertFalse(in.closed);
    }
  }

  @Test
  void unicodeFieldsRoundTripThroughShortReads() throws Exception {
    Tape.Event event =
        new Tape.Event(0, 27, "weather.lookup", "{\"city\":\"雪🌧\"}", "ERROR", "dev.tooltape.故障");
    TrackingInputStream in =
        new TrackingInputStream(bytes(Tape.HEADER + "\n" + event.line() + "\n"), 1);
    assertEquals(new Tape(List.of(event)), Tape.read(in));
    assertFalse(in.closed);
  }

  @Test
  void malformedAndTruncatedTapesFailIdenticallyWithoutClosingStream() throws Exception {
    String valid = Tape.HEADER + "\n0\t0\tdG9vbA\te30\tOK\te30\n";
    String v2 = Files.readString(Path.of("fixtures/weather-retry-v2.tape"));
    for (String text :
        List.of(
            "",
            "TOOLTAPE\t3\n",
            valid.substring(0, valid.length() - 1),
            valid + "\n",
            valid.replace("\n", "\r\n"),
            valid.replace("dG9vbA", "?"),
            valid.replace("e30", "e30="),
            valid.replace("e30", "e31"),
            valid.replace("e30", Tape.encode("[]")),
            valid.replace("e30", Tape.encode("{")),
            valid.replace("0\t0", "1\t0"),
            valid.replace("0\t0", "00\t0"),
            valid.replace("0\t0", "0\t-1"),
            valid.replace("0\t0", "0\t9223372036854775808"),
            valid.replace("\tOK\t", "\tUNKNOWN\t"),
            "TOOLTAPE\t2\nEND\t0\n",
            "TOOLTAPE\t2\nMETA\t" + Tape.encode("{}") + "\nEND\t0\n",
            v2.replace("END\t2", "END\t1"),
            v2 + "END\t2\n")) {
      assertInvalidParity(bytes(text));
    }
  }

  @Test
  void invalidUtf8IsRejectedInEnvelopeAndEncodedFields() throws Exception {
    for (byte[] invalid :
        List.of(
            new byte[] {(byte) 0xff},
            new byte[] {(byte) 0xc0, (byte) 0xaf},
            new byte[] {(byte) 0xe2, (byte) 0x82},
            new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80})) {
      byte[] envelope = Arrays.copyOf(invalid, invalid.length + 1);
      envelope[invalid.length] = '\n';
      assertEquals("Invalid UTF-8", assertInvalidParity(envelope).getMessage());
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(invalid);
      assertInvalidParity(bytes(Tape.HEADER + "\n0\t0\tdG9vbA\te30\tOK\t" + encoded + "\n"));
      assertInvalidParity(bytes(Tape.HEADER_V2 + "\nMETA\t" + encoded + "\nEND\t0\n"));
    }
  }

  @Test
  void exactlyMaxBytesIsAcceptedAndStreamRemainsOpen() throws Exception {
    String prefix = Tape.HEADER + "\n0\t0\tdG9vbA\te30\tOK\t";
    int encodedLength = Tape.MAX_BYTES - prefix.length() - 1;
    // Whitespace is valid JSON and keeps the canonical event small at the transport limit.
    int jsonLength = encodedLength * 3 / 4;
    byte[] content = bytes(prefix + Tape.encode("{}" + " ".repeat(jsonLength - 2)) + "\n");
    assertEquals(Tape.MAX_BYTES, content.length);
    TrackingInputStream in = new TrackingInputStream(content, 8192);
    Tape tape = Tape.read(in);
    assertEquals(new Tape(List.of(new Tape.Event(0, 0, "tool", "{}", "OK", "{}"))), tape);
    assertEquals(tape, readPath(content));
    assertEquals(Tape.MAX_BYTES, in.bytesRead);
    assertEquals(-1, in.read());
    assertFalse(in.closed);
  }

  @Test
  void oversizedStreamConsumesOnlyLimitPlusOneBytesAndLeavesTheRest() throws Exception {
    byte[] content = new byte[Tape.MAX_BYTES + 2];
    content[content.length - 1] = 42;
    TrackingInputStream in = new TrackingInputStream(content, 8192);
    InvalidTapeException error = assertThrows(InvalidTapeException.class, () -> Tape.read(in));
    assertEquals("Tape exceeds 16 MiB", error.getMessage());
    assertEquals(
        error.getMessage(),
        assertThrows(InvalidTapeException.class, () -> readPath(content)).getMessage());
    assertEquals(Tape.MAX_BYTES + 1, in.bytesRead);
    assertFalse(in.closed);
    assertEquals(42, in.read());
    assertEquals(-1, in.read());
  }

  @Test
  void unendingStreamIsRejectedAfterBoundedRead() {
    InputStream in =
        new InputStream() {
          int count;

          @Override
          public int read() {
            assertTrue(count < Tape.MAX_BYTES + 1, "Read beyond oversized-tape sentinel");
            count++;
            return 'x';
          }

          @Override
          public int read(byte[] b, int off, int len) {
            assertTrue(len <= Tape.MAX_BYTES + 1 - count, "Requested bytes beyond limit");
            Arrays.fill(b, off, off + len, (byte) 'x');
            count += len;
            return len;
          }

          @Override
          public void close() {
            fail("Caller-owned stream must not be closed");
          }
        };
    assertEquals(
        "Tape exceeds 16 MiB",
        assertThrows(InvalidTapeException.class, () -> Tape.read(in)).getMessage());
  }

  @Test
  void canonicalPayloadLimitsAreSharedByBothEntryPoints() throws Exception {
    String result = "{\"n\":[" + String.join(",", Collections.nCopies(1000, "1e9999")) + "]}";
    String event = "\t0\tdG9vbA\te30\tOK\t" + Tape.encode(result) + "\n";
    InvalidTapeException error =
        assertInvalidParity(bytes(Tape.HEADER + "\n0" + event + "1" + event));
    assertTrue(error.getMessage().contains("Canonical tape payload exceeds 16 MiB"));
  }

  @Test
  void ioFailuresPropagateWithoutClosingCallerStream() {
    for (int failAfter : List.of(0, 5, bytes(Tape.HEADER + "\n").length)) {
      IOException expected = new IOException("read failed");
      TrackingInputStream in =
          new TrackingInputStream(bytes(Tape.HEADER + "\n"), 1) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
              if (bytesRead >= failAfter) throw expected;
              return super.read(b, off, len);
            }
          };
      assertSame(expected, assertThrows(IOException.class, () -> Tape.read(in)));
      assertFalse(in.closed);
      assertEquals(0, expected.getSuppressed().length);
    }
  }

  @Test
  void bothEntryPointsRejectNull() {
    assertThrows(NullPointerException.class, () -> Tape.read((InputStream) null));
    assertThrows(NullPointerException.class, () -> Tape.read((Path) null));
  }

  private Tape readPath(byte[] content) throws IOException {
    Path path = dir.resolve("input.tape");
    Files.write(path, content);
    return Tape.read(path);
  }

  private InvalidTapeException assertInvalidParity(byte[] content) throws IOException {
    InvalidTapeException expected =
        assertThrows(InvalidTapeException.class, () -> readPath(content));
    TrackingInputStream in = new TrackingInputStream(content, 8192);
    InvalidTapeException actual = assertThrows(InvalidTapeException.class, () -> Tape.read(in));
    assertEquals(expected.getMessage(), actual.getMessage());
    assertEquals(
        expected.getCause() == null ? null : expected.getCause().getClass(),
        actual.getCause() == null ? null : actual.getCause().getClass());
    assertFalse(in.closed);
    return actual;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static class TrackingInputStream extends InputStream {
    private final ByteArrayInputStream delegate;
    private final int maxChunk;
    int bytesRead;
    boolean closed;

    TrackingInputStream(byte[] content, int maxChunk) {
      delegate = new ByteArrayInputStream(content);
      this.maxChunk = maxChunk;
    }

    @Override
    public int read() {
      int value = delegate.read();
      if (value != -1) bytesRead++;
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int count = delegate.read(b, off, Math.min(len, maxChunk));
      if (count > 0) bytesRead += count;
      return count;
    }

    @Override
    public int available() {
      throw new AssertionError("Parser must not rely on available()");
    }

    @Override
    public void close() throws IOException {
      closed = true;
      throw new IOException("Caller-owned stream must not be closed");
    }
  }
}
