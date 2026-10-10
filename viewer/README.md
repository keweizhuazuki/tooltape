# ToolTape offline viewer

English | [简体中文](README.zh-CN.md)

Open [`index.html`](index.html) directly in a modern browser. There is no server, build step, dependency install, CDN, account, telemetry, or upload. The page reads only files you choose or drop into it. A Content Security Policy blocks network connections, external resources, forms, and embedded objects. Nothing is saved in browser storage.

## Inspect a tape

1. Click **Open tape** and choose a local `.tape` file, or drop one file onto the page.
2. Select a timeline card to inspect its input and recorded output or exception class.
3. Search tool names and payloads, or enable **Errors only**. Press **Esc** to clear filters.
4. Click **Compare tape** to select a second run. The comparison shows the first divergent position; **Inspect divergence** selects it and displays both recorded calls.

**Try an example** loads a small, clearly labeled in-memory fixture, matching `fixtures/weather-retry.tape`, including one I/O exception and a successful retry. It performs no tool calls and requires no external file.

Files are limited to 16 MiB and 50,000 calls. The timeline initially renders 250 matching cards, with a button to show additional calls. Very large payload panels display the first 100,000 characters with a visible notice; search and comparison still use complete values. An invalid replacement file leaves the previous valid run intact. Opening a new primary tape clears the old comparison.

## Format

The viewer accepts UTF-8 ToolTape v1 line-oriented TSV. The first line is `TOOLTAPE` followed by a tab and `1`. Each later line contains exactly six tab-separated fields:

| Field | Meaning |
| --- | --- |
| `index` | Zero-based contiguous decimal call index |
| `durationNanos` | Nonnegative signed-64-bit decimal nanosecond duration |
| `toolName` | Unpadded base64url-encoded static UTF-8 tool identifier |
| `input` | Unpadded base64url-encoded canonical input JSON object |
| `status` | `OK` or `ERROR` |
| `output` | Unpadded base64url-encoded canonical JSON object for `OK`, or error class text for `ERROR` |

Files require UTF-8 without a BOM, LF line endings, and a final newline. Tool identifiers must match `[A-Za-z0-9_.:-]{1,128}`. Error class names must match `[A-Za-z_$][A-Za-z0-9_.$]*`. Inputs and successful outputs must be JSON objects; nested arrays and other JSON values are supported within those objects. Empty encoded fields decode successfully but fail these field-specific requirements. Malformed headers, columns, integers, status values, base64url, UTF-8, duplicate JSON keys, nesting beyond 64 levels, and invalid JSON produce an inline error with the line number where applicable. See [the v1 format specification](../docs/FORMAT.md).

## Comparison rules

Calls are compared in recorded row order. The first difference in **index, tool name, canonical input text, status, or output/error text** is reported. A missing or extra call is a divergence at the first absent row. The displayed position is one-based; call indexes retain their recorded values.

Durations are deliberately ignored: replay may have different timing while recording the same behavior. The viewer canonicalizes JSON object keys and decimal numbers to match the Java codec, so reordered object keys and `1.0` versus `1` compare equally. Numeric normalization is lossless, including integers outside JavaScript’s safe-integer range. Array order remains significant.

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

`test-core.cjs` extracts the actual inline viewer script and checks parsing, fixture parity, decimal canonicalization, exact large integers, malformed and noncanonical encodings, lexical indexes/durations, comparison, JSON pretty-printing, and network/storage-free implementation.

`test-interactions.cjs` executes that same script against a small DOM model and checks opening files, error filtering, search reset, divergence inspection, missing calls, inline errors, stale-read races, the file-size limit, and text-only rendering of HTML-like payloads. These are **not browser-rendering or accessibility tests**. Actual Chromium rendering and screenshot capture were unavailable in the development environment because browser startup was denied permission to create its process socket; no synthetic screenshot is supplied.
