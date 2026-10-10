# ToolTape offline viewer

English | [简体中文](README.zh-CN.md)

Open [`index.html`](index.html) directly in a modern browser. There is no server, build step, dependency install, CDN, account, telemetry, or upload. The page reads only files you choose or drop into it. A Content Security Policy blocks network connections, external resources, forms, and embedded objects. Nothing is saved in browser storage.

## Inspect a tape

1. Click **Open tape** and choose a local `.tape` file, or drop one file onto the page.
2. Select a timeline card to inspect its input and recorded output or exception class.
3. Search tool names and payloads, or enable **Errors only**. Press **Esc** to clear filters.
4. Click **Compare tape** to select a second run. The comparison shows the first divergent position; **Inspect divergence** selects it and displays both recorded calls.

**Try an example** preserves a small, clearly labeled legacy v1 in-memory fixture, matching `fixtures/weather-retry.tape`, including one I/O exception and a successful retry. It performs no tool calls and requires no external file.

Files are limited to 16 MiB and 50,000 calls. The timeline initially renders 250 matching cards, with a button to show additional calls. Very large payload panels display the first 100,000 characters with a visible notice; search and comparison still use complete values. An invalid replacement file leaves the previous valid run intact. Opening a new primary tape clears the old comparison.

## Recording details

The compact details panel shows the schema, recording completeness, provenance source, and shortened capture-policy SHA-256 identifier. Hover over the policy ID to inspect the full value; its accessible label also includes the full value. Metadata is self-reported and does not authenticate a recording.

- **SESSION CLOSED**: v2 contains a final, matching `END` count. The recording session closed; this does not prove business success. A recording with errors or zero calls can be closed.
- **INCOMPLETE**: v2 has valid metadata and event rows but no `END`. It stays inspectable with a visible warning that it cannot be used for safe replay.
- **LEGACY · UNKNOWN**: v1 has no metadata or completion footer. The original demo remains v1. Policy identity and recording completeness cannot be verified.

The comparison panel shows the second tape's schema, completeness, source, and policy separately. Different policy IDs generate a warning even when every recorded event matches. Legacy policy coverage is marked unverifiable.

## Format

The viewer accepts UTF-8 ToolTape v1 and v2 line-oriented TSV. The header is `TOOLTAPE` followed by a tab and `1` or `2`.

v1 contains event rows directly after the header. v2 requires a second line containing `META`, a tab, and unpadded base64url of exactly this canonical JSON object shape:

```json
{"policyId":"sha256:<64 lowercase hexadecimal characters>","source":"static.identifier"}
```

Metadata keys are ordered as shown with no insignificant whitespace. Both values must be strings; unknown, missing, or duplicate keys are rejected. `source` must match `[A-Za-z0-9_.:-]{1,128}`. Use a static provenance label, never a person's name, local path, credential, or other sensitive value.

After metadata, v2 uses the same event rows as v1. Its optional final line is `END`, a tab, and a canonical nonnegative decimal event count matching the number of rows. Leading zeros, a mismatched count, duplicate footers, or any row after `END` are rejected. Missing `END` is valid for inspection and is explicitly incomplete; a partial final line is still rejected.

Each event row contains exactly six tab-separated fields:

| Field | Meaning |
| --- | --- |
| `index` | Zero-based contiguous decimal call index |
| `durationNanos` | Nonnegative signed-64-bit decimal nanosecond duration |
| `toolName` | Unpadded base64url-encoded static UTF-8 tool identifier |
| `input` | Unpadded base64url-encoded canonical input JSON object |
| `status` | `OK` or `ERROR` |
| `output` | Unpadded base64url-encoded canonical JSON object for `OK`, or error class text for `ERROR` |

Files require UTF-8 without a BOM, LF line endings, and a final newline. Tool identifiers must match `[A-Za-z0-9_.:-]{1,128}`. Error class names must match `[A-Za-z_$][A-Za-z0-9_.$]*`. Inputs and successful outputs must be JSON objects; nested arrays and other JSON values are supported within those objects. Empty encoded fields decode successfully but fail these field-specific requirements. Malformed headers, columns, integers, status values, base64url, UTF-8, duplicate JSON keys, nesting beyond 64 levels, and invalid JSON produce an inline error with the line number where applicable. See [the v1/v2 format specification](../docs/FORMAT.md).

## Comparison rules

Calls are compared in recorded row order. The first difference in **index, tool name, canonical input text, status, or output/error text** is reported. A missing or extra call is a divergence at the first absent row. The displayed position is one-based; call indexes retain their recorded values.

Durations, metadata, and closure status are deliberately ignored for event equality: replay may have different timing while recording the same behavior. Policy differences and incomplete recordings remain separately visible. The viewer canonicalizes JSON object keys and decimal numbers to match the Java codec, so reordered object keys and `1.0` versus `1` compare equally. Numeric normalization is lossless, including integers outside JavaScript’s safe-integer range. Array order remains significant.

Recorded duration totals use `BigInt`, and JSON is parsed and pretty-printed without converting numeric literals through JavaScript floating-point values. Large integers remain exact. Tape-controlled strings are inserted using DOM `textContent`, never `innerHTML`, and are not interpreted as HTML, JavaScript, or clickable links.

Comparison only covers captured fields. Redacted or omitted fields cannot be checked, and equal tapes do not prove equality of excluded behavior. A valid tape does not prove the original business workflow completed.

The summary's recorded tool time is the sum of call durations, not wall-clock run time. “Successful calls” means status `OK`; the viewer does not evaluate a tool result's business meaning.

## Privacy

Tapes can contain sensitive tool arguments or outputs. The viewer does not redact them. Inspect and share tapes accordingly. Loaded data stays in this tab's memory; close or reload the page to discard it. Browser extensions and your operating system are outside this page's control.

## Reproducible checks

The viewer itself needs only a modern browser. For development checks, use **Node.js 18 or newer**; no packages or downloads are needed. From the repository root:

```sh
node viewer/test-core.cjs
node viewer/test-interactions.cjs
```

`test-core.cjs` extracts the actual inline viewer script and checks v1/v2 parsing, strict metadata fields/types/canonicalization, footer counts and placement, incomplete/empty sessions, the 50,000-call boundary, fixture parity, decimal canonicalization, exact large integers, malformed encodings, lexical indexes/durations, comparison, JSON pretty-printing, and network/storage-free implementation.

`test-interactions.cjs` executes that same script against a small DOM model and checks opening files, visible schema/completeness/provenance, policy mismatch warnings, error filtering, search reset, divergence inspection, missing calls, inline errors, interrupted and stale-read races, the file-size limit, and text-only rendering of HTML-like payloads. These are **not browser-rendering or accessibility tests**.

For real offline Chromium interactions, responsive-layout assertions, and baseline/diff/mobile screenshots, see [the browser-check instructions](browser-tests/README.md). The pinned development-only Playwright suite runs in [the browser CI workflow](../.github/workflows/browser.yml). It opens the same HTML directly from disk and uploads real screenshots with source/fixture provenance. [Verified Chromium CI](https://github.com/keweizhuazuki/tooltape/actions/runs/38033674969) passed 25 tests (one duplicate screenshot test skipped), and its real desktop/mobile screenshots were visually reviewed. The initial local browser restriction was not bypassed.

## Implementation and next verification

Completed: v1 compatibility, strict v2 metadata and closure inspection, policy-aware comparison warnings, privacy-preserving text rendering, and reproducible dependency-free checks.

Next verification: keep screenshots synchronized with viewer changes; add physical-device testing, Firefox/Safari coverage and a full accessibility audit.
