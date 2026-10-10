# Tape format: v1 and v2

UTF-8 without BOM, LF-terminated lines. A missing final LF is rejected so interrupted writes do not masquerade as complete records. Base64 means **unpadded canonical URL-safe Base64 of UTF-8**, not encryption. Maximum file size is 16 MiB.

## v2: policy-bound recording sessions (current writer)

```text
TOOLTAPE<TAB>2
META<TAB><base64 metadata JSON>
<event row 0>
<event row 1>
END<TAB>2
```

Metadata is exactly a JSON object with two string fields:

```json
{"policyId":"sha256:<64 lowercase hex characters>","source":"retry-regression"}
```

- `policyId` is SHA-256 of canonical JSON containing algorithm `scalar-v2` and sorted input/output allowlists. It identifies capture semantics, never captured values. Replay compares it to the supplied `CapturePolicy` before consuming calls. Changing either allowlist changes the fingerprint.
- `source` is an explicitly supplied static provenance label, matching `[A-Za-z0-9_.:-]{1,128}`. Default: `unspecified`. It is an annotation, **not authenticated origin**. Never put personal data, paths or credentials in this label.
- `END<TAB>N` is optional for inspection, mandatory for replay/CLI validation. N must equal the event count, and END must be the last line. The writer adds it only on successful close, including sessions with recorded exceptions. Poisoned recorders do not add it.
- An incomplete v2 tape remains inspectable if all completed rows are intact, but replay rejects it. A truncated partial row is rejected entirely.
- CLOSED means the recording scope was closed, **not that the business workflow succeeded**, or that files are tamper-proof. A zero-event closed session is valid.

## Event rows (unchanged across versions)

Six TAB-separated columns:

1. Zero-based contiguous event index; canonical nonnegative decimal
2. Nonnegative elapsed nanoseconds, Java signed-long range; diagnostic only, ignored by diff
3. Base64 static tool identifier `[A-Za-z0-9_.:-]{1,128}`
4. Base64 sanitized input JSON object
5. `OK` or `ERROR`
6. Base64 sanitized output JSON object for OK, qualified Unicode exception class label for ERROR (up to 1,024 UTF-16 units; identifier categories only, excluding control/format characters)

Object keys are sorted; numbers canonicalized. Readers reject unknown versions/metadata fields, duplicate JSON keys, invalid UTF-8, discontinuous indices, malformed outcomes, noncanonical Base64, invalid completion counts and JSON nesting beyond 64 levels. CRLF files are not valid tapes. Git `.gitattributes` preserves LF on Windows.

## v1 compatibility

Legacy header: `TOOLTAPE<TAB>1`, followed directly by event rows. There is no metadata or END footer. v2 readers still accept these fixtures; `Replay` preserves v1 compatibility and cannot verify their capture policy or completeness. CLI/viewer label them as legacy. Old v1-only readers deliberately reject the v2 header rather than silently misread metadata. New recordings use v2.

## Durability and comparison limits

A flushed event means the tool returned or threw Exception. Abrupt process termination can lose an in-flight call. Flush is not fsync; this is not a durable event journal or tamper-evident audit log.

Tape diff compares event semantics only, ignoring timing, metadata and completion status. It is not validation; use `validate` before accepting a fixture for replay. Input/output differences include an RFC 6901 JSON pointer. Redacted/omitted inputs are intentionally not compared. A matching fingerprint does not make excluded fields observable or prove business correctness.

## Resource limits and policy migration

Raw JSON, canonical JSON, and aggregate canonical event payloads (tool/input/status/result UTF-8 bytes) are each bounded to 16 MiB. Canonical output is budgeted incrementally before exponent padding is allocated. Numeric tokens/plain-decimal output are limited to 10,000 characters, and each JSON value tree to 100,000 nodes. These limits also apply when the compressed file itself is small.

`scalar-v2` normalizes separators in credential-like field names before exclusion, fixing newline/format-character bypasses. Older v2 recordings with scalar-v1 policy fingerprints remain inspectable but fail replay under a new policy. Re-record reviewed fixtures; changing a stored fingerprint by hand defeats this check. Legacy v1 still has no verifiable policy identity.
