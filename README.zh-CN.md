<div align="center">

# ToolTape
### 一次故障，一份可重复运行的回归测试。

**录制串行工具调用 · 离线回放 · 定位首次差异**

[English](README.md) | [简体中文](README.zh-CN.md)

</div>

ToolTape 把 Java 工具调用边界变成可复用的测试数据。录制调用顺序、明确允许保存的参数、结果及异常类型，再用离线替身执行编排逻辑；调用顺序或参数首次偏离时立即报错。无需服务器、账户、API Key 或运行时依赖。

> v0.2 专注串行工具编排，不录制或回放模型、不处理流式输出或并行调用，也不隔离整个 Java 进程。外部副作用必须经由 `Tool` 边界；默认不保存输入输出字段，需明确允许安全的标量字段。

## 真实录制，清晰呈现

![ToolTape v2 会话时间线与已录制的工具异常](docs/images/viewer-session.png)

图中为仓库自带 v2 重试数据的真实 Chromium 截图。[来源记录](docs/images/viewer-session.json)包含源码提交、浏览器版本及文件哈希；[对应浏览器测试](https://github.com/keweizhuazuki/tooltape/actions/runs/38039531019)已通过五组引擎/视口配置下的全部 153 项有效检查。

<details>
<summary>查看首次差异比较与移动布局</summary>

![离线查看器定位工具返回结果的首次差异](docs/images/viewer-diff.png)

<img src="docs/images/viewer-mobile.png" alt="390px Chromium 移动模拟中的 ToolTape 查看器" width="390">

移动截图来自浏览器模拟，不代表真机验证。PNG 为浏览器直接输出，未进行图像合成。

</details>

## 解决什么问题？

- 无需在线服务复现故障：已录制的超时转换成 `RecordedToolException`，后续调用返回录制结果。
- 在工具边界发现回归：检查工具名称、允许保存的参数、缺失及额外调用，关闭回放时验证完整消费。
- 本地审阅数据：命令行校验、差异比较与时间线界面均无需后端。
- 保留现有测试：零依赖核心支持普通 Java 和 JUnit；Spring AI 接入说明基于真实项目调用路径。

## 快速开始

### 预构建 CLI（Java 17+）

CI 提供经过验证的 JAR 和 SHA-256 校验文件，尚未发布到 Maven Central。带版本标签的预发布若已提供，可在[发布列表](https://github.com/keweizhuazuki/tooltape/releases)中查看。

当前经过验证的 v0.2 构建可从对应成功的 CI 下载：

```sh
gh run download 38036648099 --repo keweizhuazuki/tooltape --name tooltape-dist-62095438d459dc693ef946fa88b0bca0b6f95423
sha256sum -c SHA256SUMS
java -jar tooltape-0.2.0.jar --version
java -jar tooltape-0.2.0.jar inspect path/to/your.tape
```

CI 产物保留 30 天，下载可能需要登录 GitHub CLI；下方源码构建方式长期有效。macOS 使用 `shasum -a 256 -c SHA256SUMS`；Windows 将 `(Get-FileHash tooltape-0.2.0.jar -Algorithm SHA256).Hash` 与 SHA256SUMS 中的值比较，一致后再运行。

### 从源码构建

安装完整 **JDK 17 或 21** 与 Git。克隆目录同时包含本地查看器。

#### Linux / macOS

```sh
git clone https://github.com/keweizhuazuki/tooltape.git
cd tooltape
./build.sh
java -cp target/tooltape.jar:target/example-classes RetryRegression
java -jar target/tooltape.jar inspect target/weather.tape
```

#### Windows PowerShell

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
First divergence at event 0: arguments at /city
expected: {"city":"Hangzhou"}
actual:   {"city":"Shanghai"}
```

两次真实调用仅发生在录制阶段，回放阶段不增加真实调用；修改城市的场景用于演示回归定位。

## 适合 CI 的报告

```sh
java -jar target/tooltape.jar validate --json fixtures/weather-retry-v2.tape
java -jar target/tooltape.jar diff --json expected.tape actual.tape > report.json
```

JSON 报告采用版本化契约和明确退出码：**0** 有效或匹配，**1** 首处事件差异，**2** 无效输入、未关闭会话或不兼容的 v2 捕获策略。报告及错误不包含捕获值、工具名、文件路径或 JSON 字段名。旧 v1 比较会明确标注策略及完整性未验证。原有文本命令保持不变，可能显示录制内容。

[报告结构、兼容规则及跨平台命令 →](docs/CLI_REPORTS.zh-CN.md)

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

新录制采用 v2 格式；加固后的 scalar-v2 策略会明确拒绝旧 v2 指纹，需审阅后重新录制，详见[迁移说明](docs/SPRING_AI.zh-CN.md#策略加固迁移)。录制保存允许字段配置的 SHA-256 标识及可选的静态来源标签（`new Recorder(path, tool, policy, "my-workflow")`）。回放会在任何调用前拒绝策略变化或缺少有效关闭标记的数据。关闭录制不代表业务成功；旧 v1 数据仍可读取，但无法校验策略与完整性。

录制异常通过 `Replay.RecordedToolException.originalType()` 提供原类型，不会实例化任意异常类。若业务逻辑依赖异常子类，请在领域适配器中统一错误类型；[可执行重试示例](examples/RetryRegression.java)对此有明确处理。

### JUnit 自动回归

```sh
mvn test
```

[JUnit 示例](src/test/java/dev/tooltape/RegressionJUnitTest.java)覆盖已提交的故障数据、完整回放断言和参数变化。运行 `mvn install` 后可在其他 Maven 项目中使用本地坐标 `dev.tooltape:tooltape:0.2.0`；该坐标尚未远程发布。

[已验证的 Spring AI / kewei-ai-agent 接入](docs/SPRING_AI.zh-CN.md)已合并：假模型驱动真实 think → act 工具派发，24 项专项测试与打包通过。默认关闭，固定源码版本，ToolContext 仅在内存传递；未捕获输出时明确失败。它不是独立发布的通用框架模块。

## 查看与比较

```sh
java -jar target/tooltape.jar validate fixtures/weather-retry.tape
java -jar target/tooltape.jar inspect fixtures/weather-retry.tape
java -jar target/tooltape.jar diff fixtures/weather-retry.tape target/weather.tape
```

退出码：`0` 有效或相同，`1` 首次语义差异，`2` 文件格式、I/O 或用法错误。耗时与元数据仅展示，不参与事件比较。应先执行 validate；语义相同不等于录制完整。输入输出差异会附带 `/city` 等 JSON 指针。

本地打开 [viewer/index.html](viewer/index.html)，选择 `.tape` 文件，即可筛选失败、查看参数结果，或载入第二份文件定位差异。界面使用本地 File API，并通过 CSP 禁止网络连接。CI 在 Ubuntu 上检查 Chromium、Firefox 和 WebKit，并覆盖 390px Chromium/WebKit 移动模拟；Linux WebKit 不等于品牌 Safari 或真实 iOS，原生文件选择对话框尚未验证，也没有独立移动应用。截图来自真实浏览器，不使用模拟图。

## 安全边界

默认不保存输入输出字段、异常消息或堆栈。允许字段的值仍需人工审阅，任意文本无法自动保证不含秘密。新 POSIX 录制文件以仅属主可读写权限创建；Windows/非 POSIX 使用原生 ACL，仍需审阅。文件不加密。录制失败之前，真实工具的副作用可能已经发生，不能盲目重试。回放不是 JVM 沙箱，工具边界之外的副作用仍需由应用隔离。

详见 [安全说明](SECURITY.md)和[格式规范](docs/FORMAT.md)。

## 与现有工具的关系

[Langfuse](https://github.com/langfuse/langfuse)面向 LLM 可观测与评估，[Promptfoo](https://github.com/promptfoo/promptfoo)面向提示词和 Agent 测试，[WireMock](https://github.com/wiremock/wiremock)提供 HTTP 模拟。ToolTape 聚焦 Java 工具边界测试数据与离线回放，不替代这些项目、不声称首创，v0.2 也不支持导入它们的格式。

## 验证状态

- 本地 Linux/JDK 21：45 项无依赖检查及 24 项 JUnit 测试通过；另有 62 个真实 CLI 子进程场景通过，覆盖 JSON 隐私及退出码；CLI 和重试示例已执行。本地使用编译器模块及 source/target 17，Java 17 API 兼容由完整 JDK CI 验证。
- [已验证的 CI](https://github.com/keweizhuazuki/tooltape/pull/4/checks)：Linux、Windows、macOS × JDK 17/21 六组构建、Maven/JUnit 和界面测试全部通过。首次 Windows CI 发现 Git 转换测试数据为 CRLF，已用 `.gitattributes` 保留 LF，重跑通过。
- 界面：232 项解析与规范化检查、72 项 DOM 模型交互检查通过。[真实浏览器 CI](https://github.com/keweizhuazuki/tooltape/actions/runs/38039531019)通过 153 项 Chromium、Firefox 和 WebKit 桌面/移动模拟检查，跳过 2 项移动端重复截图，无失败或不稳定重试。

## 本次已完成（v0.2）

- 离线查看器覆盖明确的 Chromium/Firefox/WebKit 项目，并隔离不同引擎的截图和来源记录

- 提升小字号文字对比度，并通过浏览器计算样式检查默认、悬停、选中与焦点状态

- 时间线分页、清除筛选与退出比较时保留合理键盘焦点，隐藏文件输入不再形成不可见 Tab 停留点

- CI Actions 更新为经核对的官方版本并锁定完整提交，不持久保留 checkout 凭据（[维护说明](docs/CI_MAINTENANCE.zh-CN.md)）

- 版本化、隐私优先的 JSON 校验/比较报告，并在全部 CI 平台测试真实子进程退出码

- 安全加固：规范化凭据字段、限制指数展开和节点数、真实调用前预检容量、新 POSIX 文件仅属主可读写
- 真实可选 kewei-ai-agent 接入，输出需审阅后启用，回放不执行真实回调
- v2 策略绑定、来源标签与关闭标记；不完整或策略不同的数据在回放前直接拒绝
- 兼容旧 v1 数据，CLI 和查看器明确标记旧版及未完整关闭状态
- 以 RFC 6901 JSON 指针定位首个输入输出差异
- 通过 CI 运行真实 Chromium 桌面与移动模拟测试，并生成带来源信息的截图
- 固定 JAR 时间戳，二次构建逐字节对比，CI 构建产物附带校验文件
- 真实 Spring AI 项目的固定源码接入方式，不虚构 Maven Central 坐标

### v0.1 基础能力

- 零依赖串行录制回放、默认最小字段捕获
- 严格格式解析、CLI、JUnit 与可执行重试数据
- 本地时间线与已验证的 Linux、macOS、Windows × Java 17/21 矩阵
- Windows 换行修复，独立英文和中文说明

## 下次升级

1. 将经过应用测试的适配器提取为独立 Spring AI 模块，验证第二个应用和框架版本后再承诺广泛兼容。
2. 在保持报告 v1 契约兼容的前提下，增加可选的旧格式严格拒绝及多录制汇总。
3. 在跨引擎检查基础上增加辅助技术评审和真机测试；引擎模拟不代表原生 Safari/iOS 或 Android 兼容。
4. 设计安全的嵌套字段投影和按工具策略，避免任意字符串泄露或静默削弱指纹校验。
5. 在明确密钥管理方案后再设计可信来源认证；当前来源标签和校验和不能证明来源真实性。

每次实质更新同步维护两种语言的升级记录。规划不等于已实现；模型和并行回放仍不在当前范围。

## 许可

尚未选择许可证，公开可见不等于授予开源许可。超出适用法律与 GitHub 条款已有权利的复用或再分发，请先联系作者。
