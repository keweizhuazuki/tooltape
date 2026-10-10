package dev.tooltape;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Serial, bounded validation with an independently versioned, payload-free envelope. */
final class BatchReport {
  private static final int MAX_FILES = 64;

  private BatchReport() {}

  static int run(String[] args, PrintStream out) {
    Map<String, Object> report = new TreeMap<>();
    report.put("batchReportVersion", 1);
    report.put("tooltapeVersion", Main.VERSION);
    report.put("command", "validate-batch");
    boolean requireV2 = args.length > 2 && "--require-v2".equals(args[2]);
    int firstFile = requireV2 ? 3 : 2;
    if (!validArguments(args, firstFile)) {
      report.put("outcome", "error");
      report.put("exitCode", 2);
      report.put(
          "error",
          Map.of("code", "INVALID_ARGUMENTS", "message", "Invalid command arguments."));
    } else {
      List<Map<String, Object>> results = new ArrayList<>();
      int valid = 0;
      for (int i = firstFile; i < args.length; i++) {
        // Retain safe reports only, never tape objects or their event payloads.
        Map<String, Object> validation = CliReport.validationReport(args[i], requireV2, true);
        if ((Integer) validation.get("exitCode") == 0) valid++;
        results.add(Map.of("inputIndex", i - firstFile, "report", validation));
      }
      int requested = args.length - firstFile;
      int errors = requested - valid;
      report.put("counts", Map.of("requested", requested, "valid", valid, "error", errors));
      report.put("results", results);
      report.put("outcome", errors == 0 ? "valid" : "error");
      report.put("exitCode", errors == 0 ? 0 : 2);
    }
    out.print(Json.write(report));
    out.print('\n');
    return (Integer) report.get("exitCode");
  }

  private static boolean validArguments(String[] args, int firstFile) {
    if (args.length < firstFile + 1
        || args.length > firstFile + MAX_FILES
        || !"validate-batch".equals(args[0])
        || !"--json".equals(args[1])) return false;
    for (int i = firstFile; i < args.length; i++) {
      if (args[i].startsWith("--")) return false;
    }
    return true;
  }
}
