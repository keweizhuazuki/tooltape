# Spring AI：已验证的 kewei-ai-agent 接入

[English](SPRING_AI.md) | [简体中文](SPRING_AI.zh-CN.md)

真实接入已在 [kewei-ai-agent PR #15](https://github.com/keweizhuazuki/kewei-ai-agent/pull/15) 合并，验证框架版本为 **Spring AI 2.0.0-M2**。这是应用内可选适配器，不是已发布的通用 Spring AI 模块，不能据此推断其他版本兼容。

## 复现测试

```sh
git clone --recurse-submodules https://github.com/keweizhuazuki/kewei-ai-agent.git
cd kewei-ai-agent
git submodule update --init --recursive
./mvnw -Ptooltape clean "-Dtest=ToolTape*,ToolCallAgentTest,ToolRegistrationTest" test
./mvnw -Ptooltape -DskipTests package
```

Windows 改用 `.\mvnw.cmd`，应用要求 Java 21。可选 profile 编译固定 Git 子模块 `vendor/tooltape`、适配器和测试源码；默认构建不依赖 ToolTape。不要改用未审查的浮动分支，也没有 Maven Central 制品坐标可直接下载。

接入已通过 **22 项工具/适配器专项测试和打包**，应用默认后端另有 **90 项测试通过**。专项命令不是全量测试。

## 实际验证了什么？

[`ToolTapeAgentReplayTest`](https://github.com/keweizhuazuki/kewei-ai-agent/blob/74a73add6da6243bbc431ebb190327312b3be81c/src/tooltape-test/java/com/kiwi/keweiaiagent/tooltape/ToolTapeAgentReplayTest.java) 使用本地假 ChatModel 执行 `think()`，随后进入真实 `ToolCallAgent.act()` 和 Spring AI 工具管理器。录制只执行一次合成工具，回放不再次调用真实回调。该回归不使用付费模型或外部真实工具。

适配器保留 ToolDefinition、ToolMetadata 与原始 JSON 参数。仅在录制时于内存中传递 ToolContext，不将其交给录制器；回放只保留定义和元数据，不保留或执行真实回调。

实现位于应用的 `src/tooltape/java/com/kiwi/keweiaiagent/tooltape/`，测试位于 `src/tooltape-test/java/`。BeanPostProcessor 包装 ToolRegistration 的 `allTools` 和 `memoryTools` 数组，它们进入 ManusSessionService 和实际工具派发路径。

## 配置与隐私

编译时启用 `-Ptooltape` 不会自动开始录制，运行模式默认关闭。

- `kewei.tooltape.mode`：`off`、`record`、`replay`
- `kewei.tooltape.path`：录制/回放时必填；录制只创建新文件
- `kewei.tooltape.input-fields`：逗号分隔的顶层标量字段，默认空
- `kewei.tooltape.capture-output`：默认 false；设为 true 会明确允许将完整返回字符串保存为 `result`

任意工具文本无法可靠自动脱敏，输出捕获需自行审阅。优先使用合成数据或领域安全投影。只记录元数据可以用于查看，但缺少结果时回放会明确失败，不伪造占位结果，也不回退调用真实工具。已明确录制的 null 结果会原样保留。

## 运行边界

- 当前是包装回调共享的单个串行调试/测试会话，不是按用户或对话隔离的生产录制；并发与递归调用被拒绝。
- 会话随 Spring 应用生命周期关闭。尚未关闭或异常中断、缺少 END 的 v2 文件可查看，但不可安全回放。
- 工具回放不包含 ChatModel、启动钩子和工具边界之外的副作用；真实应用开启 replay 仍可能调用已配置模型。完全离线请使用上述假模型测试。
- 工具返回错误字符串仍属于正常返回，除非适配器显式映射。抛出的异常在核心回放中变为 RecordedToolException，再由应用适配器统一处理；不会自动还原原异常子类分支。
- ToolTape 尚未选择开源许可证，作者将自己的两个仓库接入不等于向第三方授予复用许可。

## 策略加固迁移

本轮加固将策略语义从 `scalar-v1` 改为 `scalar-v2`：检查凭据字段前先去掉分隔符，堵住换行和格式字符绕过。旧 v2 指纹因此会被新策略拒绝。应在审阅后用相同测试应用/版本重新录制，不要手改哈希绕过校验。旧 v1 仍可读取，但本来就没有策略证据。

应用使用哪个策略以其 Git 子模块固定提交为准。升级指针时应同时重跑集成测试，不要假定应用自动跟随 ToolTape main。
