# 贡献指南 · Contributing Guide

感谢你有兴趣为「阅读 Max（legado_Max）」做贡献。本文件说明**人类贡献者**需要知道的环境、规范与流程。

> ⚠️ 请先读 [README 的「重要声明」](README.md#-重要声明)：本项目是个人自用阅读器的 fork，在 AI 编程工具辅助下开发，开发者非计算机专业背景，**不保证**使用体验、数据安全或设备兼容性。欢迎交流与技术讨论，但请把期望值放在这个前提下。
>
> 本项目以 [GPL-3.0](LICENSE) 授权。提交任何代码即表示你同意你的贡献在该许可证下发布。

---

## 1. 从哪里开始

1. 先到仓库的 **Issues** 页面浏览，认领一个你感兴趣且没人处理的问题；新功能类改动建议**先开 Issue 讨论**，避免方向不一致后白做。
2. 提交时使用 Issue 模板：**Bug 报告** 与 **功能请求**（新建 Issue 时页面会列出模板）。简繁转换问题请先到 [quick-chinese-transfer](https://github.com/liuyueyi/quick-chinese-transfer/issues/new) 反馈。
3. 了解项目结构后再动手：工程规则、架构、构建命令、代码约定全部集中在 [CLAUDE.md](CLAUDE.md)；强制编码规范库在 [`docs/project-rules/`](docs/project-rules/README.md)（这是红线，不是教程）。

---

## 2. 开发环境

| 依赖        | 版本要求                                                                          |
| ----------- | --------------------------------------------------------------------------------- |
| JDK         | **17**                                                                            |
| Android SDK | 平台随 `app/build.gradle` 的 `compileSdk`；首次构建前需接受 license               |
| Gradle      | 使用项目自带 wrapper（Windows 用 `gradlew.bat`）                                  |
| Node / pnpm | 仅改 `modules/web/`（内置 HTTP/WS 前端，Vue 3 + Vite）时需要：Node ≥ 20、pnpm ≥ 9 |

### 首次构建

Cronet 原生库不随仓库分发，**首次构建前必须先下载**：

```bash
./gradlew app:downloadCronet   # Windows: gradlew.bat app:downloadCronet
./gradlew assembleDebug        # Debug 构建（默认 flavor：appMax）
./gradlew installAppMaxDebug   # 安装到已连接设备
```

常用验证命令：

```bash
./gradlew test     # 单元测试（JVM）
./gradlew lint     # Android Lint，本地最后一道门禁
```

> **本地验证顺序：先 `test` 再 `lint`**。CI 中 lint 通过是「完成」的一部分。构建命令全表、三个 flavor、Web 前端命令见 [`docs/project-rules/build-commands.md`](docs/project-rules/build-commands.md)。

### Product Flavors

- `appLegacy` — `io.legado.app`，与原版同包名，可覆盖更新
- `appMax` — `io.legado.app.yuedu`，**主开发目标**，共存不覆盖原版
- `appS` — `io.legado.app.yuedu.a`，另一个共存包名

日常贡献默认针对 `appMax`。

---

## 3. 编码规范

- **Kotlin 风格**：遵循 Google Android Style Guide。
- **命名**：`XxxActivity` / `XxxViewModel` / `XxxFragment`。
- **注释**：优先表达「为什么这么做 / 特殊约束 / 业务背景」；核心或复杂类（单例、引擎、解析器、管理器）必须有完整 KDoc。详见 [CLAUDE.md「Comments」](CLAUDE.md)。
- **写代码前先按主题读对应规范**（改动后如影响规范，需同步更新文档——`pre-commit` 的 help-doc-sync 钩子会拦截「改了受管代码却没同步改文档」的提交）：
  - 任何异步代码 → [`coroutine-rules.md`](docs/project-rules/coroutine-rules.md)
  - 数据层 / Repository → [`repository-rules.md`](docs/project-rules/repository-rules.md)
  - 高版本 API / 新依赖 / 发版 → [`api-compat-rules.md`](docs/project-rules/api-compat-rules.md)
  - 跨组件事件 → [`live-event-bus-rules.md`](docs/project-rules/live-event-bus-rules.md)
  - Compose UI → [`docs/project-rules/compose/`](docs/project-rules/compose/README.md)（结构/状态/主题/性能/导航/无障碍/测试/迁移共 8 篇）

### 测试

- 单元测试放 `app/src/test/`，仪器测试放 `app/src/androidTest/`。
- Mock 用 Mockk，协程测试用 kotlinx-coroutines-test。
- **覆盖率数字不作验收指标**；真实约束是：**新增 ViewModel 或修改状态机的 PR 必须有对应测试**。
- 环境跑不了仪器测试时不强求就地跑，但需在 PR 里说明。详见 [`docs/project-rules/testing.md`](docs/project-rules/testing.md)。

### 界面文案与多语言

用户可见文案需同步四种语言：简体中文、繁体中文（台湾）、繁体中文（香港）、英文。**新增/修改 `strings.xml` 后要补齐其余语言的对应条目**，不要只留单一语言。

---

## 4. Git 与提交规范

**首次使用需在项目根目录执行 `npm install`**，否则 husky git hook 不会生效。

```bash
npm run commit   # 交互式生成合规 commit message
```

提交信息使用 **Conventional Commits 中文适配**，格式 `<type>(<scope>): <subject>`：

- `type` 用英文：`feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `chore` / `ci` / `revert`
- `scope`、`subject` 用中文，subject 为动宾短语且 ≤ 100 字符，结尾不加句号
- 关联 Issue 写在 footer：`Closes #123`、`Refs #124`

例：`feat(书架): 新增批量选中分组`。详见 [`docs/git-hook/commit-spec.md`](docs/git-hook/commit-spec.md)。

> **不要对共享历史做不可逆操作**：`git push --force`、`git reset --hard`、`git rebase`、`git clean -fd` 等仅在明确知晓后果时使用；**绝不直接推送 `main` / `master`**（`.husky/pre-push` 会拦截）。

---

## 5. 提交 Pull Request

1. 从 `main` 切出功能分支，一个 PR 只做一件事，保持 diff 聚焦。
2. 提交前：`./gradlew test` → `./gradlew lint` 本地跑通。
3. 打开 PR，按模板勾选 checklist；描述里说明**改了什么、为什么、如何验证**，并关联对应 Issue。
4. 改动过 Web 服务 / API / 前端行为的，需按 [`e2e-testing-rules.md`](docs/project-rules/e2e-testing-rules.md) 做三层验证并在 PR 里注明结果。
5. Review 会按两个维度对照：是否符合项目编码规范（Standards）、是否符合需求（Spec）。CI 通过 + 人工 Review 通过后合并。

请理解：这是个人维护的志愿项目，**Review 与合并没有保证时限**。急用可自行 fork 维护。

---

## 6. 致谢

阅读 Max 继承自 [Legado / legado_Plus](https://github.com/gedoor/legado) 及 [lyc 版](https://gitee.com/lyc486/legado)，在其基础上新增功能。
