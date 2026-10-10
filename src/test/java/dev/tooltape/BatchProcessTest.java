package dev.tooltape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Dependency-free, real-process contract tests for bounded, payload-free batch validation. */
public final class BatchProcessTest {
  private static final int MAX_OUTPUT_BYTES = 64 * 1024;
  private static final String INVALID_PATH_TOKEN = "PRIVATE_INVALID_PATH_TOKEN_";
  private static final String FIELD = "PRIVATE_FIELD_/~\"\n";
  private static final String INPUT = "PRIVATE_INPUT_\"\\\n\r\t\u0001\u96ea";
  private static final String OUTPUT = "PRIVATE_OUTPUT_\"\\\n\r\t\u0002\u96ea";
  private static final Set<String> COMMON_KEYS =
      Set.of("tooltapeVersion", "command", "outcome", "exitCode");
  private static final Map<String, String> ERROR_MESSAGES =
      Map.of(
          "INVALID_ARGUMENTS", "Invalid command arguments.",
          "INVALID_TAPE", "Tape is malformed or unsupported.",
          "IO_ERROR", "Tape could not be read.",
          "INCOMPLETE_TAPE", "Tape has no verified completion marker.",
          "LEGACY_TAPE", "Legacy tape rejected by --require-v2.");
  private static int checks;
  private static int processes;
  private static final List<String> optionalChecks = new ArrayList<>();
  private static final List<String> optionalSkips = new ArrayList<>();

  private final Path directory;
  private Fixture closed;
  private Fixture empty;
  private Fixture legacy;
  private Fixture emptyLegacy;
  private Fixture incomplete;
  private Fixture recordedError;
  private Fixture missing;
  private Fixture malformed;
  private Fixture oversized;

  private BatchProcessTest(Path directory) {
    this.directory = directory;
  }

  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("tooltape-batch-PRIVATE_PATH_");
    try {
      BatchProcessTest test = new BatchProcessTest(directory);
      test.prepareFixtures();
      test.validAndMixedBatches();
      test.nestedReportsStayUnchanged();
      test.fileErrorsAndContinuation();
      test.invalidArguments();
      test.invalidPaths();
      test.literalFilenames();
      test.maximumBatch();
      test.nonregularFiles();
      System.out.println(
          "PASS: "
              + checks
              + " batch CLI checks across "
              + processes
              + " processes (bounds, order, exits, schemas, privacy)");
      System.out.println(
          "Optional filesystem checks: " + optionalChecks + "; unavailable: " + optionalSkips);
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private void prepareFixtures() throws Exception {
    CapturePolicy policy = CapturePolicy.allow(Set.of(FIELD), Set.of(FIELD));
    Tape.Metadata metadata = new Tape.Metadata(policy.identifier(), "private.marker.source");
    List<Tape.Event> events =
        List.of(
            new Tape.Event(
                0,
                Long.MAX_VALUE,
                "private.marker.tool",
                Json.write(Map.of(FIELD, INPUT)),
                "OK",
                Json.write(Map.of(FIELD, OUTPUT))),
            new Tape.Event(
                1,
                0,
                "private.marker.otherTool",
                Json.write(Map.of(FIELD, INPUT)),
                "ERROR",
                "private.marker.ExceptionClass"));
    closed = writeTape("closed", new Tape(events, metadata, true));
    empty = writeTape("empty", new Tape(List.of(), metadata, true));
    legacy = writeTape("legacy", new Tape(events));
    emptyLegacy = writeTape("empty_legacy", new Tape(List.of()));
    incomplete = writeTape("incomplete", new Tape(events, metadata, false));

    Path failurePath = directory.resolve("PRIVATE_PATH_recorded_error.tape");
    try (Recorder recorder =
        new Recorder(
            failurePath,
            (name, input) -> {
              throw new IOException("PRIVATE_EXCEPTION_MESSAGE_\"\n\t");
            },
            policy,
            "private.marker.recorder")) {
      try {
        recorder.call("private.marker.tool", Map.of(FIELD, INPUT));
        throw new AssertionError("Synthetic tool must fail");
      } catch (IOException expected) {
        check(expected.getMessage().contains("PRIVATE_EXCEPTION_MESSAGE_"), "Synthetic failure");
      }
    }
    recordedError = new Fixture(failurePath, Tape.read(failurePath), null);
    check(recordedError.tape().complete(), "Recorded error still closes its session");
    equal("ERROR", recordedError.tape().events().get(0).status(), "Recorded error fixture");
    missing = fileError("missing", "IO_ERROR");
    malformed = fileError("malformed", "INVALID_TAPE");
    Files.writeString(malformed.path(), "PRIVATE_INVALID_HEADER_\"\t\n");
    oversized = fileError("oversized", "INVALID_TAPE");
    try (var output = Files.newOutputStream(oversized.path())) {
      byte[] block = new byte[8192];
      for (int remaining = Tape.MAX_BYTES + 1; remaining > 0; ) {
        int count = Math.min(remaining, block.length);
        output.write(block, 0, count);
        remaining -= count;
      }
    }
    equal((long) Tape.MAX_BYTES + 1, Files.size(oversized.path()), "Oversized fixture bound");
  }

  private void validAndMixedBatches() throws Exception {
    for (boolean requireV2 : List.of(false, true)) {
      batch(requireV2, closed);
      batch(requireV2, empty);
      batch(requireV2, recordedError);
      batch(requireV2, legacy);
      batch(requireV2, emptyLegacy);
      batch(requireV2, incomplete);
      batch(requireV2, closed, empty, recordedError, closed);
      batch(requireV2, legacy, incomplete, closed, legacy, emptyLegacy, recordedError);
      // Policies and provenance are independent validation inputs, not a comparison.
      Fixture otherPolicy =
          writeTape(
              "other_policy_" + requireV2,
              new Tape(
                  closed.tape().events(),
                  new Tape.Metadata("sha256:" + "a".repeat(64), "private.marker.otherSource"),
                  true));
      batch(requireV2, closed, otherPolicy, closed);
    }
    Fixture[] supplied = {recordedError, missing, empty, legacy, incomplete, closed, malformed};
    String[] arguments = arguments(false, supplied);
    ProcessResult first = run(arguments);
    verifyBatch(first, false, supplied);
    ProcessResult second = run(arguments);
    verifyBatch(second, false, supplied);
    equal(first, second, "Unchanged inputs produce byte-identical output, stderr and exit status");
    Fixture[] reversed = supplied.clone();
    for (int left = 0, right = reversed.length - 1; left < right; left++, right--) {
      Fixture temporary = reversed[left];
      reversed[left] = reversed[right];
      reversed[right] = temporary;
    }
    batch(false, reversed);
  }

  private void nestedReportsStayUnchanged() throws Exception {
    Fixture[] fixtures = {
      closed, empty, legacy, emptyLegacy, incomplete, recordedError, missing, malformed
    };
    for (boolean requireV2 : List.of(false, true)) {
      List<?> results = array(batch(requireV2, fixtures).get("results"));
      for (int i = 0; i < fixtures.length; i++) {
        List<String> arguments = new ArrayList<>(List.of("validate", "--json"));
        if (requireV2) arguments.add("--require-v2");
        arguments.add(fixtures[i].path().toString());
        ProcessResult standalone = run(arguments.toArray(String[]::new));
        Map<String, Object> report =
            jsonReport(standalone, fixtures[i].errorCode(requireV2) == null ? 0 : 2);
        validation(report, requireV2, fixtures[i]);
        equal(
            report,
            object(results.get(i)).get("report"),
            "Nested report is unchanged standalone validate v1");
      }
    }
  }

  private void fileErrorsAndContinuation() throws Exception {
    Fixture directoryInput = new Fixture(directory, null, "IO_ERROR");
    for (boolean requireV2 : List.of(false, true))
      batch(
          requireV2, missing, closed, malformed, directoryInput, oversized, legacy, recordedError);

    String original = Files.readString(closed.path());
    List<String> contents =
        List.of(
            original.substring(0, original.length() - 1),
            original.replace("END\t2\n", "END\t3\n"),
            "TOOLTAPE\t2\nMETA\t"
                + Tape.encode("{\"PRIVATE_METADATA_KEY_\":\"PRIVATE_METADATA_VALUE_\"}")
                + "\nEND\t0\n",
            "TOOLTAPE\t1\n0\t0\t"
                + Tape.encode("private.marker.tool")
                + "\t"
                + Tape.encode("{\"PRIVATE_INVALID_JSON_KEY_\":}")
                + "\tOK\t"
                + Tape.encode("{}")
                + "\n",
            "TOOLTAPE\t1\n0\t0\t"
                + Tape.encode("private.marker.tool")
                + "\t"
                + Tape.encode("{}")
                + "\tPRIVATE_INVALID_STATUS_\t"
                + Tape.encode("PRIVATE_EXCEPTION_MESSAGE_")
                + "\n");
    Fixture invalid = fileError("invalid_content", "INVALID_TAPE");
    for (String content : contents) {
      Files.writeString(invalid.path(), content);
      batch(false, closed, invalid, recordedError);
    }
    Files.write(invalid.path(), new byte[] {(byte) 0xff, '\n'});
    batch(true, invalid, closed);
  }

  private void invalidArguments() throws Exception {
    String path = closed.path().toString();
    for (String[] arguments :
        List.of(
            new String[] {"validate-batch"},
            new String[] {"validate-batch", path},
            new String[] {"validate-batch", "--require-v2", path},
            new String[] {"validate-batch", "--json"},
            new String[] {"validate-batch", "--json", "--require-v2"},
            new String[] {"validate-batch", "--json", "--json", path},
            new String[] {"validate-batch", "--require-v2", "--json", path},
            new String[] {"validate-batch", path, "--json"},
            new String[] {"validate-batch", "--json", "--require-v2", "--require-v2", path},
            new String[] {"validate-batch", "--json", "--require-v2", "--json", path},
            new String[] {"validate-batch", "--json", path, "--json"},
            new String[] {"validate-batch", "--json", path, "--require-v2"},
            new String[] {"validate-batch", "--json", "--require-v2", path, "--require-v2"},
            new String[] {"validate-batch", "--json", "--PRIVATE_UNKNOWN_OPTION_", path},
            new String[] {"validate-batch", "--json", path, "--PRIVATE_UNKNOWN_OPTION_"},
            new String[] {"validate-batch", "--json", "--", path},
            new String[] {
              "validate-batch", "--json", missing.path().toString(), "--PRIVATE_UNKNOWN_OPTION_"
            },
            new String[] {"validate-batch", "--json", malformed.path().toString(), "--require-v2"}))
      invalidArguments(run(arguments));
    for (boolean requireV2 : List.of(false, true)) {
      Fixture[] tooMany = new Fixture[65];
      Arrays.fill(tooMany, closed);
      invalidArguments(run(arguments(requireV2, tooMany)));
    }
    ProcessResult misplaced = run("--json", "validate-batch", path);
    Map<String, Object> unchanged = jsonReport(misplaced, 2);
    keys(unchanged, "reportVersion", "error");
    number(1, unchanged.get("reportVersion"), "Misplaced command keeps existing v1 error schema");
    equal(null, unchanged.get("command"), "Misplaced command remains unknown");
    error(unchanged, "INVALID_ARGUMENTS");
  }

  private void invalidPaths() throws Exception {
    Fixture invalid = new Fixture(Path.of(INVALID_PATH_TOKEN), null, "INVALID_ARGUMENTS");
    for (boolean requireV2 : List.of(false, true)) {
      Fixture[] fixtures = {closed, invalid, legacy, missing, recordedError};
      verifyBatch(
          runIn(null, InvalidPathMain.class, arguments(requireV2, fixtures)), requireV2, fixtures);
    }
    invalidArguments(
        runIn(
            null,
            InvalidPathMain.class,
            "validate-batch",
            "--json",
            INVALID_PATH_TOKEN,
            "--PRIVATE_UNKNOWN_OPTION_"));
  }

  private void literalFilenames() throws Exception {
    for (String name :
        List.of(
            "PRIVATE_PATH_with spaces \u96ea.tape",
            "--json",
            "--require-v2",
            "--PRIVATE_UNKNOWN_OPTION_",
            "-")) {
      Files.copy(closed.path(), directory.resolve(name));
      Fixture fixture =
          new Fixture(Path.of(name.equals("-") ? name : "./" + name), closed.tape(), null);
      for (boolean requireV2 : List.of(false, true)) {
        String[] arguments = arguments(requireV2, fixture, fixture);
        ProcessResult result = runIn(directory, Main.class, arguments);
        try {
          verifyBatch(result, requireV2, fixture, fixture);
        } catch (AssertionError failure) {
          ProcessResult probe = runIn(directory, ArgumentProbeMain.class, arguments);
          throw new AssertionError(
              "Literal filename "
                  + Json.write(name)
                  + ", requireV2="
                  + requireV2
                  + "; CLI="
                  + result
                  + "; argv probe="
                  + probe,
              failure);
        }
      }
    }
    // An existing match does not cause explicit operands to be glob-expanded.
    Path globPath;
    try {
      globPath = Path.of("PRIVATE_GLOB_*.tape");
    } catch (InvalidPathException unavailable) {
      optionalSkips.add("literal wildcard path (not legal on this platform)");
      return;
    }
    Files.copy(closed.path(), directory.resolve("PRIVATE_GLOB_match.tape"));
    Fixture glob = new Fixture(globPath, null, "IO_ERROR");
    verifyBatch(runIn(directory, Main.class, arguments(false, glob, closed)), false, glob, closed);
  }

  private void maximumBatch() throws Exception {
    Fixture[] maximum = new Fixture[64];
    Arrays.fill(maximum, closed);
    batch(false, maximum);
    Arrays.fill(maximum, legacy);
    batch(true, maximum);
    // Incomplete v2 includes the longest error plus a full summary and policy identifier.
    Arrays.fill(maximum, incomplete);
    for (boolean requireV2 : List.of(false, true)) {
      ProcessResult result = run(arguments(requireV2, maximum));
      verifyBatch(result, requireV2, maximum);
      int bytes = result.stdout().getBytes(StandardCharsets.UTF_8).length;
      check(bytes < MAX_OUTPUT_BYTES, "64 largest-shaped nested reports fit below 64 KiB");
      // Even a ten-digit int event count cannot exhaust the remaining output budget.
      check(bytes + 64 * 9 < MAX_OUTPUT_BYTES, "Output has room for maximal event-count widths");
    }
  }

  private void nonregularFiles() throws Exception {
    Path device = Path.of("/dev/null");
    if (Files.exists(device) && !Files.isRegularFile(device)) {
      batch(false, new Fixture(device, null, "IO_ERROR"), closed);
      optionalChecks.add("character device");
    } else optionalSkips.add("character device");

    Path fifo = createFifo();
    if (fifo != null) {
      Fixture input = new Fixture(fifo, null, "IO_ERROR");
      batch(false, input, closed);
      batch(true, closed, input, recordedError);
      // With no writer, opening this FIFO blocks. Late syntax/count errors must not do I/O.
      invalidArguments(
          run("validate-batch", "--json", fifo.toString(), "--PRIVATE_UNKNOWN_OPTION_"));
      Fixture[] tooMany = new Fixture[65];
      Arrays.fill(tooMany, input);
      invalidArguments(run(arguments(false, tooMany)));
      optionalChecks.add("FIFO");
    } else optionalSkips.add("FIFO");

    Path link = directory.resolve("PRIVATE_symlink");
    try {
      Files.createSymbolicLink(link, closed.path());
    } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
      optionalSkips.add("symbolic links");
      return;
    }
    batch(true, new Fixture(link, closed.tape(), null), recordedError);
    Files.delete(link);
    Files.createSymbolicLink(link, directory);
    batch(false, new Fixture(link, null, "IO_ERROR"), closed);
    Files.delete(link);
    Files.createSymbolicLink(link, missing.path());
    batch(false, new Fixture(link, null, "IO_ERROR"), closed);
    optionalChecks.add("symbolic links");
  }

  private Path createFifo() throws Exception {
    Path executable = Path.of("/usr/bin/mkfifo");
    if (!Files.isExecutable(executable)) executable = Path.of("/bin/mkfifo");
    if (!Files.isExecutable(executable)) return null;
    Path fifo = directory.resolve("PRIVATE_fifo");
    Process process = new ProcessBuilder(executable.toString(), fifo.toString()).start();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
      check(Files.exists(fifo) && !Files.isRegularFile(fifo), "FIFO fixture is nonregular");
      return fifo;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
      process.getInputStream().close();
      process.getErrorStream().close();
      process.getOutputStream().close();
    }
  }

  private Map<String, Object> batch(boolean requireV2, Fixture... fixtures) throws Exception {
    return verifyBatch(run(arguments(requireV2, fixtures)), requireV2, fixtures);
  }

  private Map<String, Object> verifyBatch(
      ProcessResult process, boolean requireV2, Fixture... fixtures) {
    int errors =
        (int)
            Arrays.stream(fixtures).filter(fixture -> fixture.errorCode(requireV2) != null).count();
    Map<String, Object> report = batchReport(process, errors == 0 ? 0 : 2);
    keys(report, "batchReportVersion", "counts", "results");
    Map<String, Object> counts = object(report.get("counts"));
    equal(
        Set.of("requested", "valid", "error"),
        counts.keySet(),
        "Counts expose only documented totals");
    number(
        fixtures.length, counts.get("requested"), "Requested includes duplicates and failed items");
    number(fixtures.length - errors, counts.get("valid"), "Valid count");
    number(errors, counts.get("error"), "Error count");
    List<?> results = array(report.get("results"));
    equal(fixtures.length, results.size(), "Every operand yields exactly one result");
    for (int i = 0; i < fixtures.length; i++) {
      Map<String, Object> result = object(results.get(i));
      equal(
          Set.of("inputIndex", "report"),
          result.keySet(),
          "Items expose only index and nested report");
      number(
          i,
          result.get("inputIndex"),
          "Indexes follow supplied operand order without deduplication");
      validation(object(result.get("report")), requireV2, fixtures[i]);
    }
    return report;
  }

  private Map<String, Object> batchReport(ProcessResult process, int exitCode) {
    Map<String, Object> report = jsonReport(process, exitCode);
    number(1, report.get("batchReportVersion"), "Independent batch schema version");
    equal("validate-batch", report.get("command"), "Batch command identity");
    return report;
  }

  private Map<String, Object> jsonReport(ProcessResult process, int exitCode) {
    equal(exitCode, process.exitCode(), "Actual process exit status");
    equal("", process.stderr(), "JSON-only command leaves stderr empty");
    Map<String, Object> report = Json.object(process.stdout());
    equal(
        Json.write(report) + "\n",
        process.stdout(),
        "Exactly one canonical JSON document and literal newline");
    equal(Main.VERSION, report.get("tooltapeVersion"), "ToolTape version");
    equal(exitCode == 0 ? "valid" : "error", report.get("outcome"), "Reported outcome");
    number(exitCode, report.get("exitCode"), "Reported exit matches actual status");
    for (String privateValue :
        List.of(
            "PRIVATE_",
            "private.marker.",
            directory.toString(),
            "java.io.IOException",
            "InvalidPathException",
            "ExceptionClass",
            "\u96ea"))
      check(
          !process.stdout().contains(privateValue),
          "Reports exclude paths, filenames, payloads, provenance and exceptions");
    return report;
  }

  private void invalidArguments(ProcessResult process) {
    Map<String, Object> report = batchReport(process, 2);
    keys(report, "batchReportVersion", "error");
    error(report, "INVALID_ARGUMENTS");
  }

  private static void validation(Map<String, Object> report, boolean requireV2, Fixture fixture) {
    String code = fixture.errorCode(requireV2);
    List<String> fields = new ArrayList<>(List.of("reportVersion"));
    if (fixture.tape() != null) fields.addAll(List.of("summary", "warnings"));
    if (code != null) fields.add("error");
    keys(report, fields.toArray(String[]::new));
    number(1, report.get("reportVersion"), "Nested validation schema stays v1");
    equal(Main.VERSION, report.get("tooltapeVersion"), "Nested version");
    equal("validate", report.get("command"), "Nested command remains validate");
    equal(code == null ? "valid" : "error", report.get("outcome"), "Nested validation outcome");
    number(code == null ? 0 : 2, report.get("exitCode"), "Nested validation exit status");
    if (code != null) error(report, code);
    Tape tape = fixture.tape();
    if (tape == null) return;
    Map<String, Object> summary = object(report.get("summary"));
    equal(
        Set.of("schemaVersion", "eventCount", "completion", "policyId"),
        summary.keySet(),
        "Summary exposes only safe metadata");
    number(tape.version(), summary.get("schemaVersion"), "Tape version");
    number(tape.events().size(), summary.get("eventCount"), "Tape event count");
    equal(
        tape.version() == 1 ? "unknown" : tape.complete() ? "closed" : "incomplete",
        summary.get("completion"),
        "Tape completion");
    equal(
        tape.metadata() == null ? null : tape.metadata().policyId(),
        summary.get("policyId"),
        "Policy fingerprint");
    equal(
        tape.version() == 1 ? List.of("LEGACY_UNVERIFIED") : List.of(),
        report.get("warnings"),
        "Legacy warnings preserved");
  }

  private static void error(Map<String, Object> report, String code) {
    Map<String, Object> error = object(report.get("error"));
    equal(Set.of("code", "message"), error.keySet(), "Errors expose only fixed code and message");
    equal(code, error.get("code"), "Error code");
    equal(
        ERROR_MESSAGES.get(code),
        error.get("message"),
        "Error message never echoes supplied content");
  }

  private Fixture writeTape(String name, Tape tape) throws IOException {
    StringBuilder content =
        new StringBuilder(tape.version() == 1 ? Tape.HEADER : Tape.HEADER_V2).append('\n');
    if (tape.metadata() != null) content.append(tape.metadata().line()).append('\n');
    for (Tape.Event event : tape.events()) content.append(event.line()).append('\n');
    if (tape.version() == 2 && tape.complete())
      content.append("END\t").append(tape.events().size()).append('\n');
    Path path = directory.resolve("PRIVATE_PATH_" + name + ".tape");
    Files.writeString(path, content);
    return new Fixture(path, tape, null);
  }

  private Fixture fileError(String name, String code) {
    return new Fixture(directory.resolve("PRIVATE_PATH_" + name + ".tape"), null, code);
  }

  private static String[] arguments(boolean requireV2, Fixture... fixtures) {
    List<String> arguments = new ArrayList<>(List.of("validate-batch", "--json"));
    if (requireV2) arguments.add("--require-v2");
    for (Fixture fixture : fixtures) arguments.add(fixture.path().toString());
    return arguments.toArray(String[]::new);
  }

  private static ProcessResult run(String... args) throws Exception {
    return runIn(null, Main.class, args);
  }

  private static ProcessResult runIn(Path workingDirectory, Class<?> entrypoint, String... args)
      throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    if (!Files.isRegularFile(java)) java = java.resolveSibling("java.exe");
    String separator = System.getProperty("path.separator");
    String classpath =
        String.join(
            separator,
            Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(separator), -1))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .toList());
    List<String> command =
        new ArrayList<>(
            List.of(
                java.toString(),
                "-XX:-UsePerfData",
                "-Dfile.encoding=UTF-8",
                "-cp",
                classpath,
                entrypoint.getName()));
    command.addAll(Arrays.asList(args));
    ProcessBuilder builder = new ProcessBuilder(command);
    if (workingDirectory != null) builder.directory(workingDirectory.toFile());
    Process process = builder.start();
    processes++;
    ExecutorService readers =
        Executors.newFixedThreadPool(
            2,
            task -> {
              Thread thread = new Thread(task, "tooltape-batch-output");
              thread.setDaemon(true);
              return thread;
            });
    try {
      Future<String> stdout = readers.submit(() -> readOutput(process.getInputStream()));
      Future<String> stderr = readers.submit(() -> readOutput(process.getErrorStream()));
      check(process.waitFor(30, TimeUnit.SECONDS), "Batch process must finish within 30 seconds");
      return new ProcessResult(
          process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      process.getInputStream().close();
      process.getErrorStream().close();
      process.getOutputStream().close();
      readers.shutdownNow();
    }
  }

  private static String readOutput(InputStream stream) throws IOException {
    try (stream;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[4096];
      int count;
      while ((count = stream.read(bytes)) != -1) {
        if (count > MAX_OUTPUT_BYTES - buffer.size())
          throw new IOException("Batch output exceeds test limit");
        buffer.write(bytes, 0, count);
      }
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }

  private static void keys(Map<String, Object> report, String... extra) {
    Set<String> expected = new HashSet<>(COMMON_KEYS);
    expected.addAll(Arrays.asList(extra));
    equal(expected, report.keySet(), "Report has exactly the documented fields");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    check(value instanceof Map<?, ?>, "Expected JSON object");
    return (Map<String, Object>) value;
  }

  private static List<?> array(Object value) {
    check(value instanceof List<?>, "Expected JSON array");
    return (List<?>) value;
  }

  private static void number(int expected, Object actual, String message) {
    equal(Json.read(Integer.toString(expected)), actual, message);
  }

  private static void equal(Object expected, Object actual, String message) {
    check(
        Objects.equals(expected, actual),
        message + ": expected " + expected + ", actual " + actual);
  }

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  private record Fixture(Path path, Tape tape, String failure) {
    String errorCode(boolean requireV2) {
      if (failure != null) return failure;
      if (tape.version() == 2 && !tape.complete()) return "INCOMPLETE_TAPE";
      return requireV2 && tape.version() == 1 ? "LEGACY_TAPE" : null;
    }
  }

  private record ProcessResult(int exitCode, String stdout, String stderr) {}

  /** Report synthetic test argument code points to diagnose native launcher encoding failures. */
  public static final class ArgumentProbeMain {
    public static void main(String[] args) {
      System.out.print(
          Json.write(
              Map.of(
                  "argumentCodePoints",
                  Arrays.stream(args).map(value -> value.codePoints().boxed().toList()).toList(),
                  "nativeEncoding",
                  System.getProperty("native.encoding", ""),
                  "fileEncoding",
                  System.getProperty("file.encoding", ""))));
      System.out.print('\n');
    }
  }

  /** The OS cannot carry NUL in argv; inject it inside a real child JVM before invoking the CLI. */
  public static final class InvalidPathMain {
    public static void main(String[] args) {
      for (int i = 0; i < args.length; i++)
        if (INVALID_PATH_TOKEN.equals(args[i])) args[i] = "PRIVATE_INVALID_PATH_\u0000\"\n";
      Main.main(args);
    }
  }
}
