import MarkdownIt from 'markdown-it';
import type { Options } from 'markdown-it';
import type Renderer from 'markdown-it/lib/renderer.mjs';
import type Token from 'markdown-it/lib/token.mjs';
// 按需导入：只引入常见语言集（python/sql/javascript/bash 等 35 种），而非全部 190+ 语言
import hljs from 'highlight.js/lib/common';
// 通过 ?raw 将 CSS 文件以纯文本形式导入，运行时按主题动态注入 <style> 标签
import githubCSS from 'highlight.js/styles/github.css?raw';
import githubDarkCSS from 'highlight.js/styles/github-dark.css?raw';
// 复制图标 SVG（?raw 导入为字符串，内联到 HTML 中，CSS 通过 fill: currentColor 控制颜色）
import copyIconSvg from '@/assets/content-copy.svg?raw';

// table_open / table_close 在 markdown-it 默认规则中不存在（走 renderToken 兜底），
// 此处定义兜底渲染函数，用于 wrapper 内调用
function defaultTableOpenRenderer(tokens: Token[], idx: number): string {
  return `<${tokens[idx].tag}>`;
}
function defaultTableCloseRenderer(tokens: Token[], idx: number): string {
  return `</${tokens[idx].tag}>`;
}

/**
 * 渲染代码块头部（语言标签 + 复制按钮）
 */
function buildCodeBlockHeader(langDisplay: string, escapedCode: string): string {
  return `<div class="code-block-header">
  <span class="code-block-lang">${langDisplay}</span>
  <button class="code-block-copy-btn" data-code="${escapedCode}" title="复制代码">
    <div class="code-block-copy-icon" style="width:14px;height:14px">${copyIconSvg}</div>
  </button>
</div>`;
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  breaks: true,
  // 不使用 highlight 回调（它会在返回值不以 <pre 开头时自动包 <pre><code>）
  // 改为通过自定义 fence renderer 直接输出完整 HTML
});

/**
 * 自定义 fence 渲染器
 *
 * 直接输出完整的代码块 HTML 结构，绕过 markdown-it 的默认 <pre><code> 包裹行为。
 * 生成的 DOM 结构：
 *
 * <div class="code-block-wrapper">
 *   <div class="code-block-header">
 *     <span class="code-block-lang">语言名</span>
 *     <button class="code-block-copy-btn" data-code="原始代码">复制图标</button>
 *   </div>
 *   <div class="code-block-body">
 *     <pre class="hljs"><code class="language-xxx">高亮代码</code></pre>
 *   </div>
 * </div>
 */
md.renderer.rules.fence = function (
  tokens: Token[],
  idx: number,
  _options: Options,
  _env: Record<string, unknown>,
  _self: Renderer,
): string {
  const token = tokens[idx];
  const info = token.info ? token.info.trim() : '';
  // 从 fence info 中提取语言名（如 "python {1-3}" → "python"）
  const lang = info ? info.split(/\s+/)[0] : '';
  const safeLang = lang && hljs.getLanguage(lang) ? md.utils.escapeHtml(lang) : '';
  const langDisplay = safeLang || 'text';
  const escapedRaw = md.utils.escapeHtml(token.content);

  const headerHtml = buildCodeBlockHeader(langDisplay, escapedRaw);

  // 语法高亮
  let codeHtml: string;
  if (lang && hljs.getLanguage(lang)) {
    try {
      codeHtml = hljs.highlight(token.content, { language: lang }).value;
    } catch {
      codeHtml = escapedRaw;
    }
  } else {
    codeHtml = escapedRaw;
  }

  const langClassAttr = safeLang ? ` class="language-${safeLang}"` : '';

  return `<div class="code-block-wrapper">
${headerHtml}
<div class="code-block-body">
  <pre class="hljs"><code${langClassAttr}>${codeHtml}</code></pre>
</div>
</div>`;
};

// 为 linkify 自动生成的链接添加安全属性（rel="noopener noreferrer" + target="_blank"），
// 防止钓鱼攻击（target="_blank" 下新窗口可通过 window.opener 导航原页面）
// 同时添加 class="markdown-link" 方便样式定制
// 注意：仅在链接没有显式设置 target 时注入，避免覆盖手动编写的 HTML
const defaultLinkOpen =
  md.renderer.rules.link_open ||
  function (
    tokens: Token[],
    idx: number,
    options: Options,
    _env: Record<string, unknown>,
    self: Renderer,
  ): string {
    return self.renderToken(tokens, idx, options);
  };

md.renderer.rules.link_open = function (
  tokens: Token[],
  idx: number,
  options: Options,
  env: Record<string, unknown>,
  self: Renderer,
): string {
  const token = tokens[idx];

  // 如果链接未设置 target，则添加安全属性
  const targetIdx = token.attrIndex('target');
  if (targetIdx < 0) {
    token.attrPush(['target', '_blank']);
  }

  // 添加 rel="noopener noreferrer" 防止 window.opener 攻击
  const relIdx = token.attrIndex('rel');
  if (relIdx < 0) {
    token.attrPush(['rel', 'noopener noreferrer']);
  }

  // 添加 CSS 类名便于样式定制（如外链图标）
  token.attrJoin('class', 'markdown-link');

  return defaultLinkOpen(tokens, idx, options, env, self);
};

// 在 table 外层包裹 table-wrapper，方便横向滚动
md.renderer.rules.table_open = function (
  tokens: Token[],
  idx: number,
  _options: Options,
  _env: Record<string, unknown>,
  _self: Renderer,
): string {
  return `<div class="table-wrapper">${defaultTableOpenRenderer(tokens, idx)}`;
};

md.renderer.rules.table_close = function (
  tokens: Token[],
  idx: number,
  _options: Options,
  _env: Record<string, unknown>,
  _self: Renderer,
): string {
  return `${defaultTableCloseRenderer(tokens, idx)}</div>`;
};

/**
 * 将 Markdown 文本渲染为 HTML
 */
export function renderMarkdown(text: string): string {
  return md.render(text);
}

/** 导出标记 */
export interface ExportMarker {
  type: 'detail_export' | 'upload_comparison_export' | 'diagnosis_export';
  runId: string;
  /** upload_comparison 专有的 fileToken */
  fileToken?: string;
}

/**
 * 解析 assistant_message 中的导出标记，从正文移除并提取操作参数
 *
 * 支持的标记格式（文档 §6.3）：
 * - {{detail_export:RUN_xxx}}           → 指标明细导出
 * - {{upload_comparison_export:RUN_xxx:FILE_TOKEN}} → 上传对比导出
 * - {{diagnosis_export:DDR_xxx}}        → 诊断报告导出
 *
 * @param content 原始消息文本（模型返回，可能包含标记）
 * @returns 清洗后的文本和提取的导出标记列表
 */
export function parseExportMarkers(content: string): {
  cleanContent: string;
  markers: ExportMarker[];
} {
  const markers: ExportMarker[] = [];

  const regex = /\{\{(detail_export|upload_comparison_export|diagnosis_export):([^}]+)\}\}/g;
  const cleanContent = content.replace(regex, (_match, type: string, params: string) => {
    const parts = params.split(':');
    const runId = parts[0];
    const fileToken = parts[1];
    markers.push({
      type: type as ExportMarker['type'],
      runId,
      fileToken,
    });
    return '';
  });

  return { cleanContent, markers };
}

/** 当前注入的 highlight.js 样式节点引用 */
let highlightStyleNode: HTMLStyleElement | null = null;

/**
 * 根据主题（light/dark）动态注入 highlight.js 代码高亮主题样式
 *
 * @description
 * highlight.js 不提供 JS API 切换主题，每个主题是独立的 CSS 文件。
 * 该函数通过动态创建/替换 `<style>` 标签实现主题切换：
 * - light → `github.css`（白底，与浅色聊天气泡匹配）
 * - dark  → `github-dark.css`（暗色，与暗色聊天气泡匹配）
 *
 * 调用时机：应用初始化时 + Vuetify 主题切换时。
 *
 * @param prefersDark - 是否为暗色模式
 */
export function setHighlightTheme(prefersDark: boolean): void {
  if (highlightStyleNode) {
    highlightStyleNode.remove();
  }

  const style = document.createElement('style');
  style.setAttribute('id', 'highlight-theme');
  style.setAttribute('data-theme', prefersDark ? 'dark' : 'light');
  style.textContent = prefersDark ? githubDarkCSS : githubCSS;
  document.head.appendChild(style);
  highlightStyleNode = style;
}
