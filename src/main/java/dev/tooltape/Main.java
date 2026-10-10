package dev.tooltape;

import java.nio.file.*;
import java.util.*;

/** CLI exits: 0 success/equal; 1 divergence; 2 usage/invalid tape/I/O. */
public final class Main {
  public static final String VERSION = "0.2.0";

  private Main() {}

  public static void main(String[] args) {
    int code = run(args);
    if (code != 0) System.exit(code);
  }

  public static int run(String[] args) {
    try {
      if (args.length == 1 && args[0].equals("--version")) {
        System.out.println("ToolTape " + VERSION + " (schemas 1, 2)");
        return 0;
      }
      if (args.length == 2 && args[0].equals("inspect")) {
        Tape tape = Tape.read(Path.of(args[1]));
        System.out.println(
            "ToolTape v" + tape.version() + " · " + tape.events().size() + " serial events");
        if (tape.metadata() != null)
          System.out.println(
              "Session: "
                  + (tape.complete() ? "CLOSED" : "INCOMPLETE")
                  + " · source="
                  + tape.metadata().source()
                  + " · policy="
                  + tape.metadata().policyId());
        else System.out.println("Legacy v1: policy and completion are unverified");
        for (Tape.Event e : tape.events())
          System.out.printf(
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
        System.out.println(diff.orElse("MATCH: all events equal (timing ignored)"));
        return diff.isPresent() ? 1 : 0;
      }
      if (args.length == 2 && args[0].equals("validate")) {
        Tape tape = Tape.read(Path.of(args[1]));
        if (tape.version() == 2 && !tape.complete())
          throw new IllegalArgumentException("Incomplete v2 tape: no verified END marker");
        System.out.println(
            "VALID: "
                + tape.events().size()
                + " events, schema v"
                + tape.version()
                + (tape.version() == 1
                    ? " (legacy policy/completion unverified)"
                    : " (closed session)"));
        return 0;
      }
      System.err.println(
          "Usage: java -jar tooltape.jar inspect <file> | validate <file> | diff <expected>"
              + " <actual>");
      return 2;
    } catch (Exception e) {
      System.err.println("ToolTape: " + e.getMessage());
      return 2;
    }
  }
}
