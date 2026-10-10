package dev.tooltape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/** Dependency-free acceptance tests of the actual CLI process, including its exit status. */
public final class CliProcessTest {
  private static final String TOOL = "private.marker.tool";
  private static final String SOURCE = "private.marker.source";
  private static final String FIELD = "PRIVATE_FIELD_/~\"\n";
  private static final String INPUT = "PRIVATE_INPUT_\"\\\n\r\t\u0001雪";
  private static final String OUTPUT = "PRIVATE_OUTPUT_\"\\\n\r\t\u0002雪";
  private static final String EXCEPTION = "private.marker.ExceptionClass";
  private static final Set<String> COMMON_KEYS =
      Set.of("reportVersion", "tooltapeVersion", "command", "outcome", "exitCode");
  private static final Map<String, String> ERROR_MESSAGES =
      Map.of(
          "INVALID_ARGUMENTS", "Invalid command arguments.",
          "INVALID_TAPE", "Tape is malformed or unsupported.",
          "IO_ERROR", "Tape could not be read.",
          "INCOMPLETE_TAPE", "Tape has no verified completion marker.",
          "POLICY_MISMATCH", "Tapes use different capture policies.",
          "LEGACY_TAPE", "Legacy tape rejected by --require-v2.");
  private static final int MAX_OUTPUT_BYTES = 64 * 1024;
  private static int checks;
  private static int processes;
  private static int directRuns;

  private final Path directory;
  private final CapturePolicy policy = CapturePolicy.allow(Set.of(FIELD), Set.of(FIELD));
  private Tape tape;
  private Path closed;
  private Path legacy;
  private Path incomplete;

  private CliProcessTest(Path directory) {
    this.directory = directory;
  }

  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("tooltape-cli-PRIVATE_PATH_");
    try {
      CliProcessTest test = new CliProcessTest(directory);
      test.prepareFixtures();
      test.validationReports();
      test.semanticDiffReports();
      test.compatibilityReports();
      test.invalidFiles();
      test.invalidArguments();
      test.hostileArguments();
      test.humanCommands();
      test.flagNamedFiles();
      System.out.println(
          "PASS: "
              + checks
              + " CLI checks across "
              + processes
              + " processes and "
              + directRuns
              + " direct invocations (JSON, require-v2, exits, compatibility, privacy, errors)");
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private void prepareFixtures() throws Exception {
    closed = directory.resolve("PRIVATE_PATH_closed.tape");
    int[] calls = {0};
    try (Recorder recorder =
        new Recorder(
            closed,
            (name, input) -> {
              calls[0]++;
              return Map.of(FIELD, OUTPUT);
            },
            policy,
            SOURCE)) {
      recorder.call(TOOL, Map.of(FIELD, INPUT));
      recorder.call(TOOL, Map.of(FIELD, INPUT));
    }
    equal(2, calls[0], "Fixture uses only two synthetic delegate calls");
    tape = Tape.read(closed);
    legacy = writeTape("legacy", new Tape(tape.events()));
    incomplete = writeTape("incomplete", new Tape(tape.events(), tape.metadata(), false));
    check(tape.events().get(0).input().contains("PRIVATE_INPUT_"), "Sensitive input is captured");
    check(
        tape.events().get(0).result().contains("PRIVATE_OUTPUT_"), "Sensitive result is captured");
  }

  private void validationReports() throws Exception {
    Map<String, Object> valid =
        report(0, "validate", "valid", "validate", "--json", closed.toString());
    keys(valid, "summary", "warnings");
    summary(valid.get("summary"), tape);
    equal(List.of(), valid.get("warnings"), "Closed validation has no warnings");

    Map<String, Object> old =
        report(0, "validate", "valid", "validate", "--json", legacy.toString());
    keys(old, "summary", "warnings");
    summary(old.get("summary"), new Tape(tape.events()));
    equal(List.of("LEGACY_UNVERIFIED"), old.get("warnings"), "Legacy validation warns");

    Map<String, Object> unfinished =
        report(2, "validate", "error", "validate", "--json", incomplete.toString());
    keys(unfinished, "summary", "warnings", "error");
    summary(unfinished.get("summary"), new Tape(tape.events(), tape.metadata(), false));
    equal(List.of(), unfinished.get("warnings"), "Incomplete v2 does not become legacy");
    error(unfinished, "INCOMPLETE_TAPE");

    Tape empty = new Tape(List.of(), tape.metadata(), true);
    Path emptyPath = writeTape("empty", empty);
    Map<String, Object> emptyReport =
        report(0, "validate", "valid", "validate", "--json", emptyPath.toString());
    summary(emptyReport.get("summary"), empty);

    Path exceptionPath = directory.resolve("PRIVATE_PATH_exception.tape");
    try (Recorder recorder =
        new Recorder(
            exceptionPath,
            (name, input) -> {
              throw new IOException("PRIVATE_EXCEPTION_MESSAGE_\"\n\t");
            },
            policy,
            SOURCE)) {
      try {
        recorder.call(TOOL, Map.of(FIELD, INPUT));
        throw new AssertionError("Synthetic tool should fail");
      } catch (IOException expected) {
        check(expected.getMessage().contains("PRIVATE_EXCEPTION_MESSAGE_"), "Synthetic failure");
      }
    }
    Map<String, Object> recordedError =
        report(0, "validate", "valid", "validate", "--json", exceptionPath.toString());
    summary(recordedError.get("summary"), Tape.read(exceptionPath));

    equal(valid, requireV2Validation(closed, tape), "Closed v2 report remains unchanged");
    equal(emptyReport, requireV2Validation(emptyPath, empty), "Empty closed v2 remains valid");
    equal(
        recordedError,
        requireV2Validation(exceptionPath, Tape.read(exceptionPath)),
        "Recorded errors do not make a closed session invalid");
    equal(
        unfinished,
        requireV2Validation(incomplete, Tape.read(incomplete)),
        "Incomplete v2 report remains unchanged");
    requireV2Validation(legacy, new Tape(tape.events()));
    Tape emptyLegacy = new Tape(List.of());
    requireV2Validation(writeTape("emptyLegacy", emptyLegacy), emptyLegacy);

    // This gate verifies format/session evidence, not a fingerprint's capture algorithm or trust.
    Tape unknownPolicy =
        new Tape(tape.events(), new Tape.Metadata("sha256:" + "a".repeat(64), SOURCE), true);
    Path unknownPolicyPath = writeTape("unknownPolicy", unknownPolicy);
    Map<String, Object> unknownPolicyReport =
        report(0, "validate", "valid", "validate", "--json", unknownPolicyPath.toString());
    equal(
        unknownPolicyReport,
        requireV2Validation(unknownPolicyPath, unknownPolicy),
        "Requiring v2 does not establish whether a capture policy is current or trusted");
    diff(
        unknownPolicyPath,
        unknownPolicy,
        unknownPolicyPath,
        unknownPolicy,
        0,
        "verified",
        null,
        -1);
  }

  private Map<String, Object> requireV2Validation(Path path, Tape value) throws Exception {
    boolean legacy = value.version() == 1;
    int exitCode = legacy || !value.complete() ? 2 : 0;
    Map<String, Object> report =
        report(
            exitCode,
            "validate",
            exitCode == 0 ? "valid" : "error",
            "validate",
            "--json",
            "--require-v2",
            path.toString());
    if (exitCode == 2) keys(report, "summary", "warnings", "error");
    else keys(report, "summary", "warnings");
    summary(report.get("summary"), value);
    equal(
        legacy ? List.of("LEGACY_UNVERIFIED") : List.of(),
        report.get("warnings"),
        "Required-v2 validation preserves legacy warnings");
    if (exitCode == 2) error(report, legacy ? "LEGACY_TAPE" : "INCOMPLETE_TAPE");
    return report;
  }

  private void semanticDiffReports() throws Exception {
    diff(closed, tape, closed, tape, 0, "verified", null, -1);
    Tape timing =
        new Tape(
            tape.events().stream()
                .map(
                    e ->
                        new Tape.Event(
                            e.index(), Long.MAX_VALUE, e.tool(), e.input(), e.status(), e.result()))
                .toList(),
            new Tape.Metadata(policy.identifier(), "private.marker.otherSource"),
            true);
    diff(closed, tape, writeTape("timing", timing), timing, 0, "verified", null, -1);

    // Every event field differs here: report only the earliest field in the established order.
    Tape tool =
        replace(1, "private.marker.otherTool", json("PRIVATE_CHANGED_INPUT_"), "ERROR", EXCEPTION);
    diff(closed, tape, writeTape("tool", tool), tool, 1, "verified", "tool", 1);
    Tape input = replace(1, TOOL, json("PRIVATE_CHANGED_INPUT_"), "ERROR", EXCEPTION);
    diff(closed, tape, writeTape("input", input), input, 1, "verified", "input", 1);
    Tape status = replace(1, TOOL, tape.events().get(1).input(), "ERROR", EXCEPTION);
    diff(closed, tape, writeTape("status", status), status, 1, "verified", "status", 1);
    Tape result =
        replace(1, TOOL, tape.events().get(1).input(), "OK", json("PRIVATE_CHANGED_OUTPUT_"));
    diff(closed, tape, writeTape("result", result), result, 1, "verified", "result", 1);

    Tape anotherError =
        new Tape(
            List.of(
                status.events().get(0),
                new Tape.Event(
                    1,
                    0,
                    TOOL,
                    status.events().get(1).input(),
                    "ERROR",
                    "private.marker.OtherExceptionClass")),
            tape.metadata(),
            true);
    diff(
        writeTape("errorExpected", status),
        status,
        writeTape("errorActual", anotherError),
        anotherError,
        1,
        "verified",
        "result",
        1);

    Tape shorter = new Tape(tape.events().subList(0, 1), tape.metadata(), true);
    Path shorterPath = writeTape("shorter", shorter);
    diff(closed, tape, shorterPath, shorter, 1, "verified", "eventCount", 1);
    diff(shorterPath, shorter, closed, tape, 1, "verified", "eventCount", 1);

    Tape empty = new Tape(List.of(), tape.metadata(), true);
    diff(closed, tape, writeTape("emptyDiff", empty), empty, 1, "verified", "eventCount", 0);

    // A difference in an earlier event takes priority over later fields and a length change.
    Tape firstResult =
        replace(0, TOOL, tape.events().get(0).input(), "OK", json("PRIVATE_FIRST_OUTPUT_"));
    Tape earlier =
        new Tape(List.of(firstResult.events().get(0), tool.events().get(1)), tape.metadata(), true);
    diff(closed, tape, writeTape("earlier", earlier), earlier, 1, "verified", "result", 0);
    Tape shortDivergent = new Tape(List.of(firstResult.events().get(0)), tape.metadata(), true);
    diff(
        closed,
        tape,
        writeTape("shortDivergent", shortDivergent),
        shortDivergent,
        1,
        "verified",
        "result",
        0);
  }

  private void compatibilityReports() throws Exception {
    Tape old = new Tape(tape.events());
    diff(legacy, old, closed, tape, 0, "unverified", null, -1);
    diff(closed, tape, legacy, old, 0, "unverified", null, -1);
    diff(legacy, old, legacy, old, 0, "unverified", null, -1);
    Tape changed =
        replace(1, TOOL, json("PRIVATE_DIFFERENT_INPUT_"), "OK", tape.events().get(1).result());
    Tape oldChanged = new Tape(changed.events());
    diff(
        legacy,
        old,
        writeTape("legacyChanged", oldChanged),
        oldChanged,
        1,
        "unverified",
        "input",
        1);
    diff(legacy, old, writeTape("mixedChanged", changed), changed, 1, "unverified", "input", 1);
    diff(
        closed,
        tape,
        writeTape("mixedLegacyChanged", oldChanged),
        oldChanged,
        1,
        "unverified",
        "input",
        1);

    Tape.Metadata otherPolicy = new Tape.Metadata(CapturePolicy.safe().identifier(), SOURCE);
    Tape mismatch = new Tape(tape.events(), otherPolicy, true);
    Map<String, Object> equalMismatch =
        diff(
            closed,
            tape,
            writeTape("policyMismatch", mismatch),
            mismatch,
            2,
            "policy-mismatch",
            null,
            -1);
    error(equalMismatch, "POLICY_MISMATCH");
    Tape divergentMismatch = new Tape(changed.events(), otherPolicy, true);
    Map<String, Object> differentMismatch =
        diff(
            closed,
            tape,
            writeTape("divergentPolicyMismatch", divergentMismatch),
            divergentMismatch,
            2,
            "policy-mismatch",
            "input",
            1);
    error(differentMismatch, "POLICY_MISMATCH");

    Tape unfinished = new Tape(tape.events(), tape.metadata(), false);
    error(diff(closed, tape, incomplete, unfinished, 2, "incomplete", null, -1), "INCOMPLETE_TAPE");
    error(diff(incomplete, unfinished, closed, tape, 2, "incomplete", null, -1), "INCOMPLETE_TAPE");
    error(
        diff(incomplete, unfinished, incomplete, unfinished, 2, "incomplete", null, -1),
        "INCOMPLETE_TAPE");
    error(diff(legacy, old, incomplete, unfinished, 2, "incomplete", null, -1), "INCOMPLETE_TAPE");
    error(diff(incomplete, unfinished, legacy, old, 2, "incomplete", null, -1), "INCOMPLETE_TAPE");
    Tape unfinishedMismatch = new Tape(changed.events(), otherPolicy, false);
    error(
        diff(
            closed,
            tape,
            writeTape("unfinishedMismatch", unfinishedMismatch),
            unfinishedMismatch,
            2,
            "incomplete",
            "input",
            1),
        "INCOMPLETE_TAPE");
  }

  private void invalidFiles() throws Exception {
    Path missing = directory.resolve("PRIVATE_PATH_missing.tape");
    fileError("IO_ERROR", "validate", "--json", missing.toString());
    fileError("IO_ERROR", "diff", "--json", missing.toString(), closed.toString());
    fileError("IO_ERROR", "diff", "--json", closed.toString(), missing.toString());
    fileError("IO_ERROR", "diff", "--json", legacy.toString(), missing.toString());
    fileError("IO_ERROR", "diff", "--json", missing.toString(), legacy.toString());
    fileError("IO_ERROR", "validate", "--json", directory.toString());
    fileError("IO_ERROR", "diff", "--json", directory.toString(), closed.toString());
    fileError("IO_ERROR", "diff", "--json", closed.toString(), directory.toString());

    String original = Files.readString(closed);
    List<String> malformed =
        List.of(
            "PRIVATE_INVALID_HEADER_\"\t\n",
            original.substring(0, original.length() - 1),
            original.replace("END\t2\n", "END\t3\n"),
            "TOOLTAPE\t2\nMETA\t"
                + Tape.encode("{\"PRIVATE_METADATA_KEY_\":\"PRIVATE_METADATA_VALUE_\"}")
                + "\nEND\t0\n",
            "TOOLTAPE\t1\n0\t0\t"
                + Tape.encode(TOOL)
                + "\t"
                + Tape.encode("{\"PRIVATE_INVALID_JSON_KEY_\":}")
                + "\tOK\t"
                + Tape.encode("{}")
                + "\n",
            "TOOLTAPE\t1\n0\t0\t"
                + Tape.encode(TOOL)
                + "\t"
                + Tape.encode("{}")
                + "\tPRIVATE_INVALID_STATUS_\t"
                + Tape.encode("PRIVATE_EXCEPTION_MESSAGE_")
                + "\n");
    Path malformedPath = directory.resolve("PRIVATE_PATH_malformed.tape");
    for (String content : malformed) {
      Files.writeString(malformedPath, content);
      fileError("INVALID_TAPE", "validate", "--json", malformedPath.toString());
    }
    fileError("INVALID_TAPE", "diff", "--json", malformedPath.toString(), closed.toString());
    fileError("INVALID_TAPE", "diff", "--json", closed.toString(), malformedPath.toString());
    fileError("INVALID_TAPE", "diff", "--json", legacy.toString(), malformedPath.toString());
    fileError("INVALID_TAPE", "diff", "--json", malformedPath.toString(), legacy.toString());
    Files.write(malformedPath, new byte[] {(byte) 0xff, '\n'});
    fileError("INVALID_TAPE", "validate", "--json", malformedPath.toString());
  }

  private void invalidArguments() throws Exception {
    for (String[] arguments :
        List.of(
            new String[] {"validate", "--json"},
            new String[] {"validate", "--json", closed.toString(), "PRIVATE_EXTRA_ARGUMENT_"},
            new String[] {"validate", closed.toString(), "--json"},
            new String[] {"validate", "--json", "--json"},
            new String[] {"diff", "--json"},
            new String[] {"diff", "--json", closed.toString()},
            new String[] {"diff", "--json", "--json", closed.toString()},
            new String[] {
              "diff", "--json", closed.toString(), closed.toString(), "PRIVATE_EXTRA_ARGUMENT_"
            },
            new String[] {"diff", closed.toString(), "--json", closed.toString()},
            new String[] {"diff", closed.toString(), closed.toString(), "--json"},
            new String[] {"inspect", "--json", closed.toString()},
            new String[] {"--json", "validate", closed.toString()},
            new String[] {"PRIVATE_UNKNOWN_COMMAND_", "--json"},
            new String[] {"--json"},
            new String[] {"validate", "--json", "--require-v2"},
            new String[] {"validate", "--json", "--require-v2", "--require-v2"},
            new String[] {"validate", "--json", "--require-v2", "--json"},
            new String[] {"validate", "--require-v2", "--json", closed.toString()},
            new String[] {"validate", "--json", closed.toString(), "--require-v2"},
            new String[] {"validate", "--json", "--require-v2", closed.toString(), "--require-v2"},
            new String[] {
              "validate", "--json", "--require-v2", closed.toString(), "PRIVATE_EXTRA_ARGUMENT_"
            },
            new String[] {"diff", "--json", "--require-v2"},
            new String[] {"diff", "--json", "--require-v2", closed.toString()},
            new String[] {"diff", "--json", "--require-v2", "--require-v2", closed.toString()},
            new String[] {"diff", "--json", "--require-v2", closed.toString(), "--require-v2"},
            new String[] {"diff", "--json", "--require-v2", "--json", closed.toString()},
            new String[] {"diff", "--json", "--require-v2", closed.toString(), "--json"},
            new String[] {"diff", "--json", closed.toString(), "--require-v2"},
            new String[] {"diff", "--require-v2", "--json", closed.toString(), closed.toString()},
            new String[] {"diff", "--json", closed.toString(), "--require-v2", closed.toString()},
            new String[] {"diff", "--json", closed.toString(), closed.toString(), "--require-v2"},
            new String[] {
              "diff",
              "--json",
              "--require-v2",
              closed.toString(),
              closed.toString(),
              "PRIVATE_EXTRA_ARGUMENT_"
            },
            new String[] {"inspect", "--json", "--require-v2", closed.toString()},
            new String[] {"--require-v2", "validate", "--json", closed.toString()},
            new String[] {"--json", "--require-v2", "validate", closed.toString()},
            new String[] {"PRIVATE_UNKNOWN_COMMAND_", "--json", "--require-v2"},
            new String[] {"--help", "--json", "--require-v2"})) {
      String command = Set.of("validate", "diff").contains(arguments[0]) ? arguments[0] : null;
      Map<String, Object> report = report(2, command, "error", arguments);
      keys(report, "error");
      error(report, "INVALID_ARGUMENTS");
    }
  }

  private void hostileArguments() {
    // Quotes and control characters have OS-dependent command-line reconstruction rules.
    // Inject these exact arguments without an OS quoting layer; portable misuse still runs
    // through real subprocesses above, and hostile tape contents do so throughout this suite.
    for (String[] arguments :
        List.of(
            new String[] {"PRIVATE_UNKNOWN_COMMAND_\"\n", "--json"},
            new String[] {"PRIVATE_UNKNOWN_COMMAND_\"\\\n\r\t\u0001", "--json"},
            new String[] {"validate", "--json", "PRIVATE_INVALID_PATH_\u0000\"\n"},
            new String[] {"validate", "--json", "--require-v2", "PRIVATE_INVALID_PATH_\u0000\"\n"},
            new String[] {
              "diff", "--json", "--require-v2", legacy.toString(), "PRIVATE_INVALID_PATH_\u0000\"\n"
            },
            new String[] {
              "diff", "--json", "--require-v2", "PRIVATE_INVALID_PATH_\u0000\"\n", legacy.toString()
            },
            new String[] {
              "validate", "--json", closed.toString(), "PRIVATE_EXTRA_ARGUMENT_\"\\\n\r\t"
            },
            new String[] {
              "validate",
              "--json",
              "--require-v2",
              closed.toString(),
              "PRIVATE_EXTRA_ARGUMENT_\"\\\n\r\t"
            })) {
      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      int exitCode;
      try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
          PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
        exitCode = Main.run(arguments, out, err);
        directRuns++;
      }
      String command = Set.of("validate", "diff").contains(arguments[0]) ? arguments[0] : null;
      Map<String, Object> report =
          checkReport(
              new ProcessResult(
                  exitCode,
                  stdout.toString(StandardCharsets.UTF_8),
                  stderr.toString(StandardCharsets.UTF_8)),
              2,
              command,
              "error",
              arguments);
      keys(report, "error");
      error(report, "INVALID_ARGUMENTS");
    }
  }

  private void humanCommands() throws Exception {
    ProcessResult version = run("--version");
    equal(0, version.exitCode(), "Human version exits successfully");
    check(version.stdout().contains("ToolTape " + Main.VERSION), "Human version text preserved");
    equal("", version.stderr(), "Human version has no diagnostics");
    ProcessResult validation = run("validate", closed.toString());
    equal(0, validation.exitCode(), "Human validation exit code");
    check(validation.stdout().startsWith("VALID:"), "Human validation text preserved");
    ProcessResult oldValidation = run("validate", legacy.toString());
    equal(0, oldValidation.exitCode(), "Human legacy validation remains accepted");
    check(
        oldValidation.stdout().contains("legacy policy/completion unverified"),
        "Human legacy validation retains its warning");
    ProcessResult oldMatch = run("diff", legacy.toString(), closed.toString());
    equal(0, oldMatch.exitCode(), "Human legacy comparison remains accepted");
    check(oldMatch.stdout().startsWith("MATCH:"), "Human legacy match text preserved");
    ProcessResult match = run("diff", closed.toString(), closed.toString());
    equal(0, match.exitCode(), "Human diff match exit code");
    check(match.stdout().startsWith("MATCH:"), "Human diff text preserved");
    ProcessResult divergence =
        run("diff", closed.toString(), directory.resolve("PRIVATE_PATH_result.tape").toString());
    equal(1, divergence.exitCode(), "Human divergence has a real nonzero exit code");
    check(divergence.stdout().contains("Event 1 differs:"), "Human divergence text preserved");
    ProcessResult usage = run("PRIVATE_UNKNOWN_COMMAND_");
    equal(2, usage.exitCode(), "Human misuse exit code");
    check(usage.stderr().contains("Usage:"), "Human usage remains on stderr");

    for (String[] arguments :
        List.of(
            new String[] {"--help"},
            new String[] {"--require-v2"},
            new String[] {"validate", "--require-v2"},
            new String[] {"validate", "--require-v2", legacy.toString()},
            new String[] {"validate", legacy.toString(), "--require-v2"},
            new String[] {"diff", "--require-v2", closed.toString()},
            new String[] {"diff", "--require-v2", legacy.toString(), closed.toString()},
            new String[] {"diff", legacy.toString(), "--require-v2"},
            new String[] {"inspect", "--require-v2"},
            new String[] {"inspect", "--require-v2", closed.toString()})) {
      ProcessResult invalid = run(arguments);
      equal(2, invalid.exitCode(), "Human flag misuse and existing help use exit 2");
      equal("", invalid.stdout(), "Human usage leaves stdout empty");
      equal(usage.stderr(), invalid.stderr(), "Usage is fixed and does not echo arguments");
      check(
          invalid.stderr().contains("validate --json [--require-v2] <file>"),
          "Usage documents the validation flag position");
      check(
          invalid.stderr().contains("diff --json [--require-v2] <expected> <actual>"),
          "Usage documents the comparison flag position");
    }
  }

  private void flagNamedFiles() throws Exception {
    Files.copy(closed, directory.resolve("--require-v2"));
    String filename = "./--require-v2";
    ProcessResult validation = runIn(directory, "validate", filename);
    equal(0, validation.exitCode(), "Explicit relative path is not a human-mode flag");
    check(validation.stdout().startsWith("VALID:"), "Flag-named file is validated");
    equal("", validation.stderr(), "Flag-named file produces no diagnostics");
    for (boolean requireV2 : List.of(false, true)) {
      String[] arguments = {"validate", "--json", filename};
      if (requireV2) arguments = withRequireV2(arguments);
      Map<String, Object> report =
          checkReport(runIn(directory, arguments), 0, "validate", "valid", arguments);
      keys(report, "summary", "warnings");
      summary(report.get("summary"), tape);
      equal(List.of(), report.get("warnings"), "Flag-named v2 file has no warnings");

      arguments = new String[] {"diff", "--json", filename, filename};
      if (requireV2) arguments = withRequireV2(arguments);
      report = checkReport(runIn(directory, arguments), 0, "diff", "match", arguments);
      keys(
          report,
          "expected",
          "actual",
          "compatibility",
          "eventsEqual",
          "firstDivergence",
          "warnings");
      summary(report.get("expected"), tape);
      summary(report.get("actual"), tape);
      equal("verified", report.get("compatibility"), "Flag-named files remain comparable");
      equal(true, report.get("eventsEqual"), "Flag-named files have equal events");
      equal(null, report.get("firstDivergence"), "Flag-named files have no divergence");
      equal(List.of(), report.get("warnings"), "Flag-named v2 comparison has no warnings");
    }
  }

  private Map<String, Object> diff(
      Path expectedPath,
      Tape expected,
      Path actualPath,
      Tape actual,
      int exitCode,
      String compatibility,
      String field,
      int eventIndex)
      throws Exception {
    Map<String, Object> report =
        diff(
            false,
            expectedPath,
            expected,
            actualPath,
            actual,
            exitCode,
            compatibility,
            field,
            eventIndex);
    Map<String, Object> required =
        diff(
            true,
            expectedPath,
            expected,
            actualPath,
            actual,
            exitCode,
            compatibility,
            field,
            eventIndex);
    if (!"unverified".equals(compatibility))
      equal(report, required, "Requiring v2 preserves reports without an eligible legacy input");
    return report;
  }

  private Map<String, Object> diff(
      boolean requireV2,
      Path expectedPath,
      Tape expected,
      Path actualPath,
      Tape actual,
      int exitCode,
      String compatibility,
      String field,
      int eventIndex)
      throws Exception {
    boolean rejectLegacy = requireV2 && "unverified".equals(compatibility);
    if (rejectLegacy) exitCode = 2;
    String outcome = exitCode == 2 ? "error" : field == null ? "match" : "divergence";
    String[] arguments = {"diff", "--json", expectedPath.toString(), actualPath.toString()};
    if (requireV2) arguments = withRequireV2(arguments);
    Map<String, Object> report = report(exitCode, "diff", outcome, arguments);
    List<String> fields =
        new ArrayList<>(
            List.of(
                "expected",
                "actual",
                "compatibility",
                "eventsEqual",
                "firstDivergence",
                "warnings"));
    if (exitCode == 2) fields.add("error");
    keys(report, fields.toArray(String[]::new));
    summary(report.get("expected"), expected);
    summary(report.get("actual"), actual);
    equal(compatibility, report.get("compatibility"), "Diff compatibility");
    equal(
        field == null, report.get("eventsEqual"), "Event equality is independent of compatibility");
    if (field == null)
      equal(null, report.get("firstDivergence"), "Equal events have no divergence");
    else {
      Map<String, Object> first = object(report.get("firstDivergence"));
      equal(
          Set.of("eventIndex", "field"),
          first.keySet(),
          "Divergence exposes no JSON pointers or values");
      number(eventIndex, first.get("eventIndex"), "First divergent event");
      equal(field, first.get("field"), "First divergent field");
    }
    equal(
        expected.version() == 1 || actual.version() == 1 ? List.of("LEGACY_UNVERIFIED") : List.of(),
        report.get("warnings"),
        "Diff legacy warning");
    if (rejectLegacy) error(report, "LEGACY_TAPE");
    return report;
  }

  private void fileError(String code, String... args) throws Exception {
    Map<String, Object> report = report(2, args[0], "error", args);
    keys(report, "error");
    error(report, code);
    Map<String, Object> required = report(2, args[0], "error", withRequireV2(args));
    equal(report, required, "Requiring v2 preserves file-error precedence and report shape");
  }

  private static String[] withRequireV2(String... args) {
    List<String> required = new ArrayList<>(Arrays.asList(args));
    required.add(2, "--require-v2");
    return required.toArray(String[]::new);
  }

  private void error(Map<String, Object> report, String code) {
    Map<String, Object> error = object(report.get("error"));
    equal(Set.of("code", "message"), error.keySet(), "Errors expose only a fixed code and message");
    equal(code, error.get("code"), "Error code");
    equal(
        ERROR_MESSAGES.get(code),
        error.get("message"),
        "Error message never depends on supplied content");
  }

  private Map<String, Object> report(int exitCode, String command, String outcome, String... args)
      throws Exception {
    return checkReport(run(args), exitCode, command, outcome, args);
  }

  private Map<String, Object> checkReport(
      ProcessResult process, int exitCode, String command, String outcome, String... args) {
    equal(exitCode, process.exitCode(), "Exit status for " + Arrays.toString(args));
    equal("", process.stderr(), "JSON mode leaves stderr empty");
    Map<String, Object> report = Json.object(process.stdout());
    equal(
        Json.write(report) + "\n",
        process.stdout(),
        "Exactly one canonical JSON document and literal newline");
    number(1, report.get("reportVersion"), "Report schema version");
    equal(Main.VERSION, report.get("tooltapeVersion"), "ToolTape version");
    equal(command, report.get("command"), "Reported command");
    equal(outcome, report.get("outcome"), "Reported outcome");
    number(exitCode, report.get("exitCode"), "Reported exit matches actual status");
    for (String privateValue :
        List.of("PRIVATE_", "private.marker.", directory.toString(), "java.io.IOException"))
      check(
          !process.stdout().contains(privateValue),
          "Report excludes captured data, file paths and exception details");
    return report;
  }

  private static void summary(Object value, Tape tape) {
    Map<String, Object> summary = object(value);
    equal(
        Set.of("schemaVersion", "eventCount", "completion", "policyId"),
        summary.keySet(),
        "Summary exposes only safe metadata");
    number(tape.version(), summary.get("schemaVersion"), "Tape schema version");
    number(tape.events().size(), summary.get("eventCount"), "Tape event count");
    equal(
        tape.version() == 1 ? "unknown" : tape.complete() ? "closed" : "incomplete",
        summary.get("completion"),
        "Tape completion");
    equal(
        tape.metadata() == null ? null : tape.metadata().policyId(),
        summary.get("policyId"),
        "Tape policy identifier");
  }

  private Tape replace(int index, String tool, String input, String status, String result) {
    List<Tape.Event> events = new ArrayList<>(tape.events());
    events.set(index, new Tape.Event(index, 0, tool, input, status, result));
    return new Tape(events, tape.metadata(), true);
  }

  private Path writeTape(String name, Tape value) throws IOException {
    StringBuilder contents =
        new StringBuilder(value.version() == 1 ? Tape.HEADER : Tape.HEADER_V2).append('\n');
    if (value.metadata() != null) contents.append(value.metadata().line()).append('\n');
    for (Tape.Event event : value.events()) contents.append(event.line()).append('\n');
    if (value.version() == 2 && value.complete())
      contents.append("END\t").append(value.events().size()).append('\n');
    Path path = directory.resolve("PRIVATE_PATH_" + name + ".tape");
    Files.writeString(path, contents);
    return path;
  }

  private static String json(String value) {
    return Json.write(Map.of(FIELD, value));
  }

  private static ProcessResult run(String... args) throws Exception {
    return runIn(null, args);
  }

  private static ProcessResult runIn(Path workingDirectory, String... args) throws Exception {
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
                Main.class.getName()));
    command.addAll(Arrays.asList(args));
    ProcessBuilder builder = new ProcessBuilder(command);
    if (workingDirectory != null) builder.directory(workingDirectory.toFile());
    Process process = builder.start();
    processes++;
    ExecutorService readers =
        Executors.newFixedThreadPool(
            2,
            task -> {
              Thread thread = new Thread(task, "tooltape-cli-output");
              thread.setDaemon(true);
              return thread;
            });
    try {
      // Drain both pipes concurrently, so a diagnostic can never block the other stream.
      Future<String> stdout = readers.submit(() -> readOutput(process.getInputStream()));
      Future<String> stderr = readers.submit(() -> readOutput(process.getErrorStream()));
      check(process.waitFor(30, TimeUnit.SECONDS), "CLI process must finish within 30 seconds");
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
          throw new IOException("CLI output exceeds test limit");
        buffer.write(bytes, 0, count);
      }
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }

  private static void keys(Map<String, Object> value, String... extra) {
    Set<String> expected = new HashSet<>(COMMON_KEYS);
    expected.addAll(Arrays.asList(extra));
    equal(expected, value.keySet(), "Report has only documented fields");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    check(value instanceof Map<?, ?>, "Expected JSON object");
    return (Map<String, Object>) value;
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

  private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
