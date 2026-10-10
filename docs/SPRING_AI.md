# Spring AI integration design / 接入设计

This is an integration guide, **not a shipped or compiled Spring AI adapter**. The core executable and JUnit examples are framework-independent.

本文为接入设计，**不是已发布或编译验证的 Spring AI 适配器**。核心可执行示例和 JUnit 测试不依赖框架。

## Verified application boundary / 已核实的应用边界

In [kewei-ai-agent's ToolCallAgent](https://github.com/keweizhuazuki/kewei-ai-agent/blob/main/src/main/java/com/kiwi/keweiaiagent/agent/ToolCallAgent.java), `act()` calls `toolCallingManager.executeToolCalls(prompt, toolCallChatResponse)`. Constructor-supplied `ToolCallback[]` are the relevant wrapping point. The project does not use ToolTape's Map API today.

该项目的 `act()` 通过 `ToolCallingManager.executeToolCalls` 派发工具。应在构造器接收的 `ToolCallback[]` 边界包装回调，不能假定项目已使用 ToolTape Map API；目前未修改或集成其仓库。

## Adapter contract / 适配约定

1. Preserve each callback's `ToolDefinition` and `ToolMetadata`, including return-direct behavior. Forward `ToolContext` only during live recording when the callback supports it.
2. Parse the callback's JSON input to `Map<String,Object>` using your application's JSON library or `Json.object`.
3. Route `(definition.name(), arguments)` through `Recorder` when capturing, or through `Replay` in a deterministic orchestration test. Never construct a live side-effect callback on the replay path.
4. Call the original `ToolCallback.call(String)` only inside the live `Tool` implementation. Wrap its String return as `Map.of("text", result)` if text is needed; explicitly opt in to `text` after reviewing privacy. Prefer extracting safe scalar fields from structured results instead of recording arbitrary text.
5. On replay, convert the sanitized `text` field back to the callback String response. If the response field was not captured, fail clearly rather than substituting live output.
6. Normalize domain failures: some tools return an error String instead of throwing. These are recorded as OK unless your adapter explicitly maps them to an exception/error field. ToolTape does not infer failure from natural-language text.

中文要点：保留工具定义、元数据及 ToolContext；将 JSON 参数转为 Map；录制与回放使用不同边界，回放不得构造或调用邮件、文件等真实副作用工具；工具返回字符串需显式转换并审阅是否适合保存；错误字符串不会自动视为异常。

## Test the right thing / 正确测试范围

Start with a pre-scripted tool-call sequence or deterministic planner fixture. Running the real model again is an online evaluation and may change tool arguments/order; v0.1 does not replay model completions. The test validates the orchestration logic around the recorded tool boundary, not the model's quality.

从预设工具调用序列或确定性规划数据开始。重新调用真实模型属于在线评估，可能改变顺序或参数；v0.1 不回放模型响应。测试验证工具边界周围的编排逻辑，不评判模型质量。
