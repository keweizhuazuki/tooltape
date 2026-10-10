<div align="center">

# ToolTape
### 一次故障，一份可重复运行的回归测试。

**录制串行工具调用 · 离线回放 · 定位首次差异**

[English](README.md) | [简体中文](README.zh-CN.md)

</div>

ToolTape 把 Java 工具调用边界变成可复用的测试数据。录制调用顺序、明确允许保存的参数、结果及异常类型，再用离线替身执行编排逻辑；调用顺序或参数首次偏离时立即报错。无需服务器、账户、API Key 或运行时依赖。

> v0.1 专注串行工具编排，不录制或回放模型、不处理流式输出或并行调用，也不隔离整个 Java 进程。外部副作用必须经由 `Tool` 边界；默认不保存输入输出字段，需明确允许安全的标量字段。

## 解决什么问题？

- 无需在线服务复现故障：已录制的超时转换成 `RecordedToolException`，后续调用返回录制结果。
- 在工具边界发现回归：检查工具名称、允许保存的参数、缺失及额外调用，关闭回放时验证完整消费。
- 本地审阅数据：命令行校验、差异比较与时间线界面均无需后端。
- 保留现有测试：零依赖核心支持普通 Java 和 JUnit；Spring AI 接入说明基于真实项目调用路径。

## 快速开始

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

示例仅创建新文件。重复执行请选择新路径，如 `RetryRegression target/weather-2.tape`；不会静默覆盖已有数据。

### 实际运行输出

```text
PASS: recorded timeout + retry, replayed offline; live calls=2
First divergence at event 0: arguments
expected: {"city":"Hangzhou"}
actual:   {"city":"Shanghai"}
```

两次真实调用仅发生在录制阶段，回放阶段不增加真实调用；修改城市的场景用于演示回归定位。

## 接入业务代码

```java
CapturePolicy policy = CapturePolicy.allow(Set.of("city"), Set.of("temperature"));
Tool live = (name, arguments) -> weatherService.lookup(arguments);
try (Recorder recorder = new Recorder(Path.of("failure.tape"), live, policy)) {
    runMyWorkflow(recorder);
}
try (Replay replay = new Replay(Tape.read(Path.of("failure.tape")), policy)) {
    runMyWorkflow(replay);
}
```

`Tool` 输入输出为 JSON 兼容的 `Map<String,Object>`。回放返回脱敏后的结果，因此需允许业务逻辑依赖的字段。只保存明确允许的顶层标量；嵌套对象和数组替换为 `[REDACTED]`，凭据类字段名始终排除。未保存的输入字段无法参与回归校验。

录制异常通过 `Replay.RecordedToolException.originalType()` 提供原类型，不会实例化任意异常类。若业务逻辑依赖异常子类，请在领域适配器中统一错误类型；[可执行重试示例](examples/RetryRegression.java)对此有明确处理。

### JUnit 自动回归

```sh
mvn test
```

[JUnit 示例](src/test/java/dev/tooltape/RegressionJUnitTest.java)覆盖已提交的故障数据、完整回放断言和参数变化。运行 `mvn install` 后可在其他 Maven 项目中使用本地坐标 `dev.tooltape:tooltape:0.1.0-SNAPSHOT`；该坐标尚未远程发布。

[Spring AI / kewei-ai-agent 接入设计](docs/SPRING_AI.md)仅为设计说明，尚无编译验证的专用适配器。

## 查看与比较

```sh
java -jar target/tooltape.jar validate fixtures/weather-retry.tape
java -jar target/tooltape.jar inspect fixtures/weather-retry.tape
java -jar target/tooltape.jar diff fixtures/weather-retry.tape target/weather.tape
```

退出码：`0` 有效或相同，`1` 首次语义差异，`2` 文件格式、I/O 或用法错误。耗时仅展示，不参与比较。

本地打开 [viewer/index.html](viewer/index.html)，选择 `.tape` 文件，即可筛选失败、查看参数结果，或载入第二份文件定位差异。界面使用本地 File API，并通过 CSP 禁止网络连接。移动端文件选择尚未验证，没有独立移动应用。浏览器截图尚未取得，不以模拟图片替代真实截图。

## 安全边界

默认不保存输入输出字段、异常消息或堆栈。允许字段的值仍需人工审阅，任意文本无法自动保证不含秘密。文件继承操作系统权限，不加密。录制失败之前，真实工具的副作用可能已经发生，不能盲目重试。回放不是 JVM 沙箱，工具边界之外的副作用仍需由应用隔离。

详见 [安全说明](SECURITY.md)和[格式规范](docs/FORMAT.md)。

## 与现有工具的关系

[Langfuse](https://github.com/langfuse/langfuse)面向 LLM 可观测与评估，[Promptfoo](https://github.com/promptfoo/promptfoo)面向提示词和 Agent 测试，[WireMock](https://github.com/wiremock/wiremock)提供 HTTP 模拟。ToolTape 聚焦 Java 工具边界测试数据与离线回放，不替代这些项目、不声称首创，v0.1 也不支持导入它们的格式。

## 验证状态

- 本地 Linux/JDK 21：45 项无依赖检查及 3 项 JUnit 测试通过；CLI 和重试示例已执行。本地使用编译器模块及 source/target 17，Java 17 API 兼容由完整 JDK CI 验证。
- [已验证的 CI](https://github.com/keweizhuazuki/tooltape/actions/runs/38032949639)：Linux、Windows、macOS × JDK 17/21 六组构建、Maven/JUnit 和界面测试全部通过。首次 Windows CI 发现 Git 转换测试数据为 CRLF，已用 `.gitattributes` 保留 LF，重跑通过。
- 界面：78 项解析与规范化检查、23 项 DOM 模型交互检查通过。浏览器启动受限，截图及真实浏览器渲染仍未验证，不使用模拟截图。

## 本次已完成（v0.1）

- 版本化格式、规范 JSON、文件大小限制及严格输入校验
- 默认最小记录、凭据字段排除、不保存异常消息
- 串行录制、离线回放、缺失与额外调用检测、首次差异
- CLI、JUnit 数据样例与可执行重试回归
- 本地时间线界面、跨平台构建脚本与已验证的 CI 矩阵
- 修复 Windows 测试数据换行问题；英文默认 README 与独立中文说明

## 下次升级

1. 对固定 Spring AI 版本编译并集成测试专用适配器，保留 ToolContext，定义错误字符串映射。
2. 增加捕获策略标识及数据来源，提示策略漂移，设计兼容演进的完成标记。
3. 增加精确 JSON 路径差异、界面无障碍及浏览器 CI、可复现发布包和校验值。
4. 接入第二个真实编排项目后，再评估模型或并行回放。

每次实质更新同步维护英文和中文的“已完成／下次升级”记录。规划不等于已实现功能。

## 许可

尚未选择许可证，公开可见不等于授予开源许可。超出适用法律与 GitHub 条款已有权利的复用或再分发，请先联系作者。
