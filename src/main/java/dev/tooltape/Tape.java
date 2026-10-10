package dev.tooltape;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Immutable, versioned tape; reads reject malformed, oversized, or truncated records. */
public record Tape(List<Tape.Event> events) {
  public static final String HEADER = "TOOLTAPE\t1";
  public static final int MAX_BYTES = 16 * 1024 * 1024;

  public Tape {
    events = List.copyOf(events);
    for (int i = 0; i < events.size(); i++)
      if (events.get(i).index() != i)
        throw new IllegalArgumentException("Non-contiguous event sequence");
  }

  public record Event(
      int index, long durationNanos, String tool, String input, String status, String result) {
    public Event {
      if (index < 0 || durationNanos < 0)
        throw new IllegalArgumentException("Negative index or duration");
      validateName(tool);
      input = Json.write(Json.object(input));
      if (status.equals("OK")) result = Json.write(Json.object(result));
      else if (!status.equals("ERROR") || !result.matches("[A-Za-z_$][A-Za-z0-9_.$]*"))
        throw new IllegalArgumentException("Invalid outcome");
    }

    String line() {
      return index
          + "\t"
          + durationNanos
          + "\t"
          + encode(tool)
          + "\t"
          + encode(input)
          + "\t"
          + status
          + "\t"
          + encode(result);
    }
  }

  static void validateName(String name) {
    if (name == null || !name.matches("[A-Za-z0-9_.:-]{1,128}"))
      throw new IllegalArgumentException("Tool name must be a static identifier (1..128 chars)");
  }

  static String encode(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  static String decode(String s) throws CharacterCodingException {
    if (!s.matches("[A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid base64url");
    String decoded =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(s)))
            .toString();
    if (!encode(decoded).equals(s)) throw new IllegalArgumentException("Non-canonical base64url");
    return decoded;
  }

  public static Tape read(Path path) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(path)) {
      bytes = in.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES) throw new IOException("Tape exceeds 16 MiB");
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("Invalid UTF-8", e);
    }
    if (!text.endsWith("\n")) throw new IOException("Truncated tape: missing final newline");
    String[] lines = text.split("\n", -1);
    if (!lines[0].equals(HEADER)) throw new IOException("Unsupported tape header/version");
    List<Event> events = new ArrayList<>();
    for (int i = 1; i < lines.length - 1; i++)
      try {
        String[] c = lines[i].split("\t", -1);
        if (c.length != 6) throw new IllegalArgumentException("Expected six columns");
        if (!c[0].matches("0|[1-9][0-9]*") || !c[1].matches("0|[1-9][0-9]*"))
          throw new IllegalArgumentException("Non-canonical index/duration");
        events.add(
            new Event(
                Integer.parseInt(c[0]),
                Long.parseLong(c[1]),
                decode(c[2]),
                decode(c[3]),
                c[4],
                decode(c[5])));
      } catch (IllegalArgumentException | CharacterCodingException e) {
        throw new IOException("Invalid event at line " + (i + 1) + ": " + e.getMessage(), e);
      }
    try {
      return new Tape(events);
    } catch (IllegalArgumentException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /** Compare semantic event data; timing is deliberately ignored. */
  public Optional<String> diff(Tape other) {
    for (int i = 0; i < Math.min(events.size(), other.events.size()); i++) {
      Event a = events.get(i), b = other.events.get(i);
      for (String field : List.of("tool", "input", "status", "result")) {
        String x =
            switch (field) {
              case "tool" -> a.tool();
              case "input" -> a.input();
              case "status" -> a.status();
              default -> a.result();
            };
        String y =
            switch (field) {
              case "tool" -> b.tool();
              case "input" -> b.input();
              case "status" -> b.status();
              default -> b.result();
            };
        if (!x.equals(y))
          return Optional.of(
              "Event " + i + " differs: " + field + "\nexpected: " + x + "\nactual:   " + y);
      }
    }
    return events.size() == other.events.size()
        ? Optional.empty()
        : Optional.of(
            "Event "
                + Math.min(events.size(), other.events.size())
                + " differs: event count ("
                + events.size()
                + " vs "
                + other.events.size()
                + ")");
  }
}
