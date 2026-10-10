package dev.tooltape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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

/** Real native Main processes: UTF-8 manifests bypass native Unicode argv transport entirely. */
public final class ManifestProcessTest {
  private static final int MAX_BYTES = 64 * 1024;
  private static final String UNICODE_NAME =
      "PRIVATE_snow \u96ea rocket \ud83d\ude80 with spaces.tape";
  private static final Set<String> COMMON_KEYS =
      Set.of("tooltapeVersion", "command", "outcome", "exitCode");
  private static final Map<String, String> TAPE_ERRORS =
      Map.of(
          "INVALID_ARGUMENTS", "Invalid command arguments.",
          "INVALID_TAPE", "Tape is malformed or unsupported.",
          "IO_ERROR", "Tape could not be read.",
          "INCOMPLETE_TAPE", "Tape has no verified completion marker.",
          "LEGACY_TAPE", "Legacy tape rejected by --require-v2.");
  private static final Path TEST_JAR = configuredJar();
  private static int checks;
  private static int processes;
  private static int namespaceBridgeProcesses;
  private static final String[] GUARDED_DEVICE_PATHS = {
    "\\\\?\\C:\\PRIVATE_device\\manifest.json",
    "//?/C:/PRIVATE_device/manifest.json",
    "\\\\?\\UNC\\PRIVATE_NO_NETWORK\\share\\manifest.json"
  };
  private static final List<String> optionalChecks = new ArrayList<>();
  private static final List<String> optionalSkips = new ArrayList<>();

  private final Path directory;
  private final Path manifests;
  private final Path cwd;
  private final Path manifest;
  private final String operand = "../PRIVATE_manifests/manifest.json";
  private final Tape.Metadata metadata =
      new Tape.Metadata(
          CapturePolicy.allow(Set.of(), Set.of()).identifier(), "private.marker.manifestSource");
  private Fixture closed;
  private Fixture legacy;
  private Fixture incomplete;
  private Fixture empty;
  private Fixture missing;
  private Fixture malformed;

  private ManifestProcessTest(Path directory) throws IOException {
    this.directory = directory;
    manifests = Files.createDirectory(directory.resolve("PRIVATE_manifests"));
    cwd = Files.createDirectory(directory.resolve("PRIVATE_conflicting_cwd"));
    manifest = manifests.resolve("manifest.json");
  }

  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("tooltape-manifest-PRIVATE_");
    try {
      ManifestProcessTest test = new ManifestProcessTest(directory);
      test.prepareFixtures();
      test.nativeUnicodeTransport();
      test.relativePathsOrderAndReports();
      test.argumentGrammar();
      test.schemaAndPathRejections();
      test.utf8AndBom();
      test.countAndSizeBounds();
      test.literalNamesAndNativePaths();
      test.manifestIoErrors();
      test.nonregularFilesAndSymlinks();
      System.out.println(
          "PASS: "
              + checks
              + " manifest CLI checks across "
              + processes
              + " processes ("
              + (processes - namespaceBridgeProcesses)
              + " native Main; "
              + namespaceBridgeProcesses
              + " namespace-only injection; UTF-8, bounds, grammar, order, schemas, privacy)");
      System.out.println(
          "Native Main launch: " + (TEST_JAR == null ? "classpath" : "packaged JAR"));
      System.out.println(
          "Unicode transport: ASCII manifest argv, raw UTF-8 snow + supplementary emoji + spaces,"
              + " conflicting-CWD decoy tapes, both --require-v2 modes: verified");
      System.out.println(
          "Optional manifest filesystem checks: "
              + optionalChecks
              + "; unavailable: "
              + optionalSkips);
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private void prepareFixtures() throws Exception {
    closed = writeFixture("PRIVATE_closed.tape", tape(1, 2, true));
    legacy = writeFixture("PRIVATE_legacy.tape", tape(2, 1, false));
    incomplete = writeFixture("PRIVATE_incomplete.tape", tape(3, 2, false));
    empty = writeFixture("PRIVATE_empty.tape", tape(0, 2, true));
    missing = new Fixture("PRIVATE_missing.tape", null, "IO_ERROR");
    malformed = new Fixture("PRIVATE_malformed.tape", null, "INVALID_TAPE");
    Files.writeString(manifests.resolve(malformed.name()), "PRIVATE_BAD_HEADER_\n");
    // Every same-name CWD fixture has a deliberately different public summary.
    for (Fixture fixture : List.of(closed, legacy, incomplete, empty))
      writeTape(cwd.resolve(fixture.name()), tape(7, 1, false));
  }

  private void nativeUnicodeTransport() throws Exception {
    check(UNICODE_NAME.codePoints().anyMatch(value -> value == 0x96ea), "Filename contains U+96EA");
    check(
        UNICODE_NAME.codePoints().anyMatch(value -> value == 0x1f680), "Filename contains U+1F680");
    Fixture unicode = writeFixture(UNICODE_NAME, tape(4, 2, true));
    writeTape(cwd.resolve(UNICODE_NAME), tape(9, 1, false));
    String json = rawManifest(unicode.name(), closed.name(), unicode.name());
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    check(json.contains("\u96ea"), "Manifest contains a literal snow character, not an escape");
    check(json.contains("\ud83d\ude80"), "Manifest contains a literal supplementary emoji");
    check(!json.contains("\\ud83d"), "Supplementary Unicode is encoded as raw UTF-8");
    check(bytes.length > json.length(), "The UTF-8 fixture really contains multibyte bytes");
    String byteValues = java.util.HexFormat.of().formatHex(bytes);
    check(byteValues.contains("e99baa"), "Raw UTF-8 contains the bytes for U+96EA");
    check(byteValues.contains("f09f9a80"), "Raw UTF-8 contains the bytes for U+1F680");
    check(operand.chars().allMatch(value -> value < 128), "Manifest operand is ASCII-only");
    for (boolean requireV2 : List.of(false, true)) {
      Files.write(manifest, bytes);
      ProcessResult first = run(requireV2, operand);
      verifyBatch(first, requireV2, unicode, closed, unicode);
      verifyBatch(
          launch(cwd, Main.class, "US-ASCII", arguments(requireV2, operand)),
          requireV2,
          unicode,
          closed,
          unicode);
      ProcessResult second = run(requireV2, operand);
      equal(first, second, "Manifest output is deterministic for unchanged inputs");
      // An escaped spelling of the same JSON path addresses the identical actual file.
      Files.writeString(
          manifest, Json.write(Map.of("manifestVersion", 1, "files", List.of(unicode.name()))));
      verifyBatch(run(requireV2, operand), requireV2, unicode);
    }
  }

  private void relativePathsOrderAndReports() throws Exception {
    Files.createDirectory(manifests.resolve("PRIVATE_nested"));
    Fixture nested = new Fixture("PRIVATE_nested/../" + closed.name(), closed.tape(), null);
    Fixture parent = new Fixture("../PRIVATE_parent.tape", tape(5, 2, true), null);
    writeTape(directory.resolve("PRIVATE_parent.tape"), parent.tape());
    Fixture current = new Fixture("./" + empty.name(), empty.tape(), null);
    Fixture directoryEntry = new Fixture(".", null, "IO_ERROR");
    Fixture[] mixed = {
      legacy, missing, nested, incomplete, malformed, current, parent, closed, legacy
    };
    for (boolean requireV2 : List.of(false, true)) {
      Map<String, Object> report = batch(requireV2, mixed);
      List<?> results = array(report.get("results"));
      for (int i = 0; i < mixed.length; i++) {
        List<String> args = new ArrayList<>(List.of("validate", "--json"));
        if (requireV2) args.add("--require-v2");
        args.add(manifests.resolve(mixed[i].name()).toString());
        ProcessResult standalone = runMain(cwd, args.toArray(String[]::new));
        Map<String, Object> standaloneReport =
            jsonReport(standalone, mixed[i].code(requireV2) == null ? 0 : 2);
        equal(
            standaloneReport,
            object(results.get(i)).get("report"),
            "Manifest nested reports remain standalone v1 reports");
      }
      batch(requireV2, directoryEntry, closed, malformed, empty);
      Fixture[] reversed = mixed.clone();
      for (int i = 0; i < reversed.length / 2; i++) {
        Fixture swap = reversed[i];
        reversed[i] = reversed[reversed.length - 1 - i];
        reversed[reversed.length - 1 - i] = swap;
      }
      batch(requireV2, reversed);
      // Bare manifest filenames also resolve entries against the manifest's supplied parent.
      writeManifest(closed.name());
      verifyBatch(runMain(manifests, arguments(requireV2, "manifest.json")), requireV2, closed);
    }
  }

  private void argumentGrammar() throws Exception {
    writeManifest(closed.name());
    for (String[] args :
        List.of(
            new String[] {"validate-batch", "--manifest", operand},
            new String[] {"validate-batch", "--json", "--manifest"},
            new String[] {"validate-batch", "--json", "--require-v2", "--manifest"},
            new String[] {"validate-batch", "--json", "--manifest", operand, closed.name()},
            new String[] {"validate-batch", "--json", closed.name(), "--manifest", operand},
            new String[] {"validate-batch", "--json", "--manifest", operand, "--require-v2"},
            new String[] {"validate-batch", "--json", "--manifest", operand, "--json"},
            new String[] {"validate-batch", "--json", "--manifest", operand, "--manifest", operand},
            new String[] {
              "validate-batch", "--json", "--require-v2", "--manifest", operand, closed.name()
            },
            new String[] {
              "validate-batch", "--json", "--require-v2", "--require-v2", "--manifest", operand
            },
            new String[] {"validate-batch", "--require-v2", "--json", "--manifest", operand},
            new String[] {"validate-batch", "--json", "--manifest=PRIVATE_value"},
            new String[] {"validate-batch", "--json", "--manifest", "--json"},
            new String[] {"validate-batch", "--json", "--manifest", "--require-v2"},
            new String[] {"validate-batch", "--json", "--manifest", "--PRIVATE_unknown"},
            new String[] {"validate-batch", "--json", "--", "--manifest", operand})) {
      outerError(runMain(cwd, args), "INVALID_ARGUMENTS", "Invalid command arguments.");
    }
    // Never contact a server: these namespace forms must fail lexically before filesystem I/O.
    for (String networkOrDevice :
        List.of(
            "\\\\PRIVATE_NO_NETWORK\\share\\manifest.json",
            "//PRIVATE_NO_NETWORK/share/manifest.json",
            "\\\\.\\PRIVATE_device",
            "//./PRIVATE_device",
            "/\\PRIVATE_NO_NETWORK/share",
            "\\/PRIVATE_NO_NETWORK/share",
            "//",
            "\\\\")) {
      for (boolean requireV2 : List.of(false, true))
        outerError(
            run(requireV2, networkOrDevice), "INVALID_ARGUMENTS", "Invalid command arguments.");
    }
    // On Windows the Java launcher can glob '?' before Main starts. Inject these guard-only
    // cases inside a child JVM; they make no native argv transport claim and never use wildcard
    // or network/device operands on the launcher command line. The Unicode tests above always
    // launch Main directly with a normal ASCII manifest filename.
    for (int i = 0; i < GUARDED_DEVICE_PATHS.length; i++) {
      for (boolean requireV2 : List.of(false, true)) {
        namespaceBridgeProcesses++;
        ProcessResult result =
            runEntrypoint(
                cwd, NamespacePathMain.class, arguments(requireV2, "PRIVATE_DEVICE_TOKEN_" + i));
        outerError(result, "INVALID_ARGUMENTS", "Invalid command arguments.");
      }
    }
    // An option-like filename is literal when prefixed with a normal relative path.
    Path optionManifest = cwd.resolve("--manifest");
    Files.writeString(optionManifest, rawManifest("../PRIVATE_manifests/" + closed.name()));
    verifyBatch(run(false, "./--manifest"), false, closed);
    Files.writeString(cwd.resolve("-"), rawManifest("../PRIVATE_manifests/" + closed.name()));
    verifyBatch(run(true, "-"), true, closed);
  }

  private void schemaAndPathRejections() throws Exception {
    String good = Json.write(closed.name());
    for (String text :
        List.of(
            "",
            " ",
            "null",
            "[]",
            "true",
            "1",
            "\"PRIVATE_scalar\"",
            "{}",
            "{\"manifestVersion\":1}",
            "{\"files\":[" + good + "]}",
            "{\"manifestVersion\":1,\"files\":[" + good + "],\"PRIVATE_extra\":\"PRIVATE_secret\"}",
            "{\"manifestVersion\":1,\"files\":null}",
            "{\"manifestVersion\":1,\"files\":{}}",
            "{\"manifestVersion\":1,\"files\":\"PRIVATE_not_array\"}",
            "{\"manifestVersion\":1,\"files\":true}",
            "{\"manifestVersion\":1,\"files\":1}",
            "{\"manifestVersion\":1,\"files\":[]}",
            "{\"manifestVersion\":1,\"manifestVersion\":1,\"files\":[" + good + "]}",
            "{\"manifestVersion\":1,\"files\":[" + good + "],\"files\":[" + good + "]}",
            "{\"manifestVersion\":1,\"\\u0066iles\":[" + good + "],\"files\":[" + good + "]}",
            "{\"manifestVersion\":1,\"files\":[" + good + "]} PRIVATE_trailing",
            "{\"manifestVersion\":1,\"files\":[" + good + "]}{}",
            "{\"manifestVersion\":1,\"files\":[" + good + ",]}",
            "{\"manifestVersion\":1,\"files\":[" + good + "] /* PRIVATE_comment */}",
            "{\"manifestVersion\":1,\"files\":[\"PRIVATE_\\x00\"]}")) invalidManifest(text);
    for (String version :
        List.of("0", "2", "-1", "1.5", "1e2", "null", "true", "\"1\"", "[]", "{}"))
      invalidManifest("{\"manifestVersion\":" + version + ",\"files\":[" + good + "]}");
    for (String element : List.of("null", "1", "true", "{}", "[]", "\"\""))
      invalidManifest("{\"manifestVersion\":1,\"files\":[" + good + "," + element + "]}");
    for (String path :
        List.of(
            "/PRIVATE_absolute.tape",
            "//PRIVATE_NO_NETWORK/share",
            "\\\\PRIVATE_NO_NETWORK\\share",
            "C:/PRIVATE_drive.tape",
            "C:PRIVATE_drive_relative.tape",
            "PRIVATE_stream:alternate",
            "PRIVATE_folder\\file.tape",
            "PRIVATE_\u0000",
            "PRIVATE_\n",
            "PRIVATE_\r",
            "PRIVATE_\t",
            "PRIVATE_\u001f",
            "PRIVATE_\u007f",
            "PRIVATE_\u0085",
            "PRIVATE_\u009f",
            "PRIVATE_\ud800",
            "PRIVATE_\udc00",
            "PRIVATE_\ud83dX",
            "PRIVATE_X\ude80")) invalidManifest(rawManifest(closed.name(), path, missing.name()));
    for (String escapedSurrogate :
        List.of("\\ud800", "\\udc00", "\\ud800\\ud800", "\\udc00\\ud800"))
      invalidManifest("{\"manifestVersion\":1,\"files\":[\"PRIVATE_" + escapedSurrogate + "\"]}");
    for (String version : List.of("1.0", "1e0", "1.000")) {
      Files.writeString(manifest, "{\"manifestVersion\":" + version + ",\"files\":[" + good + "]}");
      verifyBatch(run(false, operand), false, closed);
    }
    // Nested hostile values are bounded and never reflected in the report.
    invalidManifest(
        "{\"manifestVersion\":1,\"files\":[" + "[".repeat(70) + "null" + "]".repeat(70) + "]}");
  }

  private void utf8AndBom() throws Exception {
    byte[] valid = rawManifest(closed.name()).getBytes(StandardCharsets.UTF_8);
    byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    for (boolean requireV2 : List.of(false, true)) {
      Files.write(manifest, join(bom, valid));
      verifyBatch(run(requireV2, operand), requireV2, closed);
      Files.write(manifest, join(bom, bom, valid));
      invalidManifest(run(requireV2, operand));
    }
    for (byte[] bytes :
        List.of(
            new byte[] {(byte) 0xff},
            new byte[] {(byte) 0xfe},
            new byte[] {(byte) 0xc0, (byte) 0xaf},
            new byte[] {(byte) 0xc3, 0x28},
            new byte[] {(byte) 0xe2, (byte) 0x82},
            new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
            new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0xef, (byte) 0xbb},
            bom,
            join(new byte[] {' '}, bom, valid),
            join(valid, bom),
            join(valid, new byte[] {0}))) {
      Files.write(manifest, bytes);
      invalidManifest(run(false, operand));
    }
    // Bad UTF-8 in a syntactically plausible path must not be silently replaced by U+FFFD.
    byte[] prefix = "{\"manifestVersion\":1,\"files\":[\"PRIVATE_".getBytes(StandardCharsets.UTF_8);
    byte[] suffix = "\"]}".getBytes(StandardCharsets.UTF_8);
    Files.write(manifest, join(prefix, new byte[] {(byte) 0xc0, (byte) 0xaf}, suffix));
    invalidManifest(run(true, operand));
    Files.write(manifest, rawManifest(closed.name()).getBytes(StandardCharsets.UTF_16LE));
    invalidManifest(run(false, operand));
  }

  private void countAndSizeBounds() throws Exception {
    for (boolean requireV2 : List.of(false, true)) {
      batch(requireV2, closed);
      Fixture[] maximum = new Fixture[64];
      Arrays.fill(maximum, requireV2 ? incomplete : closed);
      batch(requireV2, maximum);
      String[] names = new String[65];
      Arrays.fill(names, closed.name());
      writeManifest(names);
      invalidManifest(run(requireV2, operand));
    }
    Fixture unicode = new Fixture(UNICODE_NAME, tape(4, 2, true), null);
    for (boolean leadingBom : List.of(false, true)) {
      byte[] raw = rawManifest(unicode.name()).getBytes(StandardCharsets.UTF_8);
      if (leadingBom) raw = join(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, raw);
      byte[] exact = Arrays.copyOf(raw, MAX_BYTES);
      Arrays.fill(exact, raw.length, exact.length, (byte) ' ');
      Files.write(manifest, exact);
      equal((long) MAX_BYTES, Files.size(manifest), "Manifest is exactly the allowed byte limit");
      verifyBatch(run(true, operand), true, unicode);
      Files.write(manifest, join(exact, new byte[] {' '}));
      equal(
          (long) MAX_BYTES + 1,
          Files.size(manifest),
          "Manifest crosses limit by exactly one sentinel byte");
      invalidManifest(run(false, operand));
    }
    for (String path : List.of("a".repeat(4096), "\ud83d\ude80".repeat(2048))) {
      equal(4096, path.length(), "Exact path cap counts UTF-16 code units");
      batch(false, new Fixture(path, null, "IO_ERROR"), closed);
      invalidManifest(rawManifest(path + "a", closed.name()));
    }
    // An escape costs six source bytes but one UTF-16 unit after decoding.
    String escaped = "{\"manifestVersion\":1,\"files\":[\"" + "\\u0061".repeat(4096) + "\"]}";
    Files.writeString(manifest, escaped);
    verifyBatch(run(true, operand), true, new Fixture("a".repeat(4096), null, "IO_ERROR"));
    invalidManifest(escaped.replace("\"]}", "a\"]}"));
  }

  private void literalNamesAndNativePaths() throws Exception {
    for (String name :
        List.of(
            "--json",
            "--require-v2",
            "--manifest",
            "--PRIVATE_option",
            "-",
            "PRIVATE_has spaces.tape")) {
      Fixture literal = writeFixture(name, tape(2, 2, true));
      writeTape(cwd.resolve(name), tape(8, 1, false));
      for (boolean requireV2 : List.of(false, true)) batch(requireV2, literal, closed, literal);
    }
    for (String name :
        List.of(
            "PRIVATE_glob_*.tape",
            "PRIVATE_question_?.tape",
            "PRIVATE_less<than.tape",
            "PRIVATE_quote\".tape")) {
      Path nativePath;
      try {
        nativePath = manifests.resolve(name);
      } catch (InvalidPathException invalid) {
        for (boolean requireV2 : List.of(false, true))
          batch(requireV2, new Fixture(name, null, "INVALID_ARGUMENTS"), closed);
        optionalChecks.add("native invalid path remains indexed: " + Json.write(name));
        continue;
      }
      // A real matching sibling must not cause wildcard expansion.
      if (name.contains("*")) writeFixture(name.replace("*", "match"), tape(11, 1, false));
      if (name.contains("?")) writeFixture(name.replace("?", "x"), tape(12, 1, false));
      batch(false, new Fixture(name, null, "IO_ERROR"), closed);
      writeTape(nativePath, tape(6, 2, true));
      Fixture literal = new Fixture(name, tape(6, 2, true), null);
      for (boolean requireV2 : List.of(false, true)) batch(requireV2, literal, closed);
    }
  }

  private void manifestIoErrors() throws Exception {
    for (boolean requireV2 : List.of(false, true)) {
      manifestIo(run(requireV2, ""));
      manifestIo(run(requireV2, "../PRIVATE_manifests/PRIVATE_missing.json"));
      manifestIo(run(requireV2, "../PRIVATE_manifests"));
      manifestIo(run(requireV2, "../PRIVATE_manifests/manifest.json/PRIVATE_child"));
    }
    for (String nativePath :
        List.of("PRIVATE_manifest<invalid>.json", "PRIVATE_manifest\"invalid.json")) {
      try {
        Path.of(nativePath);
        manifestIo(run(false, nativePath));
      } catch (InvalidPathException expected) {
        outerError(run(false, nativePath), "INVALID_ARGUMENTS", "Invalid command arguments.");
      }
    }
    Path unreadable = manifests.resolve("PRIVATE_unreadable.json");
    Files.writeString(unreadable, rawManifest(closed.name()));
    try {
      Set<PosixFilePermission> before = Files.getPosixFilePermissions(unreadable);
      try {
        Files.setPosixFilePermissions(unreadable, Set.of());
        if (!Files.isReadable(unreadable)) {
          manifestIo(run(false, "../PRIVATE_manifests/PRIVATE_unreadable.json"));
          optionalChecks.add("unreadable manifest");
        } else optionalSkips.add("unreadable manifest (privileged filesystem access)");
      } finally {
        Files.setPosixFilePermissions(unreadable, before);
      }
    } catch (UnsupportedOperationException unavailable) {
      optionalSkips.add("POSIX unreadable manifest");
    }
  }

  private void nonregularFilesAndSymlinks() throws Exception {
    Path fifo = createFifo();
    if (fifo != null) {
      Fixture input = new Fixture(fifo.getFileName().toString(), null, "IO_ERROR");
      for (boolean requireV2 : List.of(false, true)) {
        manifestIo(run(requireV2, "../PRIVATE_manifests/" + input.name()));
        batch(requireV2, input, closed);
      }
      // No writer exists. Syntax, count and late schema errors must finish without opening it.
      outerError(
          runMain(
              cwd,
              "validate-batch",
              "--json",
              "--manifest",
              "../PRIVATE_manifests/" + input.name(),
              closed.name()),
          "INVALID_ARGUMENTS",
          "Invalid command arguments.");
      invalidManifest(rawManifest(input.name(), "PRIVATE_invalid:entry"));
      invalidManifest("{\"manifestVersion\":1,\"files\":[" + Json.write(input.name()) + ",null]}");
      String[] tooMany = new String[65];
      Arrays.fill(tooMany, input.name());
      invalidManifest(rawManifest(tooMany));
      optionalChecks.add("FIFO manifest and fixture, late grammar/schema/count errors");
    } else optionalSkips.add("FIFO");

    Path device = Path.of("/dev/null");
    if (Files.exists(device) && !Files.isRegularFile(device)) {
      manifestIo(run(false, device.toString()));
      optionalChecks.add("character device manifest");
    } else optionalSkips.add("character device manifest");

    Path link = manifests.resolve("PRIVATE_link.tape");
    try {
      Files.createSymbolicLink(link, manifests.resolve(closed.name()));
    } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
      optionalSkips.add("symbolic links");
      return;
    }
    batch(true, new Fixture(link.getFileName().toString(), closed.tape(), null), empty);
    Files.delete(link);
    Files.createSymbolicLink(link, manifests);
    batch(false, new Fixture(link.getFileName().toString(), null, "IO_ERROR"), closed);
    Files.delete(link);
    Files.createSymbolicLink(link, manifests.resolve(missing.name()));
    batch(false, new Fixture(link.getFileName().toString(), null, "IO_ERROR"), closed);
    manifestIo(run(true, "../PRIVATE_manifests/" + link.getFileName()));
    if (Files.exists(device) && !Files.isRegularFile(device)) {
      Files.delete(link);
      Files.createSymbolicLink(link, device);
      batch(false, new Fixture(link.getFileName().toString(), null, "IO_ERROR"), closed);
      optionalChecks.add("character device fixture through relative symlink");
    }

    Path storage = Files.createDirectory(directory.resolve("PRIVATE_storage"));
    writeTape(storage.resolve(closed.name()), tape(13, 1, false));
    Path targetManifest = storage.resolve("PRIVATE_stored.json");
    Files.writeString(targetManifest, rawManifest(closed.name()));
    Path manifestLink = manifests.resolve("PRIVATE_manifest_link.json");
    Files.createSymbolicLink(manifestLink, targetManifest);
    for (boolean requireV2 : List.of(false, true))
      verifyBatch(
          run(requireV2, "../PRIVATE_manifests/PRIVATE_manifest_link.json"), requireV2, closed);

    Path deep = Files.createDirectory(storage.resolve("PRIVATE_deep"));
    Files.createSymbolicLink(manifests.resolve("PRIVATE_bridge"), deep);
    Fixture explicitParent =
        new Fixture("PRIVATE_bridge/../PRIVATE_parent.tape", tape(5, 2, true), null);
    writeTape(storage.resolve("PRIVATE_parent.tape"), explicitParent.tape());
    writeTape(manifests.resolve("PRIVATE_parent.tape"), tape(14, 1, false));
    Files.writeString(
        storage.resolve("PRIVATE_parent_manifest.json"), rawManifest("PRIVATE_parent.tape"));
    optionalChecks.add("symlink manifests resolve against supplied parent, not real target parent");
    // Some native providers collapse '..' before following a link. Assert non-normalization only
    // where the host can distinguish it; ordinary explicit '..' paths are tested on every OS.
    if (Tape.read(manifests.resolve(explicitParent.name())).events().size() != 5) {
      optionalSkips.add("symlink/.. distinction (native provider collapses parent segments)");
      return;
    }
    for (boolean requireV2 : List.of(false, true)) {
      batch(requireV2, explicitParent, closed);
      verifyBatch(
          run(requireV2, "../PRIVATE_manifests/PRIVATE_bridge/../PRIVATE_parent_manifest.json"),
          requireV2,
          new Fixture("PRIVATE_parent.tape", explicitParent.tape(), null));
    }
    optionalChecks.add(
        "symlinks, supplied manifest parent, explicit .. without normalize/toRealPath");
  }

  private Path createFifo() throws Exception {
    Path executable = Path.of("/usr/bin/mkfifo");
    if (!Files.isExecutable(executable)) executable = Path.of("/bin/mkfifo");
    if (!Files.isExecutable(executable)) return null;
    Path fifo = manifests.resolve("PRIVATE_fifo");
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

  private Tape tape(int count, int version, boolean complete) {
    List<Tape.Event> events = new ArrayList<>();
    for (int i = 0; i < count; i++)
      events.add(
          new Tape.Event(
              i,
              i,
              "private.marker.tool",
              "{\"PRIVATE_input\":\"PRIVATE_payload\"}",
              "OK",
              "{\"PRIVATE_output\":\"PRIVATE_secret\"}"));
    return version == 1 ? new Tape(events) : new Tape(events, metadata, complete);
  }

  private Fixture writeFixture(String name, Tape tape) throws IOException {
    writeTape(manifests.resolve(name), tape);
    return new Fixture(name, tape, null);
  }

  private static void writeTape(Path path, Tape tape) throws IOException {
    StringBuilder value =
        new StringBuilder(tape.version() == 1 ? Tape.HEADER : Tape.HEADER_V2).append('\n');
    if (tape.metadata() != null) value.append(tape.metadata().line()).append('\n');
    for (Tape.Event event : tape.events()) value.append(event.line()).append('\n');
    if (tape.version() == 2 && tape.complete())
      value.append("END\t").append(tape.events().size()).append('\n');
    Files.writeString(path, value);
  }

  private static String rawManifest(String... names) {
    // Json.write correctly escapes lone surrogates; convert only this valid pair to raw Unicode.
    return Json.write(Map.of("manifestVersion", 1, "files", List.of(names)))
        .replace("\\ud83d\\ude80", "\ud83d\ude80");
  }

  private void writeManifest(String... names) throws IOException {
    Files.writeString(manifest, rawManifest(names), StandardCharsets.UTF_8);
  }

  private Map<String, Object> batch(boolean requireV2, Fixture... fixtures) throws Exception {
    writeManifest(Arrays.stream(fixtures).map(Fixture::name).toArray(String[]::new));
    return verifyBatch(run(requireV2, operand), requireV2, fixtures);
  }

  private Map<String, Object> verifyBatch(
      ProcessResult process, boolean requireV2, Fixture... fixtures) {
    int errors =
        (int) Arrays.stream(fixtures).filter(fixture -> fixture.code(requireV2) != null).count();
    Map<String, Object> report = batchReport(process, errors == 0 ? 0 : 2);
    keys(report, "batchReportVersion", "counts", "results");
    Map<String, Object> counts = object(report.get("counts"));
    equal(
        Set.of("requested", "valid", "error"),
        counts.keySet(),
        "Counts have exactly the unchanged batch fields");
    number(fixtures.length, counts.get("requested"), "Requested includes duplicates and failures");
    number(fixtures.length - errors, counts.get("valid"), "Valid count");
    number(errors, counts.get("error"), "Error count");
    List<?> results = array(report.get("results"));
    equal(fixtures.length, results.size(), "Exactly one result per manifest entry");
    for (int i = 0; i < fixtures.length; i++) {
      Map<String, Object> result = object(results.get(i));
      equal(
          Set.of("inputIndex", "report"),
          result.keySet(),
          "Only index and nested report are exposed");
      number(i, result.get("inputIndex"), "Indexes retain manifest order and duplicates");
      validation(object(result.get("report")), requireV2, fixtures[i]);
    }
    return report;
  }

  private static void validation(Map<String, Object> report, boolean requireV2, Fixture fixture) {
    String code = fixture.code(requireV2);
    List<String> fields = new ArrayList<>(List.of("reportVersion"));
    if (fixture.tape() != null) fields.addAll(List.of("summary", "warnings"));
    if (code != null) fields.add("error");
    keys(report, fields.toArray(String[]::new));
    number(1, report.get("reportVersion"), "Nested validation schema stays v1");
    equal(Main.VERSION, report.get("tooltapeVersion"), "Nested ToolTape version");
    equal("validate", report.get("command"), "Nested command is unchanged");
    equal(code == null ? "valid" : "error", report.get("outcome"), "Nested outcome");
    number(code == null ? 0 : 2, report.get("exitCode"), "Nested exit status");
    if (code != null) error(report, code, TAPE_ERRORS.get(code));
    if (fixture.tape() == null) return;
    Tape tape = fixture.tape();
    Map<String, Object> summary = object(report.get("summary"));
    equal(
        Set.of("schemaVersion", "eventCount", "completion", "policyId"),
        summary.keySet(),
        "Only safe summary fields are exposed");
    number(tape.version(), summary.get("schemaVersion"), "Actual resolved tape version");
    number(
        tape.events().size(),
        summary.get("eventCount"),
        "Actual resolved tape event count differs from CWD decoy");
    equal(
        tape.version() == 1 ? "unknown" : tape.complete() ? "closed" : "incomplete",
        summary.get("completion"),
        "Completion state");
    equal(
        tape.metadata() == null ? null : tape.metadata().policyId(),
        summary.get("policyId"),
        "Policy fingerprint");
    equal(
        tape.version() == 1 ? List.of("LEGACY_UNVERIFIED") : List.of(),
        report.get("warnings"),
        "Legacy warnings unchanged");
  }

  private Map<String, Object> jsonReport(ProcessResult process, int exitCode) {
    equal(
        exitCode,
        process.exitCode(),
        "Actual native process exit status; output=" + process.stdout());
    equal("", process.stderr(), "JSON mode leaves stderr empty");
    Map<String, Object> report = Json.object(process.stdout());
    equal(
        Json.write(report) + "\n",
        process.stdout(),
        "One canonical JSON document with one literal newline");
    equal(Main.VERSION, report.get("tooltapeVersion"), "ToolTape version");
    equal(exitCode == 0 ? "valid" : "error", report.get("outcome"), "Reported outcome");
    number(exitCode, report.get("exitCode"), "Reported exit matches actual exit");
    for (String secret :
        List.of(
            "PRIVATE_",
            "private.marker.",
            directory.toString(),
            "java.io.",
            "Exception",
            "\u96ea",
            "\ud83d\ude80",
            "\\u96ea",
            "\\ud83d"))
      check(
          !process.stdout().contains(secret),
          "No manifest/fixture names, paths, content, provenance or exceptions leak");
    return report;
  }

  private Map<String, Object> batchReport(ProcessResult process, int exitCode) {
    Map<String, Object> report = jsonReport(process, exitCode);
    number(1, report.get("batchReportVersion"), "Batch report version stays v1");
    equal("validate-batch", report.get("command"), "Batch command identity");
    return report;
  }

  private void outerError(ProcessResult process, String code, String message) {
    Map<String, Object> report = batchReport(process, 2);
    keys(report, "batchReportVersion", "error");
    error(report, code, message);
  }

  private void invalidManifest(String text) throws Exception {
    Files.writeString(manifest, text, StandardCharsets.UTF_8);
    invalidManifest(run(false, operand));
  }

  private void invalidManifest(ProcessResult process) {
    outerError(process, "INVALID_MANIFEST", "Manifest is malformed or unsupported.");
  }

  private void manifestIo(ProcessResult process) {
    outerError(process, "IO_ERROR", "Manifest could not be read.");
  }

  private static void error(Map<String, Object> report, String code, String message) {
    Map<String, Object> error = object(report.get("error"));
    equal(Set.of("code", "message"), error.keySet(), "Errors expose only fixed code and message");
    equal(code, error.get("code"), "Fixed error code");
    equal(message, error.get("message"), "Fixed, payload-free error message");
  }

  private ProcessResult run(boolean requireV2, String path) throws Exception {
    return runMain(cwd, arguments(requireV2, path));
  }

  private static String[] arguments(boolean requireV2, String path) {
    List<String> args = new ArrayList<>(List.of("validate-batch", "--json"));
    if (requireV2) args.add("--require-v2");
    args.add("--manifest");
    args.add(path);
    return args.toArray(String[]::new);
  }

  private static Path configuredJar() {
    String value = System.getProperty("tooltape.test.jar");
    if (value == null) return null;
    Path jar = Path.of(value).toAbsolutePath();
    if (!Files.isRegularFile(jar))
      throw new IllegalArgumentException("Configured test JAR is missing");
    return jar;
  }

  private static ProcessResult runMain(Path workingDirectory, String... args) throws Exception {
    return runEntrypoint(workingDirectory, Main.class, args);
  }

  private static ProcessResult runEntrypoint(
      Path workingDirectory, Class<?> entrypoint, String... args) throws Exception {
    return launch(workingDirectory, entrypoint, "UTF-8", args);
  }

  private static ProcessResult launch(
      Path workingDirectory, Class<?> entrypoint, String encoding, String... args)
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
            List.of(java.toString(), "-XX:-UsePerfData", "-Dfile.encoding=" + encoding));
    if (entrypoint == Main.class && TEST_JAR != null) {
      command.add("-jar");
      command.add(TEST_JAR.toString());
    } else {
      command.add("-cp");
      command.add(classpath);
      command.add(entrypoint.getName());
    }
    command.addAll(Arrays.asList(args));
    Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
    processes++;
    ExecutorService readers =
        Executors.newFixedThreadPool(
            2,
            task -> {
              Thread thread = new Thread(task, "tooltape-manifest-output");
              thread.setDaemon(true);
              return thread;
            });
    try {
      Future<String> stdout = readers.submit(() -> readOutput(process.getInputStream()));
      Future<String> stderr = readers.submit(() -> readOutput(process.getErrorStream()));
      check(
          process.waitFor(30, TimeUnit.SECONDS),
          "Native manifest process must finish within 30 seconds");
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
        if (count > MAX_BYTES - buffer.size())
          throw new IOException("Manifest output exceeds test limit");
        buffer.write(bytes, 0, count);
      }
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }

  private static byte[] join(byte[]... parts) {
    int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
    byte[] result = new byte[length];
    int position = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, result, position, part.length);
      position += part.length;
    }
    return result;
  }

  private static void keys(Map<String, Object> report, String... extra) {
    Set<String> expected = new HashSet<>(COMMON_KEYS);
    expected.addAll(Arrays.asList(extra));
    equal(expected, report.keySet(), "Report has exactly the documented unchanged fields");
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

  private record Fixture(String name, Tape tape, String failure) {
    String code(boolean requireV2) {
      if (failure != null) return failure;
      if (tape.version() == 2 && !tape.complete()) return "INCOMPLETE_TAPE";
      return requireV2 && tape.version() == 1 ? "LEGACY_TAPE" : null;
    }
  }

  /** Tests device-prefix rejection without letting the native Windows launcher expand '?'. */
  public static final class NamespacePathMain {
    public static void main(String[] args) {
      String token = args[args.length - 1];
      int index = Integer.parseInt(token.substring("PRIVATE_DEVICE_TOKEN_".length()));
      args[args.length - 1] = GUARDED_DEVICE_PATHS[index];
      Main.main(args);
    }
  }

  private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
