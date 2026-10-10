# Machine-readable CI reports

[English](CLI_REPORTS.md) | [简体中文](CLI_REPORTS.zh-CN.md)

```sh
java -jar target/tooltape.jar validate --json recording.tape
java -jar target/tooltape.jar diff --json expected.tape actual.tape
```

These two commands use the single-report JSON contract below. [Inventory validation](BATCH_REPORTS.md) has a separate batch envelope. Put `--json` immediately after the command. Every structured invocation emits one canonical JSON object and one newline on stdout, with no application stderr text. JVM options, agents or startup diagnostics can emit their own output before ToolTape runs; do not inject logging into the report stream. Property order is canonical but consumers should parse JSON rather than depend on formatting. No network access or live tool invocation occurs.

Actual generated examples: [closed-session validation](../examples/reports/validate.json), [matching events](../examples/reports/match.json), [missing-file error](../examples/reports/read-error.json). They use the checked-in synthetic weather fixture; the error example intentionally supplies a nonexistent second input.

## Require v2 in a CI gate

After a project has moved to policy-bound, closed v2 fixtures, prevent a legacy file from silently passing the same validation job:

```sh
java -jar target/tooltape.jar validate --json --require-v2 recording.tape
java -jar target/tooltape.jar diff --json --require-v2 expected.tape actual.tape
```

The option is supported by both JSON commands, immediately after `--json`. A parsed legacy v1 input produces `outcome: "error"`, `error.code: "LEGACY_TAPE"` and exit **2**, even when its events match. Without the option, legacy behavior is unchanged. Duplicate/misordered options, missing inputs and use without `--json` are usage errors; prefix a literal filename named `--require-v2` with `./`.

The existing summaries and `LEGACY_UNVERIFIED` warning remain present. A comparison retains `compatibility: "unverified"`, `eventsEqual` and `firstDivergence`. Both inputs must be readable and well-formed first; an unreadable/malformed second input is not hidden by a legacy first input. Incomplete v2 sessions and v2 policy mismatches keep their existing precedence and error codes. Closed comparable v2 tapes still return 0/1 based on event equality. A closed session containing a recorded tool error is allowed: tool failure is useful regression data.

The repository distribution job uses this option to validate its checked-in v2 fixture through the packaged JAR, so a legacy replacement cannot silently pass that consumer check.

This is a **schema eligibility gate**, not an authenticity, privacy or business-success check. It does not verify that a recorded fingerprint is the current algorithm or your application's intended policy; use `Replay` with your explicitly supplied `CapturePolicy` for policy identity enforcement. A closed v2 tape bearing an older valid fingerprint can pass validation, and two such tapes can compare when their fingerprints match.

To migrate v1 fixtures, review capture fields and re-record a controlled run using the v2 recorder, then close it successfully and review the resulting fixture. Do not manufacture `META` or `END` records to turn missing evidence into an apparent closed session. Re-recording can invoke live tools: use synthetic/stub tools or a separately approved controlled environment when tools have side effects.

[Generated legacy rejection example](../examples/reports/legacy-rejected.json) uses the checked-in synthetic v1 weather fixture. `reportVersion` remains 1 and the object shape is unchanged; `LEGACY_TAPE` is an additive error-code value used only by the opt-in command. Consumers with exhaustive error-code allowlists must accept this value before enabling the gate.

## Contract version 1

Every report contains `reportVersion: 1`, `tooltapeVersion`, `command`, `outcome` and integer `exitCode`. Unknown commands use `command: null`. `outcome` is `valid`, `match`, `divergence` or `error`.

Validation adds `summary`. Comparison adds `expected`, `actual`, `eventsEqual`, `firstDivergence` and `compatibility`. Each tape summary has:

- `schemaVersion`: 1 or 2
- `eventCount`: non-negative integer
- `completion`: `closed`, `incomplete` or legacy `unknown`
- `policyId`: the recorded SHA-256 policy identifier, or legacy `null`

`firstDivergence` is `null` or `{ "eventIndex": 0, "field": "input" }`. The index is zero-based. Fields are checked in order: `tool`, `input`, `status`, `result`, then `eventCount`. Durations and provenance labels are excluded from event equality. Input/result JSON are compared canonically. A count mismatch points to the first missing/extra event.

Reports deliberately omit captured values, tool names, source labels, paths, exception text and JSON-pointer keys. Even a key name can contain private data. Use the existing text `diff`/`inspect` commands only when exposing recorded content to the terminal/log is appropriate. Policy hashes and event counts still reveal configuration/session structure; review reports before sharing them publicly.

### Exit codes and compatibility

| Exit | Meaning |
| --- | --- |
| 0 | Valid recording or equal comparable events |
| 1 | First event divergence found |
| 2 | Invalid invocation, unreadable/malformed tape, incomplete v2 session or incompatible v2 policies; legacy input rejected by `--require-v2` |

For JSON comparison, `compatibility` has the following precedence:

1. `incomplete`: either v2 tape lacks its verified END marker; exit 2.
2. `policy-mismatch`: both tapes are v2 and have different policy identifiers; exit 2.
3. `unverified`: either tape is v1; event equality decides exit 0/1 by default (`--require-v2` instead returns `LEGACY_TAPE`/2), with `warnings: ["LEGACY_UNVERIFIED"]`.
4. `verified`: both v2 sessions are closed and carry the same policy identity; event equality decides exit 0/1.

“Verified” here checks recorded structure and policy identity only. It does not authenticate origin, establish that a workflow succeeded, or prove that omitted fields match. JSON diff is stricter than the backward-compatible text diff, which compares only events. `eventsEqual` and `firstDivergence` remain available even when metadata compatibility forces exit 2.

Failures include an `error` object with fixed `code` and safe `message`. Codes are `INVALID_ARGUMENTS`, `IO_ERROR`, `INVALID_TAPE`, `INCOMPLETE_TAPE`, `POLICY_MISMATCH` and opt-in `LEGACY_TAPE`. Reports omit fields that cannot be established, rather than inventing empty tape summaries. Successful non-legacy reports have `warnings: []`.

The Java reader exposes `InvalidTapeException extends IOException` for malformed recording content and format limits; filesystem failures remain other `IOException` instances. Existing callers catching `IOException` remain compatible.

## Preserve the status when saving a report

POSIX shell (including scripts using `set -e`):

```sh
status=0
java -jar target/tooltape.jar diff --json --require-v2 expected.tape actual.tape > report.json || status=$?
cat report.json
exit "$status"
```

PowerShell:

```powershell
java -jar target/tooltape.jar diff --json --require-v2 expected.tape actual.tape > report.json
$status = $LASTEXITCODE
Get-Content report.json
exit $status
```

Do not turn every nonzero exit into a successful job, and distinguish regressions (1) from unusable comparisons (2). Redirecting stdout preserves JSON even for invalid input. Do not append reports to one JSON document; use separate files or deliberately handle newline-delimited records.

The dependency-free build runs real Java subprocess tests for stdout, stderr and exit status on Linux, macOS and Windows with Java 17/21. These checks complement library tests; no external model credentials are needed.
