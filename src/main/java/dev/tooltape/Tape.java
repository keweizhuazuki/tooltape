package dev.tooltape;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Immutable, versioned tape; reads reject malformed, oversized, or truncated records. */
public record Tape(List<Tape.Event> events, Metadata metadata, boolean complete) {
  public static final String HEADER = "TOOLTAPE\t1";
  public static final String HEADER_V2 = "TOOLTAPE\t2";
  public static final int MAX_BYTES = Json.MAX_BYTES;

  /** Legacy in-memory tape: no policy identity or completion evidence. */
  public Tape(List<Event> events) {
    this(events, null, false);
  }

  public int version() {
    return metadata == null ? 1 : 2;
  }

  public record Metadata(String policyId, String source) {
    public Metadata {
      if (policyId == null || !policyId.matches("sha256:[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid policy identifier");
      validateName(source);
    }

    String line() {
      return "META\t" + encode(Json.write(Map.of("policyId", policyId, "source", source)));
    }
  }

  public Tape {
    events = List.copyOf(events);
    long canonicalBytes = 0;
    for (Event event : events) {
      canonicalBytes += event.canonicalBytes();
      if (canonicalBytes > MAX_BYTES)
        throw new IllegalArgumentException("Canonical tape payload exceeds 16 MiB");
    }
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
      else if (!status.equals("ERROR")
          || result.length() > 1024
          || !result.matches(
              "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Nd}\\p{Mc}\\p{Mn}]*(\\.[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Nd}\\p{Mc}\\p{Mn}]*)*"))
        throw new IllegalArgumentException("Invalid outcome");
    }

    long canonicalBytes() {
      return (long) tool.getBytes(StandardCharsets.UTF_8).length
          + input.getBytes(StandardCharsets.UTF_8).length
          + status.length()
          + result.getBytes(StandardCharsets.UTF_8).length;
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

  /** Read a tape from a file, closing the stream opened for this call. */
  public static Tape read(Path path) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(path)) {
      bytes = in.readNBytes(MAX_BYTES + 1);
    }
    return parse(bytes);
  }

  /**
   * Read a tape from a caller-owned stream using the same format and limits as {@link #read(Path)}.
   * Reads through EOF, or at most {@code MAX_BYTES + 1} bytes to detect an oversized tape. The
   * stream is never closed, including when reading or validation fails.
   *
   * @throws InvalidTapeException if the tape is malformed, truncated, or exceeds a format limit
   * @throws IOException if reading the stream fails
   * @throws NullPointerException if {@code in} is null
   */
  public static Tape read(InputStream in) throws IOException {
    Objects.requireNonNull(in, "in");
    return parse(in.readNBytes(MAX_BYTES + 1));
  }

  private static Tape parse(byte[] bytes) throws InvalidTapeException {
    if (bytes.length > MAX_BYTES) throw new InvalidTapeException("Tape exceeds 16 MiB");
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new InvalidTapeException("Invalid UTF-8", e);
    }
    if (!text.endsWith("\n"))
      throw new InvalidTapeException("Truncated tape: missing final newline");
    String[] lines = text.split("\n", -1);
    boolean v2 = lines[0].equals(HEADER_V2);
    if (!v2 && !lines[0].equals(HEADER))
      throw new InvalidTapeException("Unsupported tape header/version");
    Metadata metadata = null;
    int firstEvent = 1;
    if (v2) {
      try {
        if (lines.length < 3 || !lines[1].startsWith("META\t"))
          throw new IllegalArgumentException("Missing metadata");
        String[] fields = lines[1].split("\t", -1);
        if (fields.length != 2) throw new IllegalArgumentException("Invalid metadata row");
        String metadataJson = decode(fields[1]);
        Map<String, Object> values = Json.object(metadataJson);
        if (!Json.write(values).equals(metadataJson))
          throw new IllegalArgumentException("Metadata JSON must be canonical");
        if (!values.keySet().equals(Set.of("policyId", "source")))
          throw new IllegalArgumentException("Unknown or missing metadata fields");
        if (!(values.get("policyId") instanceof String)
            || !(values.get("source") instanceof String))
          throw new IllegalArgumentException("Metadata values must be strings");
        metadata = new Metadata((String) values.get("policyId"), (String) values.get("source"));
      } catch (IllegalArgumentException | CharacterCodingException e) {
        throw new InvalidTapeException("Invalid v2 metadata: " + e.getMessage(), e);
      }
      firstEvent = 2;
    }
    boolean complete = false;
    List<Event> events = new ArrayList<>();
    long canonicalBytes = 0;
    for (int i = firstEvent; i < lines.length - 1; i++)
      try {
        String[] c = lines[i].split("\t", -1);
        if (v2 && c[0].equals("END")) {
          if (c.length != 2
              || !c[1].matches("0|[1-9][0-9]*")
              || Integer.parseInt(c[1]) != events.size()
              || i != lines.length - 2)
            throw new IllegalArgumentException("Invalid completion footer");
          complete = true;
          continue;
        }
        if (c.length != 6) throw new IllegalArgumentException("Expected six columns");
        if (!c[0].matches("0|[1-9][0-9]*") || !c[1].matches("0|[1-9][0-9]*"))
          throw new IllegalArgumentException("Non-canonical index/duration");
        Event event =
            new Event(
                Integer.parseInt(c[0]),
                Long.parseLong(c[1]),
                decode(c[2]),
                decode(c[3]),
                c[4],
                decode(c[5]));
        canonicalBytes += event.canonicalBytes();
        if (canonicalBytes > MAX_BYTES)
          throw new IllegalArgumentException("Canonical tape payload exceeds 16 MiB");
        events.add(event);
      } catch (IllegalArgumentException | CharacterCodingException e) {
        throw new InvalidTapeException(
            "Invalid event at line " + (i + 1) + ": " + e.getMessage(), e);
      }
    try {
      return new Tape(events, metadata, complete);
    } catch (IllegalArgumentException e) {
      throw new InvalidTapeException(e.getMessage(), e);
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
        if (!x.equals(y)) {
          String location = "";
          if (field.equals("input")
              || (field.equals("result") && a.status().equals("OK") && b.status().equals("OK")))
            location = " at " + Json.firstDifference(Json.read(x), Json.read(y)).orElse("");
          return Optional.of(
              "Event "
                  + i
                  + " differs: "
                  + field
                  + location
                  + "\nexpected: "
                  + x
                  + "\nactual:   "
                  + y);
        }
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
