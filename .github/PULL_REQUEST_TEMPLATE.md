<!--
感谢提交 PR！请先阅读 CONTRIBUTING.md。
安全问题请勿用公开 PR 报告，改走 SECURITY.md 的私密渠道。
-->

## 这个 PR 做了什么

<!-- 一句话概括 + 必要的背景。一个 PR 只做一件事，保持 diff 聚焦。 -->

## 关联 Issue

<!-- Closes #123 / Refs #124；无关联 Issue 可留空，但新功能建议先在 Issue 里讨论过 -->

## 验证情况

- [ ] 本地 `./gradlew test` 通过
- [ ] 本地 `./gradlew lint` 通过（验证顺序：单测 → lint）
- [ ] 已在真机/模拟器手动验证 UI 或功能表现
- [ ] 新增 ViewModel / 修改状态机的改动**已附对应测试**
- [ ] 改动了 Web 服务 / API / 前端行为 → 已按 `docs/project-rules/e2e-testing-rules.md` 做三层验证（不涉及可勾「不适用」）

## 规范与文档同步

- [ ] 已按改动主题对照 `docs/project-rules/`（协程 / Repository / API 兼容 / 事件总线 / Compose）
- [ ] 改了受管代码 → 已同步更新对应 md 文档（否则 pre-commit 的 help-doc-sync 会拦截）
- [ ] 改了用户可见文案 → 已同步四语言（简中 / 繁台 / 繁港 / 英文）
- [ ] 改了 app 用户可见改动（bug/界面/功能）→ 已按 `docs/project-rules/update-log-rules.md` 维护 `updateLog.md`

## 提交信息

- [ ] commit message 符合 Conventional Commits 中文适配（`<type>(<scope>): <subject>`），未直接推送 `main`/`master`

## 补充说明

<!-- 取舍、已知限制、后续 follow-up。发现与本 change 无关的 bug/优化点，请作为 follow-up 报告，不在本次夹带。 -->
