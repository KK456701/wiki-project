import { defineConfig, loadEnv } from 'vite';
import type { Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import vuetify from 'vite-plugin-vuetify';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath, URL } from 'node:url';
import { API_BASE_PREFIX, APP_BASE_URL } from './src/config/app.ts';

/** Tailwind v4 的四个顶层 Cascade Layer（名称固定，由 @tailwindcss/vite 生成） */
const TAILWIND_LAYERS = ['theme', 'base', 'components', 'utilities'] as const;

/**
 * Vite 插件：生产构建时自动注入 CSS Cascade Layer 顺序声明。
 *
 * ## 背景
 * vite-plugin-vuetify 会将 Vuetify 组件 CSS 放在产物最前面，导致 @layer vuetify-components
 * 成为文档中最先声明的层（优先级最低）。Tailwind v4 的 preflight (@layer base) 中
 * `* { border: 0 solid }` 因此覆盖了 Vuetify 组件的边框样式（CSS Cascade Layers 规范中，
 * 层级优先级凌驾于选择器特异性之上）。
 *
 * ## 工作原理
 * 在 generateBundle 阶段（所有 CSS 已生成但尚未写入磁盘）：
 * 1. 扫描全部 CSS 产物，正则提取所有 @layer 名称
 * 2. 筛选出顶层 layer（Tailwind 四层 + vuetify-* 前缀层），排除嵌套子层
 * 3. 排序为 [theme, base, components] → [vuetify-*] → [utilities]
 * 4. 将 `@layer ...;` 声明作为内联 <style> 注入到 HTML <head>（先于所有外部 CSS）
 *
 * 这样无论 Vuetify 升级后层名如何变化，每次构建都能自动适配，无需手动维护 index.html。
 */
function cssLayerOrderPlugin(): Plugin {
  return {
    name: 'css-layer-order',
    enforce: 'post',
    apply: 'build',
    generateBundle(_options, bundle) {
      // 1. 从 CSS 产物中提取所有 @layer 名称
      const layerNames = new Set<string>();
      for (const asset of Object.values(bundle)) {
        if (asset.type !== 'asset' || !asset.fileName.endsWith('.css')) continue;
        const css = typeof asset.source === 'string' ? asset.source : '';
        // 匹配 @layer name { 和 @layer name1, name2; 两种形式
        for (const m of css.matchAll(/@layer\s+([\w\s,-]+)/g)) {
          for (const name of m[1].split(',')) {
            const trimmed = name.trim();
            if (/^[\w-]+$/.test(trimmed)) layerNames.add(trimmed);
          }
        }
      }

      // 2. 筛选顶层 layer（排除嵌套子层如 reset / transitions / typography 等）
      const topLevel = [...layerNames].filter(
        (name) =>
          TAILWIND_LAYERS.includes(name as (typeof TAILWIND_LAYERS)[number]) ||
          name.startsWith('vuetify'),
      );
      if (topLevel.length === 0) return;

      // 3. 排序：非 vuetify 非 utility（最低）→ vuetify-* → utilities（最高）
      topLevel.sort((a, b) => {
        if (a === 'utilities') return 1;
        if (b === 'utilities') return -1;
        // 非 vuetify 层（theme/base/components）排前，vuetify-* 排后
        return Number(a.startsWith('vuetify')) - Number(b.startsWith('vuetify'));
      });

      const styleTag = `  <style>@layer ${topLevel.join(', ')};</style>`;

      // 4. 注入到 HTML 产物（替换已有声明或新增）
      for (const asset of Object.values(bundle)) {
        if (asset.type !== 'asset' || !asset.fileName.endsWith('.html')) continue;
        let html = typeof asset.source === 'string' ? asset.source : '';
        // 移除已有的 @layer 声明（避免重复或过期残留）
        html = html.replace(/<style>\s*@layer[^<]*<\/style>\s*/g, '');
        // 必须注入到第一个 <link rel="stylesheet"> 之前，确保层级声明先于外部 CSS 解析
        html = html.replace(/<link\s+rel="stylesheet"/, `${styleTag}\n  <link rel="stylesheet"`);
        asset.source = html;
      }
    },
  };
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, import.meta.dirname, '');
  const apiTarget = env.VITE_AIMA_API_TARGET || 'http://127.0.0.1:8765';

  return {
    base: APP_BASE_URL,
    plugins: [vue(), vuetify(), tailwindcss(), cssLayerOrderPlugin()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: '127.0.0.1',
      port: 8675,
      strictPort: true,
      proxy: {
        [API_BASE_PREFIX]: {
          target: apiTarget,
          changeOrigin: true,
          rewrite: (path) => path.replace(new RegExp(`^${API_BASE_PREFIX}`), ''),
        },
      },
    },
  };
});
