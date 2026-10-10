# 在 CI 中校验一组录制文件

[English](BATCH_REPORTS.md) | 简体中文

```sh
java -jar target/tooltape.jar validate-batch --json fixtures/weather-retry.tape fixtures/weather-retry-v2.tape
java -jar target/tooltape.jar validate-batch --json --require-v2 fixtures/weather-retry-v2.tape
```

当 CI 需要检查明确列出的每份数据，并保存一份报告，而不是遇到首个普通校验错误就停止时，可以使用批量命令。它检查录制结构与格式准入，不执行应用回放，也不比较预期/实际工具事件。后两项仍应使用应用/JUnit 回放测试及单对 `diff`。

## 输入与上限

- 明确提供 **1–64 个文件路径**，结果顺序与参数一致。含空格的路径需加引号；同一命令语法适用于 POSIX shell 和 PowerShell。
- `validate-batch` 后必须紧跟 `--json`，再放可选 `--require-v2`，最后放全部文件。该选项拒绝旧 v1；默认模式仍接受格式有效的旧录制，并附警告。
- 串行处理文件。重复路径保留为独立索引请求，不去重，也不自动发现文件。
- ToolTape 不实现清单文件、目录遍历、标准输入、环境变量替换或通配符展开。shell 和原生 Java 启动器可能提前改变参数；需要可移植、可审阅的清单时，请避免通配符，并使用加引号的 ASCII 路径；未列出的文件不会被检查。
- 以 `--` 开头的参数按无效选项处理。同名文件可使用明确路径，如 `./--require-v2`。
- 每次读取保留原有 16 MiB 文件上限，以及 JSON 和解码后内容限制。最多 64 次尝试，因此录制内容读取量最多为 64 ×（16 MiB + 1 字节）。同一时刻只保留一份解析后的录制，结果只包含安全摘要。
- 批量模式在打开前检查路径是否指向普通文件。允许指向普通文件的符号链接；明确的目录、设备与 FIFO 返回 `IO_ERROR`。预检查不能消除文件系统竞争条件，也不能保证文件访问耗时。CI 仍应设置任务超时，并使用受控的数据目录。

只要首个命令为 `validate-batch`，即使缺少 `--json`，也使用安全的批量 JSON 错误格式。整体语法无效，包括零输入或超过 64 个输入时，会在读取任何文件前失败。语法有效后，缺失、格式错误、未关闭或被拒绝的旧录制各自产生结果，不阻止后续文件检查。意外的进程/JVM 故障不会被伪装成完整报告。

## Windows 文件名传递限制

使用 `Cp1252` 的托管 Windows JDK 17/21 启动器会在 ToolTape 收到参数前，将文件名中的 U+96EA 改为 `?`（[诊断运行](https://github.com/keweizhuazuki/tooltape/actions/runs/38042317004)）。加引号或设置 `-Dfile.encoding=UTF-8` 未能保留该字符，这与 [OpenJDK Windows 启动器的代码页转换](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/native/launcher/main.c#L105-L120)一致。

不含空格的变体还被展开为已有匹配文件名，并移除了相对路径前缀（[第二次诊断](https://github.com/keweizhuazuki/tooltape/actions/runs/38042619406)）；这不是 Unicode 参数原样传递。OpenJDK 会标记未加引号的通配字符，进行[参数展开](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/native/libjli/cmdtoargs.c#L135-L144)。

可移植的 Windows CLI 任务应使用加引号、完整路径均为 ASCII 的文件名。其他机器当前代码页能表示的字符可能可用，但不承诺任意 Unicode 命令行参数。文件内部的 UTF-8 内容不受影响。测试分别检查原生参数传递，以及在独立 JVM 内构造 Unicode 路径的情况；后者不证明原生命令行支持。无需且不会修改系统编码设置。

## 批量报告 v1

stdout 输出一个规范 JSON 文档和一个换行，ToolTape 本身不输出 stderr；JVM 启动诊断不受此保证约束。

批量外层独立使用 `batchReportVersion: 1`，而不是单文件命令的 `reportVersion`：

- `tooltapeVersion`、`command: "validate-batch"`、`outcome`、`exitCode`
- `counts: { "requested": N, "valid": V, "error": E }`，V + E = N
- `results` 按参数顺序排列，每项为 `{ "inputIndex": 0, "report": ... }`

`inputIndex` 从 0 开始，对应经过 shell/启动器处理后 ToolTape 实际收到的参数。`report` 保留原有[单文件校验报告 v1](CLI_REPORTS.zh-CN.md)，包括 `command: "validate"`、摘要、警告和安全错误码。整体语法错误则添加固定 `INVALID_ARGUMENTS` 错误，省略 `counts`/`results`，不会虚构空的成功批次。

| 退出码 | 外层 outcome | 含义 |
| --- | --- | --- |
| 0 | `valid` | 全部列出的输入在所选模式下通过 |
| 2 | `error` | 至少一个输入失败，或命令语法无效 |

校验批次不会返回 1，后续成功不能掩盖先前失败。包含工具异常的已关闭会话仍可作为有效回归数据；校验不证明业务成功。要求 v2 也不证明来源真实性或策略指纹符合应用预期。

报告不输出文件名、路径、路径哈希、自定义标签、工具名、捕获值、JSON 字段名或异常文本。用索引关联你自己的参数清单。策略哈希和事件数仍会暴露部分会话/配置结构，公开前应审阅。报告不添加时间戳或耗时，相同顺序的相同输入会生成相同报告。

实际生成的示例：[混合格式清单](../examples/reports/batch-valid.json)、[同一清单要求 v2](../examples/reports/batch-rejected.json)，均使用仓库自带的合成天气数据。迁移旧格式时，应[审阅字段并安全重新录制](CLI_REPORTS.zh-CN.md#在-ci-中要求-v2)，不要伪造缺失的关闭或策略证据。

## 保存报告并保留失败状态

POSIX shell，包括启用 `set -e` 的任务：

```sh
status=0
java -jar target/tooltape.jar validate-batch --json --require-v2 fixtures/weather-retry-v2.tape > fixture-report.json || status=$?
cat fixture-report.json
exit "$status"
```

PowerShell：

```powershell
java -jar target/tooltape.jar validate-batch --json --require-v2 fixtures/weather-retry-v2.tape > fixture-report.json
$status = $LASTEXITCODE
Get-Content fixture-report.json
exit $status
```

示例使用仓库内唯一的 v2 数据。随着数据集增加，请明确添加每个经审阅的文件路径。多对比较清单、自动更新基线和生成候选录制均不属于此命令范围。

## 已验证的平台

[运行 38042920703](https://github.com/keweizhuazuki/tooltape/actions/runs/38042920703)通过全部六组 Linux/macOS/Windows × Java 17/21 构建。Linux/macOS 执行 100 个批量子进程场景，包含 FIFO、字符设备及符号链接；Windows 执行 94 个场景，包含符号链接及已测得的带引号 Unicode 限制。Unix 设备/FIFO 和 Windows 不允许的通配符文件名在 Windows 上明确标为不可用，不代表已测试 Windows 命名管道。

下载后的分发 JAR 通过 SHA-256 校验及五项独立消费者检查：混合格式、要求 v2 后拒绝旧格式、缺失文件后继续处理、64 个输入、提前拒绝 65 个输入。原有 164 个单命令子进程场景及 24 项 JUnit 测试也全部通过。
