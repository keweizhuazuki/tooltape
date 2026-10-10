# Privacy and security boundaries

- Default policy captures **no input/output fields**. It still records static tool identifier, sequence, timing, status and exception class name.
- Explicit allowlists capture selected top-level scalar values. Review those values for personal or confidential data. Arbitrary scalar content cannot be reliably classified as a secret.
- Credential-like field names (token, password, authorization, cookie, API key, secret, credential, private key) are excluded even when allowlisted. Nested objects/lists become `[REDACTED]`; project safe scalar fields in your adapter instead.
- The core does not automatically collect exception messages, stack traces, request headers, environment variables or model prompts. An explicitly allowlisted scalar can still contain any of this private content, even under an ordinary field name such as `message` or `prompt`. Field-name filtering is not content classification; review the values before enabling capture. Thrown exceptions contribute their class name, not their message or stack trace.
- No telemetry or network client is included. The viewer reads local files in your browser and has no upload feature.
- Replay does not accept a live tool delegate. Your orchestration code can still cause side effects outside the Tool interface: isolate those yourself. Replay is not a JVM sandbox.
- Recorded errors replay as RecordedToolException with originalType, not reflectively constructed arbitrary exception classes. Translate into domain errors in your adapter if your code branches on exception types.
- If persistence fails after a live tool executes, the recorder is poisoned and rejects further calls. The side effect may already have happened. Never automatically retry a failed record operation without checking the live system.
- New files on POSIX filesystems are created owner-read/write only (0600); existing files are never overwritten. Windows/non-POSIX providers retain native ACL behavior, which must be reviewed separately. Store fixtures in access-controlled locations and do not commit unreviewed tapes. ToolTape does not encrypt files.

Report security concerns privately to the repository owner via their public GitHub profile contact options; do not post real secrets in issues. No private disclosure channel is claimed until one is configured.

## v2 session safety

New recordings include a capture-policy fingerprint and static provenance label. Replay rejects a v2 tape if its policy differs or the recording lacks a valid END marker. An END marker means the recording scope closed; it does not prove business success, durability, authenticity or absence of tampering. Legacy v1 fixtures remain usable but cannot offer these checks. Source labels must be explicitly reviewed static identifiers, never user content or secrets.

## Bounded input and canonicalization

Both raw and canonical JSON are bounded to 16 MiB; canonical event payloads across a tape also share a 16 MiB budget. Numeric tokens and their plain-decimal expansions are capped at 10,000 characters, JSON values at 100,000 nodes, nesting at 64 levels, and exception labels at 1,024 UTF-16 units. A small exponent-encoded file cannot expand without bound. These are input/output limits, not a promise that JVM heap usage is exactly 16 MiB.

Oversized sanitized inputs that cannot fit the remaining tape budget fail before invoking the real tool. An oversized result is discovered after the tool returns, poisons the recording and prevents further calls; its external effect may already have happened.

Credential checks normalize separators before matching names. This changes the policy algorithm to scalar-v2 and intentionally invalidates older v2 fingerprints. Re-record reviewed fixtures rather than hand-editing policy metadata. Arbitrary allowed string values still require privacy review.

## CI report boundaries

JSON reports omit captured values and identifying paths/labels, but policy hashes, event counts and positional indexes still reveal configuration/session structure. Review reports before sharing them. `--require-v2` is a schema eligibility rule, not proof of authenticity, intended capture policy or business success.

`validate-batch` accepts at most 64 explicit files and processes them serially with the existing per-tape limits. It does not discover files or invoke tools. Its batch-only regular-file precheck follows symlinks; an attacker able to replace paths afterward can race that check, and filesystem operations can block. Use a controlled fixture directory and an external CI timeout. This check does not make arbitrary filesystem inputs safe or change the existing reader/single-command contracts.

## Jupiter fixture diagnostics

The optional Jupiter adapter requires a complete policy-matching v2 recording before running its callback and has no live delegate. Workflow code still runs with normal JVM privileges. Assertions, replay divergences and exceptions can expose captured values, field/tool names, filesystem paths or arbitrary workflow messages in test reports; these reports do not inherit the CLI JSON privacy contract. Review fixtures and log/artifact access before sharing. Each invocation reads the fixture again, so keep source files immutable during a reproducible test run. Caller-supplied streams remain caller-owned and may block; parser byte limits are not timeouts.

## Manifest input boundaries

UTF-8 batch manifests accept only a fixed versioned schema, at most 64 KiB and 64 relative path entries of at most 4,096 UTF-16 units each. The complete schema/path policy is checked before fixture reads. Errors do not echo manifest contents, paths, parser offsets or exception messages. Explicit UNC/device manifest operands and absolute/drive/UNC/URI-style entries are rejected; there is no URL loader or discovery mechanism. Relative `..` and symlinks are allowed, so this is not directory confinement and mounted/symlinked storage may still be remote. Treat manifests as reviewed filesystem-read instructions, not untrusted sandbox input. The regular-file race and external-timeout caveats above still apply.
