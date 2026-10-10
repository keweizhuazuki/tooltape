package dev.tooltape;

import java.nio.file.*;
import java.util.*;

/** CLI exits: 0 success/equal; 1 divergence; 2 usage/invalid tape/I/O. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int code = run(args);
    if (code != 0) System.exit(code);
  }

  public static int run(String[] args) {
    try {
      if (args.length == 2 && args[0].equals("inspect")) {
        Tape tape = Tape.read(Path.of(args[1]));
        System.out.println("ToolTape v1 · " + tape.events().size() + " serial events");
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
        System.out.println("VALID: " + tape.events().size() + " events, schema v1");
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
