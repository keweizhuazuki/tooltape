# Validate a fixture inventory in CI

English | [简体中文](BATCH_REPORTS.zh-CN.md)

```sh
java -jar target/tooltape.jar validate-batch --json fixtures/weather-retry.tape fixtures/weather-retry-v2.tape
java -jar target/tooltape.jar validate-batch --json --require-v2 fixtures/weather-retry-v2.tape
java -jar target/tooltape.jar validate-batch --json --manifest examples/manifest/inventory.json
```

Use a batch when a CI job should check every explicitly listed fixture and save one report, rather than stop at the first ordinary validation failure. It validates recording structure and eligibility; it does not replay your application or compare expected/actual tool events. Keep application/JUnit replay tests and the single-pair `diff` command for those jobs.

## Inputs and limits

- Supply **1–64 explicit file paths**, in the order you want results. Quote paths containing spaces. The same command syntax works in POSIX shells and PowerShell.
- Put `--json` immediately after `validate-batch`, then optional `--require-v2`, then all files. `--require-v2` rejects legacy v1; default mode still accepts well-formed legacy recordings with a warning.
- Files are processed serially. Duplicate operands remain separate indexed requests; nothing is deduplicated or discovered implicitly.
- An explicit UTF-8 manifest is supported below; no recursive discovery, stdin, environment expansion or glob expansion is implemented by ToolTape. Shells and native Java launchers can transform arguments before ToolTape receives them; avoid wildcard characters and use quoted ASCII paths for a portable, reviewable inventory. A file omitted from the list is not checked.
- Operands beginning with `--` are treated as invalid option syntax; use an explicit path such as `./--require-v2` for a file with that name.
- Each read retains the existing 16 MiB tape limit and JSON/decoded-payload limits. At most 64 attempts means at most 64 × (16 MiB + 1 byte) of tape content read. Only one parsed tape is retained at a time; results contain safe summaries.
- Batch mode checks that each path resolves to a regular file before opening it. Symlinks to regular files are allowed; obvious directories, devices and FIFOs produce `IO_ERROR`. This precheck does not eliminate filesystem races or bound filesystem latency. Keep a CI job timeout and use a controlled fixture directory.

Any invocation beginning with `validate-batch` uses the safe batch JSON error format, even if `--json` is missing. Invalid CLI grammar fails before reading any input. A manifest is read and fully checked before any listed fixture is opened; zero or more than 64 entries is invalid. Once syntax is accepted, missing, malformed, incomplete and rejected-legacy files each produce a result and do not stop later inputs. Unexpected process/JVM failures are not converted into a claimed complete report.

## UTF-8 manifest: Unicode filenames without Unicode argv

Use this exclusive alternative to positional fixture arguments:

```sh
java -jar target/tooltape.jar validate-batch --json [--require-v2] --manifest inventory.json
```

The brackets indicate optional syntax, not literal characters to type. Do not mix `--manifest` with positional fixtures or repeat/reorder flags. The checked-in [inventory](../examples/manifest/inventory.json) is runnable from the repository root and includes a Unicode filename with a space and supplementary character. Its Unicode-named tape is a byte-for-byte copy of the reviewed weather-v2 fixture for filename demonstration, not a separate application scenario.

```json
{
  "manifestVersion": 1,
  "files": ["../../fixtures/weather-retry.tape", "天气 重试🌧.tape"]
}
```

Only the manifest's pathname crosses native argv. Its contents are decoded explicitly as UTF-8 inside Java, so listed Unicode filenames do not pass through the Windows launcher's active code page. Use an ASCII-only manifest pathname for portable Windows invocation; arbitrary Unicode manifest argv is still subject to the limitation below. UTF-8 decoding does not depend on `file.encoding`.

### Schema and resource limits

- Exactly `manifestVersion` and `files` are accepted. Version must be the numeric value 1. No labels, per-file options, nested manifests or includes.
- `files` must contain 1–64 nonempty strings, each at most 4,096 UTF-16 code units. Preserve duplicates and order. No trimming or Unicode normalization occurs.
- Total manifest size is at most 64 KiB, including any BOM and whitespace; the reader consumes at most 64 KiB + 1 byte to detect overflow. These are independent limits: 64 individually maximal paths need not fit in one manifest.
- Strict UTF-8 accepts one optional leading BOM, including files written by Windows tools. Invalid encoding, multiple/non-leading BOMs outside JSON strings, malformed JSON, duplicate/unknown keys, wrong types/version and escaped unpaired surrogates are rejected.
- Manifest and listed fixtures must pass the existing regular-file precheck. The manifest adds at most 65,537 bytes to the batch's existing tape-read bound. Limits do not guarantee exact heap usage or I/O completion time.

### Path meaning

Entries use forward-slash **relative** filesystem paths. They resolve against the supplied manifest path's parent, not the process working directory. Explicit `..` is allowed, for example a `.tooltape/inventory.json` can list `../fixtures/example.tape`. The implementation does not call `normalize()` or `toRealPath()`; symlinks and `..` retain filesystem semantics. For a symlinked manifest, the base is its supplied location, not the target file's directory.

Absolute/drive/UNC/URI-style entries, backslashes, control characters and unpaired surrogates are invalid. Option-like names such as `--require-v2` are ordinary file entries. Wildcards are literal characters, never expanded; whether a filename is legal still depends on the filesystem. Native-invalid names yield indexed `INVALID_ARGUMENTS` and do not stop subsequent entries.

The manifest operand itself rejects explicit UNC/device forms before ToolTape performs filesystem inspection, including mixed leading slash/backslash pairs handled as UNC by the [OpenJDK Windows path parser](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsPathParser.java). These checks run after native launcher argument processing; ToolTape cannot control that earlier stage. There is no URL loader, shell evaluation, recursive discovery, environment-variable expansion or include mechanism. This is **not directory confinement**: `..`, symlinks and mounts can reach other locations, including network-backed storage. Use reviewed manifests and controlled directories, with an external CI timeout. Regular-file checks do not eliminate races or filesystem latency.

### Safe failures

The complete schema and lexical path policy are checked before reading any fixture. Invalid encoding/schema/count/size/path policy produces outer `INVALID_MANIFEST` with a fixed message and no `counts`/`results`. Unreadable or nonregular manifests produce outer `IO_ERROR`; invalid CLI syntax/native manifest paths produce outer `INVALID_ARGUMENTS`. No parser offsets, field names, paths, entries or exception text are echoed.

Once accepted, individual fixture failures retain ordinary nested validation reports and processing continues. `inputIndex` maps to the manifest array's zero-based order. Aggregate exits remain 0/2 and `batchReportVersion` remains 1. A manifest changes input transport, not validation eligibility or the report's privacy boundary.

## Windows filename transport

The hosted Windows JDK 17/21 launchers using `Cp1252` changed the filename character U+96EA to `?` before ToolTape received the arguments ([diagnostic run](https://github.com/keweizhuazuki/tooltape/actions/runs/38042317004)). Quoting and `-Dfile.encoding=UTF-8` did not preserve it. This matches the [OpenJDK Windows launcher code-page conversion](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/native/launcher/main.c#L105-L120).

A no-space variant was also expanded into an existing matching filename with its relative prefix removed ([second diagnostic run](https://github.com/keweizhuazuki/tooltape/actions/runs/38042619406)); that is not an exact Unicode argument round trip. OpenJDK marks unquoted wildcard characters for [argument expansion](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/native/libjli/cmdtoargs.c#L135-L144).

For positional inputs, use quoted ASCII-only full paths for portable Windows CLI jobs. For Unicode fixture filenames, use the UTF-8 manifest route above with an ASCII manifest argument. A filename representable in another machine's active code page may work there, but arbitrary Unicode argv support is not promised. UTF-8 fixture contents are unaffected. Tests distinguish native argument transport from a separate child-JVM Unicode-path check; the latter does not prove native CLI transport. No system encoding changes are required or performed.

## Batch report version 1

A batch emits one canonical JSON document plus one literal newline on stdout, with no application stderr. JVM startup diagnostics remain outside ToolTape's control.

The independent envelope uses `batchReportVersion: 1`, not the single-command `reportVersion`:

- `tooltapeVersion`, `command: "validate-batch"`, `outcome`, `exitCode`
- `counts: { "requested": N, "valid": V, "error": E }`, where V + E = N
- `results`, in operand order; each entry is `{ "inputIndex": 0, "report": ... }`

`inputIndex` is zero-based and refers to positional arguments received by ToolTape after shell/launcher processing, or to the manifest array order. `report` is the unchanged [single-validation report v1](CLI_REPORTS.md), including its `command: "validate"`, summary, warnings and safe error code. Invalid overall syntax instead adds a fixed `error` with `INVALID_ARGUMENTS` and omits `counts`/`results`; it does not invent an empty successful batch.

| Exit | Envelope outcome | Meaning |
| --- | --- | --- |
| 0 | `valid` | Every listed input validates under the selected mode |
| 2 | `error` | At least one input failed, or invocation/manifest input was invalid or unreadable |

A validation batch never returns 1. Later valid inputs cannot mask earlier failures. A closed session containing a recorded tool exception remains valid regression data; validation does not establish business success. The v2 requirement does not authenticate origins or establish that a policy fingerprint is the application's intended policy.

No filenames, basenames, paths, path hashes, caller labels, tool names, captured payloads, JSON keys or exception text are emitted. Correlate indexes with your private operand list. Policy hashes and event counts still reveal session/configuration structure, so review reports before public sharing. No timestamps or durations are added; unchanged inputs in the same order produce identical reports.

Actual generated examples: [mixed-schema inventory](../examples/reports/batch-valid.json), [the same inventory requiring v2](../examples/reports/batch-rejected.json). Both use the checked-in synthetic weather fixtures. Running the checked-in UTF-8 manifest produces these same reports byte-for-byte (default and `--require-v2` respectively); it changes filename transport, not report contents. For legacy migration, [review fields and re-record safely](CLI_REPORTS.md#require-v2-in-a-ci-gate); never invent missing closure or policy evidence.

## Save the report without losing failure status

POSIX shell, including `set -e` jobs:

```sh
status=0
java -jar target/tooltape.jar validate-batch --json --require-v2 fixtures/weather-retry-v2.tape > fixture-report.json || status=$?
cat fixture-report.json
exit "$status"
```

PowerShell:

```powershell
java -jar target/tooltape.jar validate-batch --json --require-v2 fixtures/weather-retry-v2.tape > fixture-report.json
$status = $LASTEXITCODE
Get-Content fixture-report.json
exit $status
```

These examples use the one checked-in v2 fixture. Add each reviewed fixture path explicitly as your corpus grows. Multi-pair comparison manifests, automatic baseline updates and candidate recording are outside this command's scope.

## Verified platforms

[Run 38042920703](https://github.com/keweizhuazuki/tooltape/actions/runs/38042920703) passed all six Linux/macOS/Windows × Java 17/21 builds. Linux/macOS ran 100 batch subprocess scenarios, including FIFO, character-device and symlink checks. Windows ran 94 scenarios, including symlinks and the measured quoted-Unicode limitation; the Unix device/FIFO fixtures and invalid Windows wildcard filename are explicitly unavailable there. This is not a claim that Windows named pipes were tested.

The downloaded distribution JAR passed its SHA-256 check and five isolated consumer scenarios: mixed versions, required-v2 rejection, continuation after a missing file, 64 inputs and preflight rejection of 65 inputs. Existing 164 single-command subprocess scenarios and 24 JUnit tests also passed.
