package dev.tooltape;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded, payload-free machine-readable CLI reports. No untrusted diagnostic text is emitted. */
final class CliReport {
  private CliReport() {}

  static int run(String[] args, PrintStream out) {
    String command =
        args.length > 0 && ("validate".equals(args[0]) || "diff".equals(args[0])) ? args[0] : null;
    Map<String, Object> report = base(command);
    boolean requireV2 = args.length > 2 && "--require-v2".equals(args[2]);
    int firstFile = requireV2 ? 3 : 2;
    try {
      if (command == null
          || args.length != firstFile + (command.equals("validate") ? 1 : 2)
          || !"--json".equals(args[1])
          || List.of(args).subList(firstFile, args.length).stream()
              .anyMatch(arg -> "--json".equals(arg) || "--require-v2".equals(arg))) {
        error(report, "INVALID_ARGUMENTS", "Invalid command arguments.");
      } else if (command.equals("validate")) {
        validate(report, read(args[firstFile]), requireV2);
      } else {
        diff(report, read(args[firstFile]), read(args[firstFile + 1]), requireV2);
      }
    } catch (InvalidPathException e) {
      error(report, "INVALID_ARGUMENTS", "Invalid command arguments.");
    } catch (InvalidTapeException | IllegalArgumentException e) {
      error(report, "INVALID_TAPE", "Tape is malformed or unsupported.");
    } catch (IOException | SecurityException e) {
      error(report, "IO_ERROR", "Tape could not be read.");
    }
    out.print(Json.write(report));
    out.print('\n');
    return (Integer) report.get("exitCode");
  }

  private static Tape read(String filename) throws IOException {
    return Tape.read(Path.of(filename));
  }

  private static Map<String, Object> base(String command) {
    Map<String, Object> report = new TreeMap<>();
    report.put("reportVersion", 1);
    report.put("tooltapeVersion", Main.VERSION);
    report.put("command", command);
    return report;
  }

  private static Map<String, Object> summary(Tape tape) {
    Map<String, Object> summary = new TreeMap<>();
    summary.put("schemaVersion", tape.version());
    summary.put("eventCount", tape.events().size());
    summary.put(
        "completion", tape.version() == 1 ? "unknown" : tape.complete() ? "closed" : "incomplete");
    summary.put("policyId", tape.metadata() == null ? null : tape.metadata().policyId());
    return summary;
  }

  private static void validate(Map<String, Object> report, Tape tape, boolean requireV2) {
    report.put("summary", summary(tape));
    report.put("warnings", tape.version() == 1 ? List.of("LEGACY_UNVERIFIED") : List.of());
    if (tape.version() == 2 && !tape.complete()) {
      error(report, "INCOMPLETE_TAPE", "Tape has no verified completion marker.");
    } else if (requireV2 && tape.version() == 1) {
      error(report, "LEGACY_TAPE", "Legacy tape rejected by --require-v2.");
    } else {
      report.put("outcome", "valid");
      report.put("exitCode", 0);
    }
  }

  private static void diff(
      Map<String, Object> report, Tape expected, Tape actual, boolean requireV2) {
    report.put("expected", summary(expected));
    report.put("actual", summary(actual));
    Map<String, Object> divergence = firstDivergence(expected, actual);
    report.put("eventsEqual", divergence == null);
    report.put("firstDivergence", divergence);
    boolean legacy = expected.version() == 1 || actual.version() == 1;
    report.put("warnings", legacy ? List.of("LEGACY_UNVERIFIED") : List.of());
    if ((expected.version() == 2 && !expected.complete())
        || (actual.version() == 2 && !actual.complete())) {
      report.put("compatibility", "incomplete");
      error(report, "INCOMPLETE_TAPE", "Tape has no verified completion marker.");
    } else if (!legacy && !expected.metadata().policyId().equals(actual.metadata().policyId())) {
      report.put("compatibility", "policy-mismatch");
      error(report, "POLICY_MISMATCH", "Tapes use different capture policies.");
    } else if (requireV2 && legacy) {
      report.put("compatibility", "unverified");
      error(report, "LEGACY_TAPE", "Legacy tape rejected by --require-v2.");
    } else {
      report.put("compatibility", legacy ? "unverified" : "verified");
      report.put("outcome", divergence == null ? "match" : "divergence");
      report.put("exitCode", divergence == null ? 0 : 1);
    }
  }

  /** Return static field names only: even JSON pointer segments can contain secrets. */
  private static Map<String, Object> firstDivergence(Tape expected, Tape actual) {
    int sharedCount = Math.min(expected.events().size(), actual.events().size());
    for (int i = 0; i < sharedCount; i++) {
      Tape.Event a = expected.events().get(i), b = actual.events().get(i);
      if (!a.tool().equals(b.tool())) return divergence(i, "tool");
      if (!a.input().equals(b.input())) return divergence(i, "input");
      if (!a.status().equals(b.status())) return divergence(i, "status");
      if (!a.result().equals(b.result())) return divergence(i, "result");
    }
    return expected.events().size() == actual.events().size()
        ? null
        : divergence(sharedCount, "eventCount");
  }

  private static Map<String, Object> divergence(int eventIndex, String field) {
    return Map.of("eventIndex", eventIndex, "field", field);
  }

  private static void error(Map<String, Object> report, String code, String message) {
    report.put("outcome", "error");
    report.put("exitCode", 2);
    report.put("error", Map.of("code", code, "message", message));
  }
}
