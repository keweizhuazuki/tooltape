package dev.tooltape;

import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;

/** CLI exits: 0 success/equal; 1 divergence; 2 usage/invalid or incompatible tape/I/O. */
public final class Main {
  public static final String VERSION = "0.2.0";

  private Main() {}

  public static void main(String[] args) {
    int code = run(args);
    if (code != 0) System.exit(code);
  }

  public static int run(String[] args) {
    return run(args, System.out, System.err);
  }

  /** Run a command with caller-owned output streams. Neither stream is closed. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (Arrays.asList(args).contains("--json")) return CliReport.run(args, out);
    if (Arrays.asList(args).contains("--require-v2")) return usage(err);
    try {
      if (args.length == 1 && args[0].equals("--version")) {
        out.println("ToolTape " + VERSION + " (schemas 1, 2)");
        return 0;
      }
      if (args.length == 2 && args[0].equals("inspect")) {
        Tape tape = Tape.read(Path.of(args[1]));
        out.println(
            "ToolTape v" + tape.version() + " · " + tape.events().size() + " serial events");
        if (tape.metadata() != null)
          out.println(
              "Session: "
                  + (tape.complete() ? "CLOSED" : "INCOMPLETE")
                  + " · source="
                  + tape.metadata().source()
                  + " · policy="
                  + tape.metadata().policyId());
        else out.println("Legacy v1: policy and completion are unverified");
        for (Tape.Event e : tape.events())
          out.printf(
              Locale.ROOT,
              "%03d  %-5s %-24s %.3f ms%n     in  %s%n     out %s%n",
              e.index(),
              e.status(),
              e.tool(),
              e.durationNanos() / 1_000_000.0,
              e.input(),
              e.result());
        return 0;
      }
      if (args.length == 3 && args[0].equals("diff")) {
        Optional<String> diff = Tape.read(Path.of(args[1])).diff(Tape.read(Path.of(args[2])));
        out.println(diff.orElse("MATCH: all events equal (timing ignored)"));
        return diff.isPresent() ? 1 : 0;
      }
      if (args.length == 2 && args[0].equals("validate")) {
        Tape tape = Tape.read(Path.of(args[1]));
        if (tape.version() == 2 && !tape.complete())
          throw new IllegalArgumentException("Incomplete v2 tape: no verified END marker");
        out.println(
            "VALID: "
                + tape.events().size()
                + " events, schema v"
                + tape.version()
                + (tape.version() == 1
                    ? " (legacy policy/completion unverified)"
                    : " (closed session)"));
        return 0;
      }
      return usage(err);
    } catch (Exception e) {
      err.println("ToolTape: " + e.getMessage());
      return 2;
    }
  }

  private static int usage(PrintStream err) {
    err.println(
        "Usage: java -jar tooltape.jar <command>\n"
            + "  inspect <file>\n"
            + "  validate <file>\n"
            + "  validate --json [--require-v2] <file>\n"
            + "  diff <expected> <actual>\n"
            + "  diff --json [--require-v2] <expected> <actual>\n"
            + "  --version\n"
            + "--require-v2 rejects legacy v1 inputs (exit 2); only supported after --json.\n"
            + "Exit codes: 0 valid/matching, 1 event divergence, 2 invalid/unusable input.\n"
            + "This gate does not verify authenticity, business success or the current capture"
            + " policy.");
    return 2;
  }
}
