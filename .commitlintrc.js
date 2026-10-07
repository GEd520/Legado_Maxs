module.exports = {
  // 历史遗留（commit-msg 钩子生效前）的每日「更新日志」「调整日志」裸提交没有规范格式，
  // 仅精确放行这两条消息本身，其余提交仍按常规规则校验
  ignores: [
    (message) =>
      ["更新日志", "调整日志"].includes(message.split("\n")[0].trim()),
  ],
  extends: ["@commitlint/config-conventional"],
  rules: {
    "type-enum": [
      2,
      "always",
      [
        "feat",
        "fix",
        "docs",
        "style",
        "refactor",
        "perf",
        "test",
        "chore",
        "ci",
        "revert",
      ],
    ],
    "type-case": [2, "always", "lower-case"],
    "type-empty": [2, "never"],
    "subject-empty": [2, "never"],
    "subject-max-length": [2, "always", 100],
    // 允许中文，关闭大小写限制
    "subject-case": [0],
    // 中文占宽较大，放宽 header 长度限制
    "header-max-length": [2, "always", 120],
    "body-max-line-length": [1, "always", 200],
    "footer-max-line-length": [1, "always", 200],
  },
  prompt: {
    messages: {
      type: "选择提交类型:",
      scope: "输入影响范围（可选）:",
      subject: "填写简短描述:",
      body: '填写详细描述（可选，使用 "|" 换行）:',
      breaking: "列出不兼容变更（可选）:",
      footer: "关联的 Issue（可选，例如 #123）:",
      confirmCommit: "确认提交以上信息？",
    },
    // 中文提交类型描述
    types: {
      feat: { description: "新功能: 添加新功能" },
      fix: { description: "缺陷修复: 修复 bug" },
      docs: { description: "文档变更: 更新文档" },
      style: { description: "代码格式: 格式/空格等不影响逻辑的修改" },
      refactor: { description: "重构: 不是新功能也不是修复 bug 的代码重构" },
      perf: { description: "性能优化" },
      test: { description: "测试: 添加/更新测试" },
      chore: { description: "构建/工具/依赖变更" },
      ci: { description: "持续集成配置" },
      revert: { description: "回滚: 回滚某次提交" },
    },
  },
};
