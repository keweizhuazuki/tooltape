# 真实浏览器检查与截图

[English](README.md) | 简体中文

这些仅用于开发的检查会在真实的 Chromium、Firefox 和 WebKit 引擎中，通过 `file://` 直接打开 `viewer/index.html`。它们不会启动网页服务器、隧道、部署或外部服务。查看器本身仍然没有运行时依赖。浏览器测试上下文处于离线状态，每项测试还会检查是否存在意外的非文件请求或未捕获的 JavaScript 错误。

## 复现检查

在允许启动浏览器进程的机器上，使用 Node.js 22 或更高版本：

```sh
cd viewer
npm ci --ignore-scripts --no-audit --no-fund
npx --no-install playwright install --with-deps chromium firefox webkit
npm test
npm run test:browser
```

`npm run test:browser:list` 只列出测试，不启动浏览器。在 Linux 上安装浏览器可能需要系统软件包安装权限。如果浏览器启动被拒绝，不要通过关闭安全设置或更换执行路径来绕过限制。

已提交的锁文件固定了 Playwright 测试运行器及浏览器安装版本。推送代码、创建拉取请求或手动触发时，`.github/workflows/browser.yml` 会在 `ubuntu-24.04` 上运行这些命令。该工作流对仓库只有只读权限，不使用任何密钥。构建产物名为 `viewer-browser-<commit SHA>`，保留 14 天。

## 检查范围

- 真实浏览器中的文件选择，以及重复选择同一测试文件
- 内置示例、错误筛选、内容搜索、键盘选择，以及按 Escape 清除筛选
- 忽略耗时差异的比较、定位首个差异、清除比较，以及替换主文件
- 规范化 JSON 的相等判断，以及有符号 64 位整数的精确处理
- 格式错误、无效 UTF-8、错误 base64url 编码及超大文件的页面内提示，同时保留此前的有效状态
- 将类似 HTML 的内容及文件名显示为不会执行的普通文本
- 空记录文件，以及主文件缺少调用时的查看功能
- 浏览器 `File` / `DataTransfer` 拖放处理，以及拒绝同时拖入多个文件
- 延迟文件读取的竞态处理，以及时间线分页
- v2 会话关闭状态、不完整记录警告、来源信息，以及采集策略变化提示
- 桌面端并排布局和移动端上下布局，且页面不出现横向溢出

移动端项目使用 390 × 844 的 Chromium/WebKit 模拟环境，并未验证真实 Android 或 iPhone 设备、Safari 或操作系统文件选择器。测试运行器控制浏览器文件选择，不检查操作系统原生对话框。这些断言和真实截图也不等同于完整的无障碍审计或基于像素基准的视觉回归系统。

文字对比度检查覆盖部分标签、占位文字和调用状态，并合成计算样式的透明背景/透明度。

新增键盘回归检查覆盖分页、清除筛选、退出比较后的焦点、可见 Tab 停留点，以及 Enter/空格触发文件选择器。

## 截图输出

真实截图测试将以下文件写入 `viewer/browser-tests/artifacts/screenshots/<project-name>/`：

- `viewer-baseline.png`：原样加载已提交的 `fixtures/weather-retry.tape`，选中首次异常，视口宽度为 1440 像素
- `viewer-diff.png`：将该文件与一个人工构造的回归样例比较，后者仅将成功返回的温度从 22 改为 23
- `viewer-mobile.png`：原样加载测试文件，使用宽度为 390 像素的移动端模拟环境
- `viewer-session.png`：原样加载 `fixtures/weather-retry-v2.tape`，显示已关闭的会话及文件自行声明的来源信息

每张 PNG 都有一个配套 JSON 文件，包含查看器源码及测试文件的 SHA-256 哈希值、在 CI 中运行时的提交与工作流运行标识、浏览器和测试运行器版本、视口信息，以及输入内容说明。截图由记录的实际引擎直接生成，未经图像合成或替换。

下载工作流产物后，应先查看实际 PNG，再将它们复制到 `docs/images/` 并在 README 中引用。产物还包含 `artifacts/test-results.json`、`report/index.html`，以及 `results/` 下的失败截图和执行轨迹。截图证明画面中的状态已经渲染；同一提交对应的成功测试结果才证明相关交互断言通过。

## 引擎与产物隔离

托管任务在 Ubuntu 24.04 上运行五个明确项目：desktop-chromium、desktop-firefox、desktop-webkit 使用 1440 × 1000 桌面视口；mobile-chromium、mobile-webkit 使用 390 × 844 移动和触控模拟。Linux WebKit 不等同于品牌 Safari，移动模拟也不是 Android/iPhone 真机或原生文件选择对话框验证。

截图位于 `artifacts/screenshots/<project-name>/`，不同引擎不会覆盖彼此。每个桌面项目生成 baseline/session/diff 三组，两个移动项目各生成 mobile 一组，共 11 组 PNG/JSON。来源记录包含截图、源码与样例哈希，实际引擎及版本、提交/运行编号、视口、移动/触控设置、实际 DPR/user-agent，并与图片一起附在报告中。

更新 README 时应明确选择项目，不能复制最后完成的引擎产物。文件拖放通过 Playwright 自带 drop API 进入真实处理器，保留单文件成功及多文件拒绝断言。完整测试仍保留键盘、隐私、格式、对比度和布局检查。
