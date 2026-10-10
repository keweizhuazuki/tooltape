<div align="center">

# ToolTape
### One failure. A repeatable regression.<br>一次故障，一份可重复运行的回归测试。

**Record serial tool calls · Replay offline · Find the first divergence**<br>
**录制串行工具调用 · 离线回放 · 定位首次差异**

[Quick start / 快速开始](#quick-start--快速开始) · [API](#use-it-in-your-code--接入业务代码) · [Security / 安全](SECURITY.md) · [Format / 格式](docs/FORMAT.md) · [Roadmap / 升级计划](#next-upgrade--下次升级)

</div>

ToolTape turns a Java tool boundary into a reusable test fixture. Record the order, selected arguments, results and exception types once; rerun your orchestration against an offline stub that fails at the first unexpected tool call or argument. No server, account, API key or runtime dependency.

ToolTape 把 Java 工具调用边界变成可复用的测试数据。录制调用顺序、明确允许保存的参数、结果及异常类型，再用离线替身执行编排逻辑；调用顺序或参数首次偏离时立即报错。无需服务器、账户、API Key 或运行时依赖。

> **Scope / 边界:** v0.1 is for serial tool orchestration. It does not record/replay an LLM, stream tokens, reproduce concurrency, or sandbox your Java process. Your orchestration must route external effects through `Tool`. The default policy records no input/output fields; you opt in to safe scalar fields.
>
> v0.1 专注串行工具编排，不录制或回放模型、不处理流式输出或并行调用，也不隔离整个 Java 进程。外部副作用必须经由 `Tool` 边界；默认不保存输入输出字段，需明确允许安全的标量字段。

## Why use it? / 解决什么问题？

- **Reproduce a failure without a live service.** A recorded timeout becomes a controlled `RecordedToolException`; the next call returns the recorded response.<br>无需在线服务复现故障：已录制的超时会转换成明确的回放异常，后续调用返回录制结果。
- **Catch regressions at the boundary.** Tool name, allowlisted arguments, missing calls and extra calls are checked; closing a replay asserts complete consumption.<br>在工具边界发现回归：检查工具名称、允许保存的参数、缺失调用及额外调用，关闭回放时验证完整消费。
- **Review fixtures locally.** CLI validation/diff and a standalone timeline viewer need no backend.<br>本地审阅测试数据：命令行校验、差异比较和时间线界面均无需后端。
- **Keep existing tests.** A zero-dependency core works with ordinary Java and JUnit; Spring AI integration guidance is based on a real project's dispatch path.<br>保留现有测试：零依赖核心支持普通 Java 和 JUnit，Spring AI 接入说明基于真实项目调用路径。

## Quick start / 快速开始

Install a **full JDK 17 or 21** and Git. These are source builds; no Maven Central artifact or downloadable release is claimed yet.<br>
安装完整 **JDK 17 或 21** 与 Git。目前从源码构建，尚未发布 Maven Central 制品或独立安装包。

### Linux / macOS

```sh
git clone https://github.com/keweizhuazuki/tooltape.git
cd tooltape
./build.sh
java -cp target/tooltape.jar:target/example-classes RetryRegression
java -jar target/tooltape.jar inspect target/weather.tape
```

### Windows PowerShell

```powershell
git clone https://github.com/keweizhuazuki/tooltape.git
cd tooltape
./build.ps1
java -cp 'target/tooltape.jar;target/example-classes' RetryRegression
java -jar target/tooltape.jar inspect target/weather.tape
```

The demo creates `target/weather.tape` with CREATE_NEW. To rerun, choose a fresh path: `RetryRegression target/weather-2.tape`. Existing fixtures are never silently overwritten.<br>
示例仅创建新文件；重复执行时请使用新路径，例如 `RetryRegression target/weather-2.tape`，不会静默覆盖已有数据。

### Actual demo output / 实际运行输出

```text
PASS: recorded timeout + retry, replayed offline; live calls=2
First divergence at event 0: arguments
expected: {"city":"Hangzhou"}
actual:   {"city":"Shanghai"}
```

The two live calls happen during recording. Replay adds zero live calls. The changed-city variant intentionally demonstrates a regression.<br>
两次真实调用仅发生在录制阶段，回放阶段不增加真实调用；修改城市的场景用于演示回归定位。

## Use it in your code / 接入业务代码

```java
CapturePolicy policy = CapturePolicy.allow(
    Set.of("city"),                 // explicitly reviewed input fields
    Set.of("temperature")           // output fields needed by the test
);

Tool live = (name, arguments) -> weatherService.lookup(arguments);
try (Recorder recorder = new Recorder(Path.of("failure.tape"), live, policy)) {
    runMyWorkflow(recorder);         // your orchestration, including retries
}

try (Replay replay = new Replay(Tape.read(Path.of("failure.tape")), policy)) {
    runMyWorkflow(replay);           // no live implementation is passed in
}                                   // fails if recorded calls remain unused
```

`Tool` accepts and returns `Map<String,Object>` of JSON-compatible data. Recorded output is sanitized, so allow the fields your workflow requires. Only explicitly allowed top-level scalars are captured; nested values become `[REDACTED]`. Credential-like field names are always excluded. Excluded input fields cannot be regression-checked.

`Tool` 输入输出为 JSON 兼容的 `Map<String,Object>`。回放返回脱敏后的结果，因此需允许业务逻辑依赖的字段。只保存明确允许的顶层标量，嵌套对象和数组替换为 `[REDACTED]`，凭据类字段名始终排除。未保存的输入字段无法参与回归校验。

Recorded exceptions use `Replay.RecordedToolException.originalType()`, not the original Java subclass. Normalize errors in a domain adapter when business code branches on exception type. The executable [retry example](examples/RetryRegression.java) shows this explicitly.

录制异常通过 `Replay.RecordedToolException.originalType()` 提供原类型，不会实例化任意异常类。若业务逻辑依赖异常子类，请在领域适配器中统一错误类型；[可执行重试示例](examples/RetryRegression.java)对此有明确处理。

### JUnit / 自动回归

```sh
mvn test
```

[RegressionJUnitTest](src/test/java/dev/tooltape/RegressionJUnitTest.java) contains a checked-in failure fixture, complete replay assertion and changed-argument failure. `mvn install` installs the library locally for another Maven project; coordinates are `dev.tooltape:tooltape:0.1.0-SNAPSHOT` and are **not** remotely published.

[JUnit 示例](src/test/java/dev/tooltape/RegressionJUnitTest.java)覆盖已提交的故障数据、完整回放断言和参数变化。运行 `mvn install` 后可在其他 Maven 项目中使用本地坐标 `dev.tooltape:tooltape:0.1.0-SNAPSHOT`；该坐标尚未远程发布。

[Spring AI / kewei-ai-agent adapter guide →](docs/SPRING_AI.md)

## Inspect and compare / 查看与比较

```sh
java -jar target/tooltape.jar validate fixtures/weather-retry.tape
java -jar target/tooltape.jar inspect fixtures/weather-retry.tape
java -jar target/tooltape.jar diff fixtures/weather-retry.tape target/weather.tape
```

Exit codes: `0` valid/equal, `1` first semantic difference, `2` malformed file, I/O or usage error. Timing is shown but ignored in comparisons.<br>
退出码：`0` 有效或相同，`1` 首次语义差异，`2` 文件格式、I/O 或用法错误。耗时仅展示，不参与比较。

Open [viewer/index.html](viewer/index.html) from your local clone. Choose a `.tape`, filter failures, inspect arguments/results, or load a second tape to find the first difference. The viewer uses local File APIs and blocks network connections with CSP. On mobile, viewing the repository is possible but local file selection/browser behavior has not been validated; no mobile app is claimed.

在本地克隆目录打开 [viewer/index.html](viewer/index.html)，选择 `.tape` 文件，即可筛选失败、查看参数结果，或载入第二份文件定位差异。界面使用本地 File API，并通过 CSP 禁止网络连接。移动端可浏览仓库，但尚未验证文件选择兼容性，也没有独立移动应用。

## Where it fits / 与现有工具的关系

[Langfuse](https://github.com/langfuse/langfuse) provides LLM observability and evaluation; [Promptfoo](https://github.com/promptfoo/promptfoo) provides prompt/agent testing and evaluation workflows; [WireMock](https://github.com/wiremock/wiremock) mocks HTTP services. ToolTape is a small Java tool-boundary fixture/replay library. It does not replace these projects, claim a novel category, or import their formats in v0.1.

Langfuse 面向 LLM 可观测与评估，Promptfoo 面向提示词和 Agent 测试，WireMock 提供 HTTP 模拟。ToolTape 聚焦 Java 工具边界的测试数据与离线回放，不替代这些项目、不声称首创该类别，v0.1 也不支持导入它们的格式。

## Verification / 验证状态

- Local Linux/JDK 21: **45 dependency-free acceptance checks + 3 JUnit tests passed**; CLI and retry example executed. The environment lacks a complete `javac` launcher/`ct.sym`, so local compilation used the JDK compiler module with source/target 17; this alone is not proof of Java 17 API compatibility.<br>本地 Linux/JDK 21：45 项无依赖检查及 3 项 JUnit 测试通过，CLI 与重试示例已执行。环境缺少完整编译器启动器与 `ct.sym`，采用编译器模块和 source/target 17，不能单凭此证明 Java 17 API 兼容。
- CI defines full-JDK 17/21 builds on Linux, Windows and macOS plus Maven/JUnit. See [Actions](https://github.com/keweizhuazuki/tooltape/actions) for actual results; a workflow file is not a passing run.<br>CI 配置完整 JDK 17/21 的 Linux、Windows、macOS 构建及 Maven/JUnit；实际结果以 Actions 为准，配置存在不代表已通过。
- Browser screenshot and browser interaction checks are reported only when actually obtained.<br>浏览器截图与交互测试仅在实际完成后记录。

## Completed in v0.1 / 本次已完成

- Versioned tape reader/writer, canonical JSON and bounded file parsing / 版本化格式、规范 JSON 与文件大小限制
- Privacy-first scalar projection, credential-name exclusions, message-free exception records / 默认最小记录、凭据字段排除、不保存异常消息
- Serial recorder, offline replay, missing/extra-call detection, first divergence / 串行录制、离线回放、缺失与额外调用检测、首次差异
- CLI inspect/validate/diff, JUnit fixture, executable retry regression / CLI、JUnit 数据样例和可执行重试回归
- Local timeline viewer, cross-platform build scripts and CI matrix / 本地时间线界面、跨平台构建脚本和 CI 矩阵

## Next upgrade / 下次升级

1. Compile and integration-test a dedicated Spring AI ToolCallback adapter against pinned framework versions; preserve ToolContext behavior and define error-string projection.<br>对固定 Spring AI 版本编译并集成测试专用适配器，保留 ToolContext 行为，定义错误字符串映射。
2. Persist a capture-policy identifier and fixture provenance; warn about policy drift and add an explicit completion marker without breaking v1 readers.<br>增加捕获策略标识及数据来源信息，提示策略漂移，并设计兼容演进的完成标记。
3. Add precise JSON-path differences, viewer accessibility/browser CI and reproducible release archives with checksums.<br>增加精确 JSON 路径差异、界面无障碍与浏览器 CI，以及可复现发布包和校验值。
4. Validate with a second real orchestration project before expanding into model or parallel replay.<br>接入第二个真实编排项目后，再评估模型或并行回放。

Maintain this completed/next section with every meaningful release. Roadmap items are not implemented features.<br>
每次实质更新同步维护“已完成／下次升级”，规划不等同于已实现功能。

## License / 许可

No license has been selected yet. Public visibility is not an open-source license grant. Please ask the owner before reuse or redistribution beyond rights already provided by applicable law and GitHub's terms.<br>
尚未选择许可证，公开可见不等于授予开源许可。超出适用法律与 GitHub 条款已有权利的复用或再分发，请先联系作者。
