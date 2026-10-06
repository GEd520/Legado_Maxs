# Compose UI 规范 — 总纲

> 本目录 8 个规范文件均为原 `UI-ARCHITECTURE.md`（2026-08-19）拆分产物，章节编号沿用原编号，跨文件引用按「文件名 §编号」格式书写。各文件共用的通用约定收在这里，避免 8 处重复维护。

## 通用约定

- **生效范围**：`io.legado.app.ui` 包及以下所有代码。
- **执行方式**：`migration-review.md` §14.1 标 [机器] 的条目，其 CI/lint 强制机制（`tools/lint-rules/` 独立模块）**尚未建立**，现阶段统一按 [人工] 处理——Code Review 人工对照，不达标 PR 打回；机器强制落地前，不要假设违规会被构建拦截。
- **老代码策略**：分阶段迁移，过渡期豁免方式见 `migration-review.md` §13。

## 文件清单

| 文件                                             | 原章节       | 主题                                             |
| ------------------------------------------------ | ------------ | ------------------------------------------------ |
| [structure.md](./structure.md)                   | §1/2/3/11/12 | 目录结构、命名、API 契约、组件拆分               |
| [state-events.md](./state-events.md)             | §4/5/6       | 状态管理、事件、错误处理                         |
| [theme-styles.md](./theme-styles.md)             | §7           | 颜色、dimens、图片、字体、字符串、动画、点击反馈 |
| [performance.md](./performance.md)               | §8           | Recomposition 防范、列表性能                     |
| [navigation-preview.md](./navigation-preview.md) | §9/10        | 导航约定、Preview 规范                           |
| [accessibility.md](./accessibility.md)           | §15          | 无障碍                                           |
| [testing.md](./testing.md)                       | §16          | Compose / ViewModel 测试                         |
| [migration-review.md](./migration-review.md)     | §13/14/17    | 迁移三阶段、Review Checklist、违规示例           |
