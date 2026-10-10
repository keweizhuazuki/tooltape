# 在 CI 中校验一组录制文件

[English](BATCH_REPORTS.md) | 简体中文

```sh
java -jar target/tooltape.jar validate-batch --json fixtures/weather-retry.tape fixtures/weather-retry-v2.tape
java -jar target/tooltape.jar validate-batch --json --require-v2 fixtures/weather-retry-v2.tape
java -jar target/tooltape.jar validate-batch --json --manifest examples/manifest/inventory.json
```

当 CI 需要检查明确列出的每份数据，并保存一份报告，而不是遇到首个普通校验错误就停止时，可以使用批量命令。它检查录制结构与格式准入，不执行应用回放，也不比较预期/实际工具事件。后两项仍应使用应用/JUnit 回放测试及单对 `diff`。

## 输入与上限

- 明确提供 **1–64 个文件路径**，结果顺序与参数一致。含空格的路径需加引号；同一命令语法适用于 POSIX shell 和 PowerShell。
- `validate-batch` 后必须紧跟 `--json`，再放可选 `--require-v2`，最后放全部文件。该选项拒绝旧 v1；默认模式仍接受格式有效的旧录制，并附警告。
- 串行处理文件。重复路径保留为独立索引请求，不去重，也不自动发现文件。
- ToolTape 支持下文的显式 UTF-8 清单，不实现递归发现、标准输入、环境变量替换或通配符展开。shell 和原生 Java 启动器可能提前改变参数；需要可移植、可审阅的清单时，请避免通配符，并使用加引号的 ASCII 路径；未列出的文件不会被检查。
- 以 `--` 开头的参数按无效选项处理。同名文件可使用明确路径，如 `./--require-v2`。
- 每次读取保留原有 16 MiB 文件上限，以及 JSON 和解码后内容限制。最多 64 次尝试，因此录制内容读取量最多为 64 ×（16 MiB + 1 字节）。同一时刻只保留一份解析后的录制，结果只包含安全摘要。
- 批量模式在打开前检查路径是否指向普通文件。允许指向普通文件的符号链接；明确的目录、设备与 FIFO 返回 `IO_ERROR`。预检查不能消除文件系统竞争条件，也不能保证文件访问耗时。CI 仍应设置任务超时，并使用受控的数据目录。

只要首个命令为 `validate-batch`，即使缺少 `--json`，也使用安全的批量 JSON 错误格式。无效 CLI 语法会在读取任何文件前失败。清单会在打开任何录制文件前被完整读取和校验；零条或超过 64 条记录均无效。语法有效后，缺失、格式错误、未关闭或被拒绝的旧录制各自产生结果，不阻止后续文件检查。意外的进程/JVM 故障不会被伪装成完整报告。

## UTF-8 清单：无需通过 argv 传递 Unicode 文件名

以下形式与直接提供录制文件参数互斥：

```sh
java -jar target/tooltape.jar validate-batch --json [--require-v2] --manifest inventory.json
```

方括号表示可选项，不应原样输入。不要混用 `--manifest` 与录制文件参数，也不要重复或调换选项顺序。仓库自带[可执行清单](../examples/manifest/inventory.json)，从仓库根目录即可运行，包含带空格和补充平面字符的 Unicode 文件名。其中 Unicode 命名的录制是已审查 weather-v2 数据的逐字节副本，仅演示文件名传递，不代表新的独立应用场景。

```json
{
  "manifestVersion": 1,
  "files": ["../../fixtures/weather-retry.tape", "天气 重试🌧.tape"]
}
```

只有清单路径经过原生命令行参数；其内容在 Java 内部明确按 UTF-8 解码，因此列出的 Unicode 文件名不经过 Windows 启动器的活动代码页。可移植 Windows 调用应给清单本身使用 ASCII 路径；清单参数含任意 Unicode 时仍受下文限制。UTF-8 解码不依赖 `file.encoding`。

### 格式与资源限制

- 仅接受 `manifestVersion` 和 `files` 两个字段，版本必须为数值 1。不提供标签、逐文件选项、嵌套清单或 include。
- `files` 必须包含 1–64 个非空字符串，每条最多 4,096 个 UTF-16 代码单元。重复条目和顺序原样保留，不去除空白，也不做 Unicode 规范化。
- 整份清单最多 64 KiB，包括 BOM 和空白；为发现超限，最多读取 64 KiB + 1 字节。总大小与单条限制独立生效，64 条分别达到长度上限的路径不一定能放入同一份清单。
- 严格 UTF-8 解码允许开头出现一个可选 BOM，兼容 Windows 工具写出的文件。无效编码、JSON 字符串外重复或非开头的 BOM、损坏 JSON、重复/未知字段、错误类型/版本、转义后的不配对代理项均拒绝。
- 清单与录制文件都须通过普通文件预检查。相比原有录制读取上限，清单最多增加 65,537 字节。限制不保证精确堆内存用量或文件访问完成时间。

### 路径语义

条目使用正斜杠分隔的**相对**文件系统路径，相对于传入清单路径的父目录解析，而不是进程工作目录。允许显式 `..`，例如 `.tooltape/inventory.json` 可列出 `../fixtures/example.tape`。实现不调用 `normalize()` 或 `toRealPath()`，符号链接与 `..` 保留文件系统语义。清单本身为符号链接时，基准是传入路径所在位置，而不是目标文件所在目录。

绝对路径、盘符/UNC/URI 形式、反斜杠、控制字符和不配对代理项均无效。`--require-v2` 等形似选项的名称在清单中是普通文件名。通配符仅作为字面字符，不会展开；名称是否合法仍由文件系统决定。原生路径提供者不接受的名称产生带索引的 `INVALID_ARGUMENTS`，不会阻止后续条目。

清单参数本身也会在 ToolTape 检查文件系统前拒绝显式 UNC/设备形式，包括被 [OpenJDK Windows 路径解析器](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsPathParser.java)视为 UNC 的混合前导正反斜杠。这些检查发生在原生启动器处理参数之后，ToolTape 无法控制更早的处理阶段。不提供 URL 加载、shell 求值、递归发现、环境变量展开或 include。这里**不提供目录隔离**：`..`、符号链接和挂载点可能指向其他位置，包括网络存储。请使用经过审查的清单、受控目录和外部 CI 超时。普通文件检查不能消除竞争条件或文件系统延迟。

### 安全错误报告

任何录制文件被读取前，会先完整检查清单结构及词法路径规则。编码、结构、条目数、大小或路径规则无效时，外层返回固定文案的 `INVALID_MANIFEST`，不含 `counts`/`results`。清单无法读取或并非普通文件时返回外层 `IO_ERROR`；CLI 语法或原生清单路径无效时返回外层 `INVALID_ARGUMENTS`。不回显解析偏移、字段名、路径、条目或异常文本。

清单被接受后，每份录制的错误仍使用原有嵌套校验报告，继续处理后续条目。`inputIndex` 对应清单数组中从 0 开始的顺序。整体退出码仍为 0/2，`batchReportVersion` 仍为 1。清单改变的是输入传递方式，不改变校验准入或报告隐私边界。

## Windows 文件名传递限制

使用 `Cp1252` 的托管 Windows JDK 17/21 启动器会在 ToolTape 收到参数前，将文件名中的 U+96EA 改为 `?`（[诊断运行](https://github.com/keweizhuazuki/tooltape/actions/runs/38042317004)）。加引号或设置 `-Dfile.encoding=UTF-8` 未能保留该字符，这与 [OpenJDK Windows 启动器的代码页转换](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/native/launcher/main.c#L105-L120)一致。

不含空格的变体还被展开为已有匹配文件名，并移除了相对路径前缀（[第二次诊断](https://github.com/keweizhuazuki/tooltape/actions/runs/38042619406)）；这不是 Unicode 参数原样传递。OpenJDK 会标记未加引号的通配字符，进行[参数展开](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/native/libjli/cmdtoargs.c#L135-L144)。

直接提供文件参数时，可移植 Windows CLI 任务应使用加引号、完整路径均为 ASCII 的文件名。录制文件名包含 Unicode 时，可使用上面的 UTF-8 清单，并给清单参数使用 ASCII 路径。其他机器当前代码页能表示的字符可能可用，但不承诺任意 Unicode 命令行参数。文件内部的 UTF-8 内容不受影响。测试分别检查原生参数传递，以及在独立 JVM 内构造 Unicode 路径的情况；后者不证明原生命令行支持。无需且不会修改系统编码设置。

## 批量报告 v1

stdout 输出一个规范 JSON 文档和一个换行，ToolTape 本身不输出 stderr；JVM 启动诊断不受此保证约束。

批量外层独立使用 `batchReportVersion: 1`，而不是单文件命令的 `reportVersion`：

- `tooltapeVersion`、`command: "validate-batch"`、`outcome`、`exitCode`
- `counts: { "requested": N, "valid": V, "error": E }`，V + E = N
- `results` 按参数顺序排列，每项为 `{ "inputIndex": 0, "report": ... }`

`inputIndex` 从 0 开始，对应经过 shell/启动器处理后 ToolTape 实际收到的位置参数，或清单数组顺序。`report` 保留原有[单文件校验报告 v1](CLI_REPORTS.zh-CN.md)，包括 `command: "validate"`、摘要、警告和安全错误码。整体语法错误则添加固定 `INVALID_ARGUMENTS` 错误，省略 `counts`/`results`，不会虚构空的成功批次。

| 退出码 | 外层 outcome | 含义 |
| --- | --- | --- |
| 0 | `valid` | 全部列出的输入在所选模式下通过 |
| 2 | `error` | 至少一个输入失败，或命令/清单无效或无法读取 |

校验批次不会返回 1，后续成功不能掩盖先前失败。包含工具异常的已关闭会话仍可作为有效回归数据；校验不证明业务成功。要求 v2 也不证明来源真实性或策略指纹符合应用预期。

报告不输出文件名、路径、路径哈希、自定义标签、工具名、捕获值、JSON 字段名或异常文本。用索引关联你自己的参数清单。策略哈希和事件数仍会暴露部分会话/配置结构，公开前应审阅。报告不添加时间戳或耗时，相同顺序的相同输入会生成相同报告。

实际生成的示例：[混合格式清单](../examples/reports/batch-valid.json)、[同一清单要求 v2](../examples/reports/batch-rejected.json)，均使用仓库自带的合成天气数据。仓库自带 UTF-8 清单在默认及 `--require-v2` 模式下分别产生与这两份报告逐字节一致的输出；改变的是文件名传递方式，而非报告内容。迁移旧格式时，应[审阅字段并安全重新录制](CLI_REPORTS.zh-CN.md#在-ci-中要求-v2)，不要伪造缺失的关闭或策略证据。

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
