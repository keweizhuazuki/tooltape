# Tape format v1

UTF-8 without BOM, LF-terminated lines. A missing final LF is rejected so interrupted writes do not masquerade as complete records. Header: `TOOLTAPE<TAB>1`. Each record has six TAB-separated columns:

1. Zero-based contiguous event index
2. Nonnegative elapsed nanoseconds (diagnostic only, ignored by diff)
3. Tool identifier encoded as unpadded URL-safe Base64 UTF-8
4. Sanitized input JSON object, same encoding
5. `OK` or `ERROR`
6. Sanitized output JSON object for OK, Java exception class name for ERROR, same encoding

Object keys are sorted; JSON numbers canonicalized. Tool identifiers are static `[A-Za-z0-9_.:-]{1,128}` values. Never put personal data in identifiers. Recorders and readers limit total size to 16 MiB. Readers reject unknown versions, duplicate JSON keys, invalid UTF-8, discontinuous indices, malformed outcomes, invalid Base64, and JSON nesting beyond 64 levels. CRLF files are not v1. Base64 is encoding, not encryption.

A flushed record means the tool returned or threw Exception. Abrupt process termination can lose the currently running call, and flush is not fsync. The format is not a durable event journal or tamper-evident audit log. A header-only tape is valid and contains zero completed calls. There is no session-completion footer; completeness is relative to recorded events, not proof that the original business workflow finished.

The format does not contain a capture policy. Version-control the same policy with your orchestration test. Redacted/omitted inputs are intentionally not compared. Changing the policy can invalidate fixtures or weaken assertions. Do not treat tape equality as proof of behavior on excluded fields.
