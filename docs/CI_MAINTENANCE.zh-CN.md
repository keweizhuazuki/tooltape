# CI Actions 维护

[English](CI_MAINTENANCE.md) | [简体中文](CI_MAINTENANCE.zh-CN.md)

2026-10-10 核对：托管运行器提示 Actions 的 Node 20 运行时及 setup-java v4 已弃用。应用测试仍为 Java 17/21、Node 22；Action 内部运行时与它安装的开发语言版本是两回事。

| 官方版本 | 固定提交 |
| --- | --- |
| [checkout v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| [setup-java v6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1) | `de7274f081f381c8f8158605e0321c36c376e2e6` |
| [setup-node v7.1.0](https://github.com/actions/setup-node/releases/tag/v7.1.0) | `949feb2413d6458794dcd2491c4babbbce0c15c1` |
| [upload-artifact v7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |

通过官方 `actions/*` 仓库 Git refs API 将版本标签解析为上述提交，并确认各版本 `action.yml` 使用 `node24`。锁定完整 SHA 可避免可移动标签静默改变执行代码，但仍需审阅后续更新。

## 兼容性与范围

- 使用 GitHub 托管运行器。[setup-node 官方说明](https://github.com/actions/setup-node#breaking-changes-in-v5)要求支持 Node 24 的运行器版本至少为 2.327.1。
- [Checkout v7](https://github.com/actions/checkout#whats-new)调整特权触发器下的 fork 检出。当前仅使用普通 push/pull_request，浏览器另有 workflow_dispatch；没有添加危险的绕过开关。
- [Setup Java v6](https://github.com/actions/setup-java#v6)调整部分发布参数及 JDK 校验。这里只用 Temurin 17/21 和 Maven 缓存，没有签名或发布凭据。
- [Setup Node v7](https://github.com/actions/setup-node#whats-new-in-v7)移除占位 registry token。这里未配置 registry 认证；无依赖界面检查关闭自动缓存，浏览器任务保留显式 npm lockfile 缓存。
- 构建产物名称、保留时间和上传路径不变，未启用隐藏文件上传，也不涉及部署。
- 权限保持 `contents: read`，全部 checkout 设置 `persist-credentials: false`；没有新增 secrets、令牌或写权限。

## 后续更新步骤

1. 查阅官方发布记录，通过官方标签解析完整提交，不能复制无关仓库或第三方片段的 SHA。
2. 检查 action.yml、运行器要求、输入及缓存/产物行为，保留最小权限与明确上传路径。
3. 同时更新完整 SHA 和可读版本注释。
4. 合并前执行全部平台/JDK、Maven、界面和浏览器检查，合并后验证 main，并下载 JAR 核对校验和。

这些是维护步骤，不代表已自动更新依赖，也不承诺自托管运行器或 GitHub Enterprise Server 兼容。
