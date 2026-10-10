# Privacy and security boundaries

- Default policy captures **no input/output fields**. It still records static tool identifier, sequence, timing, status and exception class name.
- Explicit allowlists capture selected top-level scalar values. Review those values for personal or confidential data. Arbitrary scalar content cannot be reliably classified as a secret.
- Credential-like field names (token, password, authorization, cookie, API key, secret, credential, private key) are excluded even when allowlisted. Nested objects/lists become `[REDACTED]`; project safe scalar fields in your adapter instead.
- Exception messages, stack traces, request headers, environment variables and model prompts are never recorded by the core.
- No telemetry or network client is included. The viewer reads local files in your browser and has no upload feature.
- Replay does not accept a live tool delegate. Your orchestration code can still cause side effects outside the Tool interface: isolate those yourself. Replay is not a JVM sandbox.
- Recorded errors replay as RecordedToolException with originalType, not reflectively constructed arbitrary exception classes. Translate into domain errors in your adapter if your code branches on exception types.
- If persistence fails after a live tool executes, the recorder is poisoned and rejects further calls. The side effect may already have happened. Never automatically retry a failed record operation without checking the live system.
- Files inherit OS permissions/umask. Store sensitive fixtures in access-controlled locations; do not commit unreviewed tapes. ToolTape does not encrypt files.

Report security concerns privately to the repository owner via their public GitHub profile contact options; do not post real secrets in issues. No private disclosure channel is claimed until one is configured.

## v2 session safety

New recordings include a capture-policy fingerprint and static provenance label. Replay rejects a v2 tape if its policy differs or the recording lacks a valid END marker. An END marker means the recording scope closed; it does not prove business success, durability, authenticity or absence of tampering. Legacy v1 fixtures remain usable but cannot offer these checks. Source labels must be explicitly reviewed static identifiers, never user content or secrets.
