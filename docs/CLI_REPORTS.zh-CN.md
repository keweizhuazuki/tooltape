# 机器可读的 CI 报告

[English](CLI_REPORTS.md) | [简体中文](CLI_REPORTS.zh-CN.md)

```sh
java -jar target/tooltape.jar validate --json recording.tape
java -jar target/tooltape.jar diff --json expected.tape actual.tape
```

这两个命令使用下述单份 JSON 报告契约；[清单校验](BATCH_REPORTS.zh-CN.md)使用独立批量外层。`--json` 必须紧跟命令。结构化调用始终向 stdout 输出一行规范 JSON 及换行，ToolTape 本身不输出 stderr。JVM 参数、代理或启动诊断仍可能先行输出，请勿将其日志注入报告流。应解析 JSON，不要依赖属性排列。命令不联网，也不会调用真实工具。

实际生成的示例：[关闭会话校验](../examples/reports/validate.json)、[事件一致](../examples/reports/match.json)、[缺失文件错误](../examples/reports/read-error.json)。前两项使用仓库自带天气合成数据；错误示例故意传入不存在的第二个文件。

## 在 CI 中要求 v2

当项目已经迁移到带策略标识和关闭记录的 v2 数据后，可防止旧格式文件悄悄通过同一个校验任务：

```sh
java -jar target/tooltape.jar validate --json --require-v2 recording.tape
java -jar target/tooltape.jar diff --json --require-v2 expected.tape actual.tape
```

两个 JSON 命令均支持此选项，位置必须紧跟 `--json`。已成功解析的旧 v1 输入会返回 `outcome: "error"`、`error.code: "LEGACY_TAPE"` 和退出码 **2**，即使事件一致也不例外。不加选项时，原有旧格式行为保持不变。重复或错位选项、缺少输入、未配合 `--json` 使用均为用法错误；文件名若恰为 `--require-v2`，请使用 `./--require-v2`。

报告仍保留摘要及 `LEGACY_UNVERIFIED` 警告。比较仍提供 `compatibility: "unverified"`、`eventsEqual` 和 `firstDivergence`。两个输入必须先完成读取和格式校验，不能因为第一个输入为旧格式就掩盖第二个输入的读取或格式错误。未关闭 v2 会话及 v2 策略不一致仍保留原有优先级和错误码。可比较的已关闭 v2 仍按事件是否一致返回 0/1。包含已录制工具异常的关闭会话可以通过，因为工具失败本身就是有用的回归数据。

仓库的分发验证任务通过打包后的 JAR，使用该选项校验自带 v2 数据，避免用旧格式替换后仍悄悄通过消费者检查。

这是**格式准入检查**，不证明来源真实、隐私安全或业务成功，也不验证录制指纹是否使用当前算法或符合应用所需策略。策略身份校验应使用显式传入 `CapturePolicy` 的 `Replay`。含旧算法有效指纹的已关闭 v2 仍可能通过校验，两份指纹相同的此类录制也可比较。

迁移 v1 时，应审阅捕获字段，在受控运行中用 v2 录制器重新录制并成功关闭，然后审阅产物。不要凭空添加 `META` 或 `END`，把缺失证据伪装为完整会话。重新录制可能调用真实工具；工具有副作用时，应使用合成/桩工具或另行获准的受控环境。

[实际生成的旧格式拒绝示例](../examples/reports/legacy-rejected.json)使用仓库自带的合成 v1 天气录制。`reportVersion` 仍为 1，对象结构不变；`LEGACY_TAPE` 是仅在启用该选项后使用的新错误码。若消费者穷举错误码白名单，启用前需接受这个新增值。

## 报告契约 v1

全部报告包含 `reportVersion: 1`、`tooltapeVersion`、`command`、`outcome`、整数 `exitCode`。未知命令的 `command` 为 `null`。`outcome` 为 `valid`、`match`、`divergence` 或 `error`。

校验报告有 `summary`；比较报告有 `expected`、`actual`、`eventsEqual`、`firstDivergence`、`compatibility`。每个录制摘要包含：

- `schemaVersion`：1 或 2
- `eventCount`：非负事件数
- `completion`：`closed`、`incomplete` 或旧格式 `unknown`
- `policyId`：录制内策略 SHA-256 标识，旧格式为 `null`

`firstDivergence` 为 `null` 或 `{ "eventIndex": 0, "field": "input" }`，索引从 0 开始。按 `tool`、`input`、`status`、`result` 的顺序检查事件，最后检查 `eventCount`。数量差异指向首个缺失或额外事件。JSON 输入/输出采用规范化比较，忽略耗时和来源标签。

报告不包含捕获值、工具名、来源标签、路径、异常文本或 JSON 指针字段名，字段名本身也可能是敏感信息。仅在允许终端或日志显示录制内容时使用原有文本 `diff`/`inspect`。策略哈希及事件数仍会暴露部分配置/会话结构，公开前仍应审阅。

## 退出码与兼容性

| 退出码 | 含义 |
| --- | --- |
| 0 | 录制有效，或可比较的事件一致 |
| 1 | 找到首处事件差异 |
| 2 | 命令无效、读入失败、格式错误、v2 会话未关闭或 v2 策略不兼容；`--require-v2` 拒绝旧格式 |

JSON 比较的 `compatibility` 按以下优先级决定：

1. `incomplete`：任一 v2 缺少有效 END，退出 2。
2. `policy-mismatch`：两个 v2 的策略标识不同，退出 2。
3. `unverified`：任一为 v1，默认依据事件是否一致退出 0/1（启用 `--require-v2` 则为 `LEGACY_TAPE`/2），附 `warnings: ["LEGACY_UNVERIFIED"]`。
4. `verified`：两个 v2 都关闭且策略标识相同，依据事件是否一致退出 0/1。

这里的 verified 仅检查录制结构和策略标识，不证明来源真实性、业务成功或未捕获字段相同。JSON 比较比原有仅比较事件的文本命令更严格。即使兼容性使退出码为 2，仍提供 `eventsEqual` 和 `firstDivergence`。

错误使用固定 `error.code` 和安全的 `error.message`，代码为 `INVALID_ARGUMENTS`、`IO_ERROR`、`INVALID_TAPE`、`INCOMPLETE_TAPE`、`POLICY_MISMATCH` 和可选模式的 `LEGACY_TAPE`。无法确定的摘要字段不输出，不会虚构空录制。成功的非旧格式报告包含 `warnings: []`。

Java 读取接口新增 `InvalidTapeException extends IOException`，区分格式/容量错误与文件系统 I/O 错误。原先捕获 `IOException` 的代码仍兼容。

## 保存报告并保留退出码

POSIX shell（包括启用 `set -e` 的脚本）：

```sh
status=0
java -jar target/tooltape.jar diff --json --require-v2 expected.tape actual.tape > report.json || status=$?
cat report.json
exit "$status"
```

PowerShell：

```powershell
java -jar target/tooltape.jar diff --json --require-v2 expected.tape actual.tape > report.json
$status = $LASTEXITCODE
Get-Content report.json
exit $status
```

不要把全部非零退出码转换为成功；应区分回归差异（1）与不可用比较（2）。错误时重定向 stdout 仍能保存 JSON。不要直接把多个报告追加成一个 JSON 文档，可使用独立文件或明确按逐行 JSON 处理。

无依赖构建会启动真实 Java 子进程检查 stdout、stderr 与退出码，在 Linux/macOS/Windows × Java 17/21 CI 中执行，无需模型凭据。
