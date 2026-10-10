package dev.tooltape;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Serial, bounded validation with an independently versioned, payload-free envelope. */
final class BatchReport {
  private BatchReport() {}

  static int run(String[] args, PrintStream out) {
    Map<String, Object> report = new TreeMap<>();
    report.put("batchReportVersion", 1);
    report.put("tooltapeVersion", Main.VERSION);
    report.put("command", "validate-batch");
    boolean requireV2 = args.length > 2 && "--require-v2".equals(args[2]);
    int firstFile = requireV2 ? 3 : 2;
    if (!validArguments(args, firstFile)) {
      error(report, "INVALID_ARGUMENTS", "Invalid command arguments.");
    } else {
      validate(report, args, firstFile, requireV2);
    }
    out.print(Json.write(report));
    out.print('\n');
    return (Integer) report.get("exitCode");
  }

  private static void validate(
      Map<String, Object> report, String[] args, int firstFile, boolean requireV2) {
    List<String> files;
    Path directory = null;
    if ("--manifest".equals(args[firstFile])) {
      try {
        BatchManifest manifest = BatchManifest.read(args[firstFile + 1]);
        files = manifest.files();
        directory = manifest.directory();
      } catch (InvalidPathException e) {
        error(report, "INVALID_ARGUMENTS", "Invalid command arguments.");
        return;
      } catch (IllegalArgumentException e) {
        error(report, "INVALID_MANIFEST", "Manifest is malformed or unsupported.");
        return;
      } catch (IOException | SecurityException e) {
        error(report, "IO_ERROR", "Manifest could not be read.");
        return;
      }
    } else {
      files = Arrays.asList(args).subList(firstFile, args.length);
    }
    List<Map<String, Object>> results = new ArrayList<>();
    int valid = 0;
    for (int i = 0; i < files.size(); i++) {
      // Retain safe reports only, never tape objects or their event payloads.
      Map<String, Object> validation =
          CliReport.validationReport(files.get(i), requireV2, true, directory);
      if ((Integer) validation.get("exitCode") == 0) valid++;
      results.add(Map.of("inputIndex", i, "report", validation));
    }
    int requested = files.size();
    int errors = requested - valid;
    report.put("counts", Map.of("requested", requested, "valid", valid, "error", errors));
    report.put("results", results);
    report.put("outcome", errors == 0 ? "valid" : "error");
    report.put("exitCode", errors == 0 ? 0 : 2);
  }

  private static void error(Map<String, Object> report, String code, String message) {
    report.put("outcome", "error");
    report.put("exitCode", 2);
    report.put("error", Map.of("code", code, "message", message));
  }

  private static boolean validArguments(String[] args, int firstFile) {
    if (args.length < firstFile + 1
        || !"validate-batch".equals(args[0])
        || !"--json".equals(args[1])) return false;
    if ("--manifest".equals(args[firstFile]))
      return args.length == firstFile + 2 && !args[firstFile + 1].startsWith("--");
    if (args.length > firstFile + BatchManifest.MAX_FILES) return false;
    for (int i = firstFile; i < args.length; i++) {
      if (args[i].startsWith("--")) return false;
    }
    return true;
  }
}
