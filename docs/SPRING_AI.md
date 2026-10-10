# Spring AI: verified kewei-ai-agent integration

[English](SPRING_AI.md) | [简体中文](SPRING_AI.zh-CN.md)

The actual integration is now merged in [kewei-ai-agent PR #15](https://github.com/keweizhuazuki/kewei-ai-agent/pull/15), verified against Spring AI **2.0.0-M2**. It is an **opt-in application adapter**, not a separately published general-purpose Spring AI module. Other framework versions are not implied to work.

## Reproduce the integration tests

```sh
git clone --recurse-submodules https://github.com/keweizhuazuki/kewei-ai-agent.git
cd kewei-ai-agent
# For an existing clone:
git submodule update --init --recursive
./mvnw -Ptooltape clean "-Dtest=ToolTape*,ToolCallAgentTest,ToolRegistrationTest" test
./mvnw -Ptooltape -DskipTests package
```

On Windows, use `.\mvnw.cmd` for the same commands. Java 21 is required by the application. The profile adds the pinned `vendor/tooltape` sources and the app's adapter/test source roots; its default build has no ToolTape dependency. Do not replace the submodule pin with an unreviewed moving branch or invent Maven Central coordinates.

The merged integration passed **22 focused tool/adapter tests with packaging**, plus the application's default **90-test backend suite**. These are separate checks; the focused invocation is not the whole app suite.

## What is actually exercised?

[`ToolTapeAgentReplayTest`](https://github.com/keweizhuazuki/kewei-ai-agent/blob/74a73add6da6243bbc431ebb190327312b3be81c/src/tooltape-test/java/com/kiwi/keweiaiagent/tooltape/ToolTapeAgentReplayTest.java) runs `think()` with a local fake ChatModel and then the real `ToolCallAgent.act()` / Spring AI tool manager. Recording executes the synthetic tool once; replay returns the fixture without another live callback. No paid model or real external tool is used by this regression.

The adapter preserves `ToolDefinition`, `ToolMetadata` and the original JSON input. During recording only, it forwards `ToolContext` in memory; context is never submitted to the recorder. Replay retains definition/metadata and never retains or invokes the executable live callback.

Source locations in kewei-ai-agent:

- `src/tooltape/java/com/kiwi/keweiaiagent/tooltape/ToolTapeSession.java`
- `src/tooltape/java/com/kiwi/keweiaiagent/tooltape/ToolTapeBeanPostProcessor.java`
- `src/tooltape/java/com/kiwi/keweiaiagent/tooltape/ToolTapeConfiguration.java`
- `src/tooltape-test/java/com/kiwi/keweiaiagent/tooltape/`

The BeanPostProcessor wraps the `allTools` and `memoryTools` callback arrays exported by ToolRegistration. These feed ManusSessionService and the actual ToolCallAgent dispatch path.

## Runtime controls and privacy

Compilation with `-Ptooltape` does not turn recording on. Runtime mode defaults to `off`.

- `kewei.tooltape.mode`: `off`, `record`, or `replay`
- `kewei.tooltape.path`: required for record/replay; record creates a new file
- `kewei.tooltape.input-fields`: comma-separated top-level scalar allowlist; empty by default
- `kewei.tooltape.capture-output`: false by default; true explicitly permits the entire returned String under `result`

Output capture is a privacy decision: arbitrary tool text cannot be reliably sanitized. Use reviewed synthetic/test data or a domain-specific safe projection. Metadata-only recording is valid for inspection, but replay **fails clearly** when the required result was not captured; it does not invent a placeholder or fall back to a live tool. Null is a distinct captured result and is preserved.

## Operational boundaries

- This profile is a single serial debug/test session across the wrapped callbacks, not per-user/per-conversation production recording. Concurrent or recursive calls are rejected.
- The recording closes with the Spring session/application lifecycle. A running/interrupted v2 recording without END is inspectable but cannot be safely replayed.
- Tool replay does **not** replay the ChatModel, startup hooks or other external effects outside callbacks. Starting the real app in replay mode can still call its configured model. Use the fake-model regression above for a genuinely offline test.
- A tool's returned error String is an OK tool return unless explicitly mapped by the adapter. Recorded thrown exceptions become `RecordedToolException` at the core boundary and are normalized by the application adapter; original exception-subclass branching is not automatically reproduced.
- The repo owner has not selected an open-source license for ToolTape. Integration of their own repositories does not grant third-party reuse rights.

## Policy hardening migration

The post-v0.2 quality pass changes policy semantics from `scalar-v1` to `scalar-v2`: credential-name matching removes separators before checking names, closing newline/format-character bypasses. Old v2 policy fingerprints therefore deliberately fail replay under the new policy. Review and re-record fixtures with the same tested application/version; do not replace a hash manually to bypass verification. Legacy v1 remains readable but has no policy evidence.

The application's Git submodule pin is the source of truth for which semantics it uses. Update that pin and rerun its integration suite together; never assume an application automatically follows ToolTape main.
