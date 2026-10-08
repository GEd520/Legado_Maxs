#!/usr/bin/env node
/**
 * help-doc-sync.mjs
 *
 * pre-commit 门禁：检查"代码改动是否同步到了对应的帮助文档"。
 *
 * 用法（由 .husky/pre-commit 调用，无需手动执行）：
 *   SKIP_DOC_SYNC=1 git commit ...        # 跳过本次检查
 *
 * CI 自检（校验 map.json 本身是否还有效，供 .github/workflows/lint.yaml 调用）：
 *   node scripts/help-doc-sync.mjs --verify
 *
 * 退出码：0 通过 / 1 拦截（同时阻止 commit）
 */

import { readFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import url from 'node:url';

const __dirname = path.dirname(url.fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..');

const MAP_FILE = path.join(repoRoot, 'docs', 'help-doc-sync', 'map.json');
const HELP_MD_DIR = path.join(
  repoRoot,
  'app',
  'src',
  'main',
  'assets',
  'web',
  'help',
  'md'
);

// 统一用 POSIX 风格路径（正向斜杠），避免 Windows 反斜杠与 glob 不匹配
const toPosix = (p) => p.split(path.sep).join('/');

// ---------- glob → RegExp ----------
// 支持 *（任意字符不含 /）和 **（任意多级目录）
function globToRegExp(glob) {
  const escaped = glob
    .replace(/[.+^${}()|[\]\\]/g, '\\$&') // 转义正则元字符
    .replace(/\*\*/g, '\u0000GLOB_STARS') // 先占位 **
    .replace(/\*/g, '[^/]*') // * → 排除 /
    .replace(/\u0000GLOB_STARS/g, '.*'); // ** → .*
  return new RegExp('^' + escaped + '$');
}

// ---------- 读取映射表 ----------
function loadMap() {
  const raw = readFileSync(MAP_FILE, 'utf8');
  const json = JSON.parse(raw);
  return json.rules.map((r) => ({
    source: r.source,
    sourceRe: globToRegExp(r.source),
    docs: r.docs.map((d) => toPosix(path.resolve(HELP_MD_DIR, d))),
  }));
}

// ---------- 获取暂存文件列表 ----------
function getStagedFiles() {
  try {
    // core.quotePath=false：中文等非 ASCII 文件名不转义成八进制，否则与映射表路径比对不上
    const out = execFileSync('git', [
      '-c',
      'core.quotePath=false',
      'diff',
      '--cached',
      '--name-only',
      '--diff-filter=ACM',
    ], {
      cwd: repoRoot,
      encoding: 'utf8',
      maxBuffer: 10 * 1024 * 1024,
    });
    return out
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean)
      .map((s) => toPosix(path.resolve(repoRoot, s)));
  } catch (e) {
    // diff 失败（如无暂存变更）时返回空
    return [];
  }
}

// ---------- --verify 模式（CI 用）：校验 map.json 是否仍然有效 ----------
// 逐条 rule 检查：source glob 必须在仓库命中 ≥1 个真实文件；docs 相对路径必须真实存在。
function verify() {
  console.log('[help-doc-sync:verify] 开始自检映射表（map.json）...');
  const rules = loadMap();

  let tracked = [];
  try {
    const out = execFileSync('git', ['ls-files'], {
      cwd: repoRoot,
      encoding: 'utf8',
      maxBuffer: 10 * 1024 * 1024,
    });
    tracked = out
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean);
  } catch (e) {
    console.error('[help-doc-sync:verify] ⚠ git ls-files 失败，source 命中校验跳过。');
  }

  let failed = 0;
  for (const rule of rules) {
    const matched = tracked.filter((f) => rule.sourceRe.test(f));
    if (matched.length === 0) {
      console.error(
        `[help-doc-sync:verify] ❌ source 未命中任何跟踪文件：${rule.source}`
      );
      failed++;
    }
    for (const doc of rule.docs) {
      if (!existsSync(doc)) {
        console.error(
          `[help-doc-sync:verify] ❌ 文档不存在：${toPosix(
            path.relative(repoRoot, doc)
          )}（来自 source ${rule.source}）`
        );
        failed++;
      }
    }
  }

  if (failed > 0) {
    console.error(`[help-doc-sync:verify] ❌ 共发现 ${failed} 处映射异常，请修 map.json。`);
    return 1;
  }
  const docCount = rules.reduce((n, r) => n + r.docs.length, 0);
  console.log(
    `[help-doc-sync:verify] ✅ 自检通过：${rules.length} 条 rule（合计 ${docCount} 个文档引用）均有效。`
  );
  return 0;
}

// ---------- 主逻辑 ----------
function main() {
  if (process.env.SKIP_DOC_SYNC) {
    console.log('[help-doc-sync] SKIP_DOC_SYNC 已设置，跳过检查。');
    return 0;
  }

  const rules = loadMap();
  const staged = getStagedFiles();

  if (staged.length === 0) {
    console.log('[help-doc-sync] 无暂存变更，跳过检查。');
    return 0;
  }

  // glob 是相对仓库根的路径，测试时用相对路径
  const changedSources = staged.filter((f) =>
    rules.some((r) => r.sourceRe.test(toPosix(path.relative(repoRoot, f))))
  );

  if (changedSources.length === 0) {
    console.log(
      `[help-doc-sync] 本次改动不涉及受管代码区域，跳过检查。`
    );
    return 0;
  }

  const helpMdDirPosix = toPosix(HELP_MD_DIR);
  const changedDocs = staged.filter((f) => f.startsWith(helpMdDirPosix));

  const violations = [];

  for (const src of changedSources) {
    const matchedRules = rules.filter((r) => r.sourceRe.test(toPosix(path.relative(repoRoot, src))));
    for (const rule of matchedRules) {
      const docHit = rule.docs.some((d) => changedDocs.includes(d));
      if (!docHit) {
        violations.push({
          source: toPosix(path.relative(repoRoot, src)),
          expectedDocs: rule.docs.map((d) =>
            toPosix(path.relative(repoRoot, d))
          ),
        });
      }
    }
  }

  if (violations.length === 0) {
    console.log(
      `[help-doc-sync] ✅ 通过：${changedSources.length} 个源文件改动均有对应文档同步。`
    );
    return 0;
  }

  // 去重（一个源可能命中多条 rule，按 source 合并 expectedDocs）
  const merged = new Map();
  for (const v of violations) {
    const existing = merged.get(v.source);
    if (existing) {
      existing.expectedDocs.push(...v.expectedDocs);
    } else {
      merged.set(v.source, { ...v, expectedDocs: [...v.expectedDocs] });
    }
  }
  const unique = [...merged.values()].map((v) => ({
    source: v.source,
    expectedDocs: [...new Set(v.expectedDocs)],
  }));

  console.error(
    `\n[help-doc-sync] ❌ 拦截提交：以下代码改动未同步对应帮助文档。\n`
  );
  for (const v of unique) {
    console.error(`  📄 源文件：${v.source}`);
    console.error(`     应同步文档：${v.expectedDocs.join('、')}`);
    console.error('');
  }
  console.error('  处理方式（二选一）：');
  console.error('  1) 修改对应的帮助文档后重新提交');
  console.error(
    '  2) 确认本次改动不涉及文档内容，提交时加环境变量跳过：\n' +
      '     SKIP_DOC_SYNC=1 git commit ...'
  );
  console.error('');

  return 1;
}

const args = process.argv.slice(2);
if (args.includes('--verify')) {
  process.exit(verify());
}
process.exit(main());