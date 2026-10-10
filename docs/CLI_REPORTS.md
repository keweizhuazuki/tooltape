# Machine-readable CI reports

[English](CLI_REPORTS.md) | [简体中文](CLI_REPORTS.zh-CN.md)

```sh
java -jar target/tooltape.jar validate --json recording.tape
java -jar target/tooltape.jar diff --json expected.tape actual.tape
```

Only these two commands support JSON. Put `--json` immediately after the command. Every structured invocation emits one canonical JSON object and one newline on stdout, with no application stderr text. JVM options, agents or startup diagnostics can emit their own output before ToolTape runs; do not inject logging into the report stream. Property order is canonical but consumers should parse JSON rather than depend on formatting. No network access or live tool invocation occurs.

Actual generated examples: [closed-session validation](../examples/reports/validate.json), [matching events](../examples/reports/match.json), [missing-file error](../examples/reports/read-error.json). They use the checked-in synthetic weather fixture; the error example intentionally supplies a nonexistent second input.

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
| 2 | Invalid invocation, unreadable/malformed tape, incomplete v2 session or incompatible v2 policies |

For JSON comparison, `compatibility` has the following precedence:

1. `incomplete`: either v2 tape lacks its verified END marker; exit 2.
2. `policy-mismatch`: both tapes are v2 and have different policy identifiers; exit 2.
3. `unverified`: either tape is v1; event equality decides exit 0/1, with `warnings: ["LEGACY_UNVERIFIED"]`.
4. `verified`: both v2 sessions are closed and carry the same policy identity; event equality decides exit 0/1.

“Verified” here checks recorded structure and policy identity only. It does not authenticate origin, establish that a workflow succeeded, or prove that omitted fields match. JSON diff is stricter than the backward-compatible text diff, which compares only events. `eventsEqual` and `firstDivergence` remain available even when metadata compatibility forces exit 2.

Failures include an `error` object with fixed `code` and safe `message`. Codes are `INVALID_ARGUMENTS`, `IO_ERROR`, `INVALID_TAPE`, `INCOMPLETE_TAPE` and `POLICY_MISMATCH`. Reports omit fields that cannot be established, rather than inventing empty tape summaries. Successful non-legacy reports have `warnings: []`.

The Java reader exposes `InvalidTapeException extends IOException` for malformed recording content and format limits; filesystem failures remain other `IOException` instances. Existing callers catching `IOException` remain compatible.

## Preserve the status when saving a report

POSIX shell (including scripts using `set -e`):

```sh
status=0
java -jar target/tooltape.jar diff --json expected.tape actual.tape > report.json || status=$?
cat report.json
exit "$status"
```

PowerShell:

```powershell
java -jar target/tooltape.jar diff --json expected.tape actual.tape > report.json
$status = $LASTEXITCODE
Get-Content report.json
exit $status
```

Do not turn every nonzero exit into a successful job, and distinguish regressions (1) from unusable comparisons (2). Redirecting stdout preserves JSON even for invalid input. Do not append reports to one JSON document; use separate files or deliberately handle newline-delimited records.

The dependency-free build runs real Java subprocess tests for stdout, stderr and exit status on Linux, macOS and Windows with Java 17/21. These checks complement library tests; no external model credentials are needed.
