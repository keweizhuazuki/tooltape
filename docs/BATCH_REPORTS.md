# Validate a fixture inventory in CI

English | [简体中文](BATCH_REPORTS.zh-CN.md)

```sh
java -jar target/tooltape.jar validate-batch --json fixtures/weather-retry.tape fixtures/weather-retry-v2.tape
java -jar target/tooltape.jar validate-batch --json --require-v2 fixtures/weather-retry-v2.tape
```

Use a batch when a CI job should check every explicitly listed fixture and save one report, rather than stop at the first ordinary validation failure. It validates recording structure and eligibility; it does not replay your application or compare expected/actual tool events. Keep application/JUnit replay tests and the single-pair `diff` command for those jobs.

## Inputs and limits

- Supply **1–64 explicit file paths**, in the order you want results. Quote paths containing spaces. The same command syntax works in POSIX shells and PowerShell.
- Put `--json` immediately after `validate-batch`, then optional `--require-v2`, then all files. `--require-v2` rejects legacy v1; default mode still accepts well-formed legacy recordings with a warning.
- Files are processed serially. Duplicate operands remain separate indexed requests; nothing is deduplicated or discovered implicitly.
- No manifest, directory traversal, stdin, environment expansion or glob expansion is implemented by ToolTape. Shells and native Java launchers can transform arguments before ToolTape receives them; avoid wildcard characters and use quoted ASCII paths for a portable, reviewable inventory. A file omitted from the list is not checked.
- Operands beginning with `--` are treated as invalid option syntax; use an explicit path such as `./--require-v2` for a file with that name.
- Each read retains the existing 16 MiB tape limit and JSON/decoded-payload limits. At most 64 attempts means at most 64 × (16 MiB + 1 byte) of tape content read. Only one parsed tape is retained at a time; results contain safe summaries.
- Batch mode checks that each path resolves to a regular file before opening it. Symlinks to regular files are allowed; obvious directories, devices and FIFOs produce `IO_ERROR`. This precheck does not eliminate filesystem races or bound filesystem latency. Keep a CI job timeout and use a controlled fixture directory.

Any invocation beginning with `validate-batch` uses the safe batch JSON error format, even if `--json` is missing. Invalid overall syntax, including zero or more than 64 inputs, fails before reading any input. Once syntax is accepted, missing, malformed, incomplete and rejected-legacy files each produce a result and do not stop later inputs. Unexpected process/JVM failures are not converted into a claimed complete report.

## Windows filename transport

The hosted Windows JDK 17/21 launchers using `Cp1252` changed the filename character U+96EA to `?` before ToolTape received the arguments ([diagnostic run](https://github.com/keweizhuazuki/tooltape/actions/runs/38042317004)). Quoting and `-Dfile.encoding=UTF-8` did not preserve it. This matches the [OpenJDK Windows launcher code-page conversion](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/native/launcher/main.c#L105-L120).

A no-space variant was also expanded into an existing matching filename with its relative prefix removed ([second diagnostic run](https://github.com/keweizhuazuki/tooltape/actions/runs/38042619406)); that is not an exact Unicode argument round trip. OpenJDK marks unquoted wildcard characters for [argument expansion](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/native/libjli/cmdtoargs.c#L135-L144).

Use quoted ASCII-only full paths for portable Windows CLI jobs. A filename representable in another machine's active code page may work there, but arbitrary Unicode argv support is not promised. UTF-8 fixture contents are unaffected. Tests distinguish native argument transport from a separate child-JVM Unicode-path check; the latter does not prove native CLI transport. No system encoding changes are required or performed.

## Batch report version 1

A batch emits one canonical JSON document plus one literal newline on stdout, with no application stderr. JVM startup diagnostics remain outside ToolTape's control.

The independent envelope uses `batchReportVersion: 1`, not the single-command `reportVersion`:

- `tooltapeVersion`, `command: "validate-batch"`, `outcome`, `exitCode`
- `counts: { "requested": N, "valid": V, "error": E }`, where V + E = N
- `results`, in operand order; each entry is `{ "inputIndex": 0, "report": ... }`

`inputIndex` is zero-based and refers to the arguments received by ToolTape, after any shell/launcher processing. `report` is the unchanged [single-validation report v1](CLI_REPORTS.md), including its `command: "validate"`, summary, warnings and safe error code. Invalid overall syntax instead adds a fixed `error` with `INVALID_ARGUMENTS` and omits `counts`/`results`; it does not invent an empty successful batch.

| Exit | Envelope outcome | Meaning |
| --- | --- | --- |
| 0 | `valid` | Every listed input validates under the selected mode |
| 2 | `error` | At least one input failed, or invocation syntax was invalid |

A validation batch never returns 1. Later valid inputs cannot mask earlier failures. A closed session containing a recorded tool exception remains valid regression data; validation does not establish business success. The v2 requirement does not authenticate origins or establish that a policy fingerprint is the application's intended policy.

No filenames, basenames, paths, path hashes, caller labels, tool names, captured payloads, JSON keys or exception text are emitted. Correlate indexes with your private operand list. Policy hashes and event counts still reveal session/configuration structure, so review reports before public sharing. No timestamps or durations are added; unchanged inputs in the same order produce identical reports.

Actual generated examples: [mixed-schema inventory](../examples/reports/batch-valid.json), [the same inventory requiring v2](../examples/reports/batch-rejected.json). Both use the checked-in synthetic weather fixtures. For legacy migration, [review fields and re-record safely](CLI_REPORTS.md#require-v2-in-a-ci-gate); never invent missing closure or policy evidence.

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
