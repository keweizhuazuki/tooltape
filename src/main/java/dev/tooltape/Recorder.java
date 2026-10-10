package dev.tooltape;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates a new tape, flushing each completed serial call. Never overwrites a fixture. */
public final class Recorder implements Tool, AutoCloseable {
  private final BufferedWriter writer;
  private final Tool delegate;
  private final CapturePolicy policy;
  private final AtomicBoolean busy = new AtomicBoolean();
  private int index;
  private boolean closed, poisoned;
  private int bytes;

  public Recorder(Path path, Tool delegate, CapturePolicy policy) throws IOException {
    this(path, delegate, policy, "unspecified");
  }

  /** Source is an explicitly supplied static provenance label, not user content or a file path. */
  public Recorder(Path path, Tool delegate, CapturePolicy policy, String source)
      throws IOException {
    this.delegate = Objects.requireNonNull(delegate);
    this.policy = Objects.requireNonNull(policy);
    Tape.Metadata metadata = new Tape.Metadata(policy.identifier(), source);
    String preamble = Tape.HEADER_V2 + "\n" + metadata.line() + "\n";
    writer =
        Files.newBufferedWriter(
            path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    try {
      writer.write(preamble);
      writer.flush();
      bytes = preamble.getBytes(StandardCharsets.UTF_8).length;
    } catch (IOException failure) {
      try {
        writer.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  @Override
  public Map<String, Object> call(String name, Map<String, Object> arguments) throws Exception {
    if (!busy.compareAndSet(false, true))
      throw new IllegalStateException("Concurrent or recursive calls are unsupported");
    try {
      if (closed || poisoned) throw new IllegalStateException("Recorder is closed or failed");
      Tape.validateName(name);
      String input = Json.write(policy.input(arguments));
      long start = System.nanoTime();
      Map<String, Object> result;
      try {
        result = delegate.call(name, arguments);
      } catch (Exception original) {
        try {
          append(
              new Tape.Event(
                  index,
                  System.nanoTime() - start,
                  name,
                  input,
                  "ERROR",
                  original.getClass().getName()));
        } catch (Exception writeFailure) {
          poisoned = true;
          original.addSuppressed(writeFailure);
        }
        throw original;
      } catch (Error fatal) {
        // An unrecorded fatal call must never leave a session that looks replay-complete.
        poisoned = true;
        throw fatal;
      }
      try {
        append(
            new Tape.Event(
                index,
                System.nanoTime() - start,
                name,
                input,
                "OK",
                Json.write(policy.output(result))));
      } catch (Exception failure) {
        poisoned = true;
        throw failure;
      }
      return result;
    } finally {
      busy.set(false);
    }
  }

  private void append(Tape.Event event) throws IOException {
    String line = event.line() + "\n";
    int size = line.getBytes(StandardCharsets.UTF_8).length;
    if ((long) bytes + size + 15 > Tape.MAX_BYTES)
      throw new IOException("Tape exceeds 16 MiB; recording stopped after tool execution");
    writer.write(line);
    writer.flush();
    bytes += size;
    index++;
  }

  @Override
  public void close() throws IOException {
    if (!busy.compareAndSet(false, true))
      throw new IllegalStateException("Cannot close during a call");
    try {
      if (closed) return;
      closed = true;
      try {
        if (!poisoned) {
          writer.write("END\t" + index + "\n");
          writer.flush();
        }
      } finally {
        writer.close();
      }

    } finally {
      busy.set(false);
    }
  }
}
