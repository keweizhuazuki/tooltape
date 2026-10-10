# 机器可读的 CI 报告

[English](CLI_REPORTS.md) | [简体中文](CLI_REPORTS.zh-CN.md)

```sh
java -jar target/tooltape.jar validate --json recording.tape
java -jar target/tooltape.jar diff --json expected.tape actual.tape
```

仅这两个命令支持 JSON，`--json` 必须紧跟命令。结构化调用始终向 stdout 输出一行规范 JSON 及换行，ToolTape 本身不输出 stderr。JVM 参数、代理或启动诊断仍可能先行输出，请勿将其日志注入报告流。应解析 JSON，不要依赖属性排列。命令不联网，也不会调用真实工具。

实际生成的示例：[关闭会话校验](../examples/reports/validate.json)、[事件一致](../examples/reports/match.json)、[缺失文件错误](../examples/reports/read-error.json)。前两项使用仓库自带天气合成数据；错误示例故意传入不存在的第二个文件。

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
| 2 | 命令无效、读入失败、格式错误、v2 会话未关闭或 v2 策略不兼容 |

JSON 比较的 `compatibility` 按以下优先级决定：

1. `incomplete`：任一 v2 缺少有效 END，退出 2。
2. `policy-mismatch`：两个 v2 的策略标识不同，退出 2。
3. `unverified`：任一为 v1，依据事件是否一致退出 0/1，附 `warnings: ["LEGACY_UNVERIFIED"]`。
4. `verified`：两个 v2 都关闭且策略标识相同，依据事件是否一致退出 0/1。

这里的 verified 仅检查录制结构和策略标识，不证明来源真实性、业务成功或未捕获字段相同。JSON 比较比原有仅比较事件的文本命令更严格。即使兼容性使退出码为 2，仍提供 `eventsEqual` 和 `firstDivergence`。

错误使用固定 `error.code` 和安全的 `error.message`，代码为 `INVALID_ARGUMENTS`、`IO_ERROR`、`INVALID_TAPE`、`INCOMPLETE_TAPE`、`POLICY_MISMATCH`。无法确定的摘要字段不输出，不会虚构空录制。成功的非旧格式报告包含 `warnings: []`。

Java 读取接口新增 `InvalidTapeException extends IOException`，区分格式/容量错误与文件系统 I/O 错误。原先捕获 `IOException` 的代码仍兼容。

## 保存报告并保留退出码

POSIX shell（包括启用 `set -e` 的脚本）：

```sh
status=0
java -jar target/tooltape.jar diff --json expected.tape actual.tape > report.json || status=$?
cat report.json
exit "$status"
```

PowerShell：

```powershell
java -jar target/tooltape.jar diff --json expected.tape actual.tape > report.json
$status = $LASTEXITCODE
Get-Content report.json
exit $status
```

不要把全部非零退出码转换为成功；应区分回归差异（1）与不可用比较（2）。错误时重定向 stdout 仍能保存 JSON。不要直接把多个报告追加成一个 JSON 文档，可使用独立文件或明确按逐行 JSON 处理。

无依赖构建会启动真实 Java 子进程检查 stdout、stderr 与退出码，在 Linux/macOS/Windows × Java 17/21 CI 中执行，无需模型凭据。
