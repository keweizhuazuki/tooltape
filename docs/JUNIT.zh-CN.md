# JUnit Jupiter 回放夹具

[English](JUNIT.md) | [简体中文](JUNIT.zh-CN.md)

可选的 `integrations/junit-jupiter` 模块把经过审查的 v2 录制变成可重复执行的 Jupiter 测试。核心库仍然零运行时依赖；适配器需要 Jupiter API 和测试运行器。两个组件均未发布到 Maven Central。

## 本地构建与安装

使用完整 JDK 17 或 21、Maven，在源码仓库执行：

```sh
mvn -B clean install
mvn -B -f integrations/junit-jupiter/pom.xml clean install
```

PowerShell 可使用相同命令语法；此适配器的托管兼容性检查目前在 Ubuntu 执行，尚未验证原生 Windows/macOS。它们仅将当前源码的组件安装到本机 Maven 仓库，不会发布版本。团队应固定源码提交。开发构建目前共用 `0.2.0`，升级时请同时重新安装两个组件，不要混用不同提交的构建。

在应用的测试依赖中加入本地安装的 `dev.tooltape:tooltape-junit-jupiter:0.2.0`，以及匹配版本的 Jupiter API/engine，均使用 test scope。适配器的 Jupiter API 为 `provided`，测试运行器由应用控制。请沿用项目的 JUnit BOM，不要混搭不一致的 engine/platform 版本。首次准备普通测试依赖可能需要联网；回放本身不需要网络。

## 一个工作流，一份夹具

把经过审查并已正常关闭的 v2 录制放在 `src/test/resources/tapes/weather-retry.tape`。捕获策略必须与录制时一致：

```java
import dev.tooltape.CapturePolicy;
import dev.tooltape.Replay;
import dev.tooltape.junit.ReplayFixture;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherRegressionTest {
  private static final CapturePolicy POLICY =
      CapturePolicy.allow(Set.of("city"), Set.of("temperature"));
  private static final ReplayFixture FIXTURE = ReplayFixture.fromResource(
      WeatherRegressionTest.class, "/tapes/weather-retry.tape", POLICY);

  @Test
  void retriesRecordedTimeout() throws Throwable {
    FIXTURE.assertReplay(tool -> {
      Map<String, Object> result;
      try {
        result = tool.call("weather.lookup", Map.of("city", "Hangzhou"));
      } catch (Replay.RecordedToolException recordedFailure) {
        result = tool.call("weather.lookup", Map.of("city", "Hangzhou"));
      }
      assertEquals("22", result.get("temperature").toString());
    });
  }
}
```

实际使用时，通常把 `tool` 传给自己的编排方法，而不是在测试中逐个手写调用。副作用边界应显式注入。辅助类没有真实工具委托，不会替你调用外部工具，但回调中的其他代码仍是普通 Java，并非沙箱。

## 命名动态测试

```java
@TestFactory
Stream<DynamicTest> recordedScenarios() {
  return Stream.of(
      FIXTURE.test("timeout then successful retry", tool -> {
        assertEquals("22", forecast(tool, "Hangzhou").get("temperature").toString());
      }));
}
```

导入 `org.junit.jupiter.api.TestFactory`、`org.junit.jupiter.api.DynamicTest` 和 `java.util.stream.Stream`；`forecast` 代表自己的编排方法。每个动态子测试执行时才加载、校验录制并创建独立回放。因此缺失或损坏的夹具会在对应名称的子测试中失败。创建辅助对象或 `DynamicTest` 时不会读取文件。

JUnit 不会为每个动态子测试分别执行 `@BeforeEach`/`@AfterEach`。请把可变工作流状态放在各自的回调内。辅助对象不可变，可以重复使用，也可供独立的并行测试共享；单次回放内部仍须串行调用，不要把回放引用交给后台任务。

## 准入、资源所有权和失败语义

- `fromPath(Path, CapturePolicy)` 读取文件系统夹具。`fromResource(Class<?>, String, CapturePolicy)` 遵循 `Class.getResourceAsStream`：以 `/` 开头表示类路径根目录，否则相对于锚点类所在包。支持 JAR 内资源，无需临时解压。
- 每次执行都先检查 v2 格式、完整结束标记和精确匹配的策略指纹，再执行回调。核心 API 和 CLI 仍可读取 v1；使用此辅助类需重新录制并审查 v2 夹具。它不会升级指纹或猜测策略。
- 每次执行必须消费全部录制调用。参数变化、缺失/额外调用、吞掉差异异常都会失败。合法的空录制只允许零次调用。
- 预期的录制错误必须在回调**内部**捕获或断言，再消费后续事件。不要对整个辅助方法使用 `assertThrows`，否则只接受主异常时可能忽略被抑制的未完成回放错误。
- 回调抛出的原始 Throwable 对象保持为主异常；关闭回放时出现的错误作为 suppressed 异常，遵循 Java try-with-resources 语义。断言失败和中止测试也如此。即使附带未完成回放的 suppressed 异常，Jupiter 中止仍保持中止状态；如果每条轨迹都必须明确通过或失败，请避免在回放回调内使用 assumptions。排查时请检查 suppressed 异常。
- 辅助类关闭自己打开的流。新增核心 `Tape.read(InputStream)` 在成功和失败时都不关闭调用方的流，并复用 `Tape.read(Path)` 的有界解析逻辑。路径重载仍关闭自己打开的流。
- 工厂不缓存文件内容。需要可重复输入时，请在测试期间保持夹具不可变；之后的再次执行会有意读取被替换的新文件。

## 诊断与隐私

JUnit 输出可能包含工具名、字段名、允许捕获的参数/结果值、路径，以及工作流的任意错误信息。它**不属于** CLI 无载荷 JSON 报告的隐私契约。分享前请审查夹具、测试名称、断言文本及 CI 日志/产物权限。默认策略不捕获值；允许一个字段意味着明确保留它，并不证明其内容安全。

## 验证与边界

适配器以 Jupiter 5.14.4 编译。独立消费者项目在 Java 17/21 下，分别用 Jupiter 5.14.4 和 6.0.3 消费**同一个已打包适配器 JAR**。具体构建与消费流程见 [Jupiter 工作流](../.github/workflows/jupiter.yml)。每个组合的兼容性证据是实际成功的运行，而非仅凭源码兼容推断。

测试覆盖真实 JAR 资源、命名动态子测试结果、重复使用时的独立状态、并行隔离、回调前拒绝格式/策略问题、完整消费、录制错误重试及 Throwable 保留。核心解析测试覆盖流大小限制、一致性和所有权。暂不提供录制注解、目录发现、可变扩展注册表或自动基线更新。

JUnit 生命周期参考：[官方动态测试指南](https://docs.junit.org/6.0.3/writing-tests/dynamic-tests.html)。
