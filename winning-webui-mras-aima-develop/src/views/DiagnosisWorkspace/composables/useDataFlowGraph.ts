import type { DataFlow, DataFlowNode } from '@/types/chat';
import type { Graph } from '@antv/g6';
import { nextTick } from 'vue';
import { useTheme } from 'vuetify';

export type NodeClickCallback = (node: DataFlowNode | null) => void;

/** 主题色读取 */
function getThemeRgb(varName: string): string {
  const raw = getComputedStyle(document.documentElement).getPropertyValue(varName).trim();
  return raw || '';
}

function rgbToHex(rgbStr: string): string {
  const rgb = rgbStr.split(',').map((s) => parseInt(s.trim(), 10));
  if (rgb.length !== 3 || rgb.some(isNaN)) return '';
  return '#' + rgb.map((c) => c.toString(16).padStart(2, '0').toUpperCase()).join('');
}

function lightenRgb(rgbStr: string, factor: number): string {
  const rgb = rgbStr.split(',').map((s) => parseInt(s.trim(), 10));
  if (rgb.length !== 3) return rgbStr;
  return rgb.map((c) => Math.round(c + (255 - c) * factor)).join(', ');
}

function darkenRgb(rgbStr: string, factor: number): string {
  const rgb = rgbStr.split(',').map((s) => parseInt(s.trim(), 10));
  if (rgb.length !== 3) return rgbStr;
  return rgb.map((c) => Math.round(c * (1 - factor))).join(', ');
}

function darkenHexFill(hex: string, factor: number): string {
  return (
    '#' +
    hex
      .slice(1)
      .match(/.{2}/g)!
      .map((c) => {
        const v = Math.round(parseInt(c, 16) * (1 - factor));
        return v.toString(16).padStart(2, '0');
      })
      .join('')
  );
}

const NODE_TYPE_VAR: Record<string, string> = {
  TABLE: '--v-theme-primary',
  SOURCE_EXTRACT_SQL: '--v-theme-warning',
  EXTENDED_EVENT_SQL: '--v-theme-secondary',
  OVERVIEW_SQL: '--v-theme-success',
  DEPARTMENT_SQL: '--v-theme-success',
  PATIENT_SQL: '--v-theme-success',
  RESULT: '--v-theme-primary',
  CONFIGURATION: '--v-theme-surface-variant',
};

const NODE_TYPE_FALLBACK: Record<string, { fill: string; stroke: string }> = {
  TABLE: { fill: '#E8EDFE', stroke: '#2D5AFA' },
  SOURCE_EXTRACT_SQL: { fill: '#FFF3E6', stroke: '#FF8C00' },
  EXTENDED_EVENT_SQL: { fill: '#F0F0F0', stroke: '#666666' },
  OVERVIEW_SQL: { fill: '#E6F5EC', stroke: '#00AB44' },
  DEPARTMENT_SQL: { fill: '#EFF9F2', stroke: '#08C955' },
  PATIENT_SQL: { fill: '#EFF9F2', stroke: '#08C955' },
  RESULT: { fill: '#E8EDFE', stroke: '#2D5AFA' },
  CONFIGURATION: { fill: '#F5F5F5', stroke: '#BABABA' },
};

const DARK_FILL_FACTOR = 0.35;
const LIGHT_FILL_FACTOR = 0.88;
const LIGHT_FILL_FACTOR_SUBTLE = 0.93;

function getNodeStyle(node: DataFlowNode, isDark: boolean): { fill: string; stroke: string } {
  const varName = NODE_TYPE_VAR[node.nodeType] ?? '--v-theme-primary';
  const baseRgb = getThemeRgb(varName);
  const strokeHex = baseRgb ? rgbToHex(baseRgb) : '';
  const fallback = NODE_TYPE_FALLBACK[node.nodeType];

  if (!baseRgb || !strokeHex) {
    if (isDark) {
      return {
        fill: darkenHexFill(fallback.fill, DARK_FILL_FACTOR),
        stroke: fallback.stroke,
      };
    }
    return { fill: fallback.fill, stroke: fallback.stroke };
  }

  if (isDark) {
    // 深色主题：向黑色混合，节点与暗色背景协调，符合 MD3 dark surface container 规范
    return { fill: `rgb(${darkenRgb(baseRgb, DARK_FILL_FACTOR)})`, stroke: strokeHex };
  }

  // 浅色主题：向白色混合，生成 MD3 surface container 浅色变体
  const fillFactor =
    node.nodeType === 'DEPARTMENT_SQL' || node.nodeType === 'PATIENT_SQL'
      ? LIGHT_FILL_FACTOR_SUBTLE
      : LIGHT_FILL_FACTOR;

  return { fill: `rgb(${lightenRgb(baseRgb, fillFactor)})`, stroke: strokeHex };
}

export function useDataFlowGraph(onNodeClick: NodeClickCallback) {
  const theme = useTheme();
  let graphInstance: Graph | null = null;
  let lastFlow: DataFlow | null = null;
  let resizeObserver: ResizeObserver | null = null;
  let resizeFrame: number | null = null;
  let lastWidth = 0;
  let lastHeight = 0;

  function destroyGraph() {
    if (resizeFrame !== null) {
      cancelAnimationFrame(resizeFrame);
      resizeFrame = null;
    }
    if (resizeObserver) {
      resizeObserver.disconnect();
      resizeObserver = null;
    }
    if (graphInstance) {
      graphInstance.destroy();
      graphInstance = null;
    }
  }

  /** 高亮指定节点（或清除高亮），与画布点击/下拉选择共用 */
  function selectElement(nodeId: string | null) {
    if (!graphInstance || !lastFlow) return;
    if (nodeId) {
      graphInstance.setElementState({ [nodeId]: 'selected' });
      graphInstance.setElementState(
        Object.fromEntries(lastFlow.nodes.filter((n) => n.id !== nodeId).map((n) => [n.id, ''])),
      );
    } else {
      graphInstance.setElementState(Object.fromEntries(lastFlow.nodes.map((n) => [n.id, ''])));
    }
  }

  async function fitView() {
    if (!graphInstance) return;
    await graphInstance.fitView({ when: 'always', direction: 'both' }, { duration: 300 });
  }

  async function renderGraph(flow: DataFlow, container: HTMLElement | null) {
    if (!container) return;
    lastFlow = flow;
    destroyGraph();
    await nextTick();

    const { Graph: G6Graph } = await import('@antv/g6');

    const textColor = getThemeRgb('--v-theme-on-surface');
    const textHex = textColor ? rgbToHex(textColor) || '#000000' : '#000000';
    const secondaryTextRgb = getThemeRgb('--v-theme-on-surface-variant');
    const secondaryTextHex = secondaryTextRgb ? rgbToHex(secondaryTextRgb) || '#666666' : '#666666';
    const edgeStrokeRgb = getThemeRgb('--v-theme-outline');
    const edgeStroke = edgeStrokeRgb ? rgbToHex(edgeStrokeRgb) || '#BABABA' : '#BABABA';
    const surfaceRgb = getThemeRgb('--v-theme-surface');
    const surfaceHex = surfaceRgb ? rgbToHex(surfaceRgb) || '#ffffff' : '#ffffff';
    const containerWidth = container.clientWidth || 800;

    const isDark = theme.global.current.value.dark;

    const graphData = {
      nodes: flow.nodes.map((node) => ({
        id: node.id,
        data: node as unknown as Record<string, unknown>,
        style: {
          ...getNodeStyle(node, isDark),
          size: [164, 52] as [number, number],
          radius: 6,
          labelText: node.title,
          labelFontSize: 12,
          labelFill: textHex,
          labelPlacement: 'center',
          labelWordWrap: true,
          labelMaxWidth: '100%',
          labelMaxLines: 2,
        },
      })),
      edges: flow.edges.map((edge) => ({
        source: edge.from,
        target: edge.to,
        data: edge as unknown as Record<string, unknown>,
        style: {
          stroke: edgeStroke,
          endArrow: true,
          labelText: edge.label,
          labelFontSize: 10,
          labelFill: secondaryTextHex,
          labelBackground: true,
          labelBackgroundFill: surfaceHex,
          labelBackgroundOpacity: 0.85,
          labelBackgroundRadius: 2,
          labelBackgroundPadding: [2, 4] as [number, number],
          labelAutoRotate: false,
        },
      })),
    };

    const containerHeight = container.clientHeight;
    lastWidth = containerWidth;
    lastHeight = containerHeight;

    const graph = new G6Graph({
      container,
      width: containerWidth,
      height: containerHeight,
      padding: 20,
      theme: isDark ? 'dark' : 'light',
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      data: graphData as any,
      layout: { type: 'dagre', rankdir: 'TB', nodesep: 36, ranksep: 80 },
      node: { type: 'rect' },
      edge: { type: 'cubic-vertical' },
      zoomRange: [2 / 3, 1.5],
      behaviors: [{ type: 'drag-canvas', range: 0.25 }, { type: 'zoom-canvas' }, 'hover-activate'],
    });

    graph.on('node:click', (event: unknown) => {
      const evt = event as { target?: { id?: string } };
      const nodeId = evt.target?.id;
      if (!nodeId) return;

      selectElement(nodeId);
      const found = flow.nodes.find((n) => n.id === nodeId);
      onNodeClick(found ?? null);
    });

    await graph.render();

    await graph.fitView({ when: 'always', direction: 'both' }, { duration: 0 });

    graphInstance = graph;

    // 容器尺寸变化（响应式断点 / 面板调整）时自适应重绘
    resizeObserver?.disconnect();
    resizeObserver = new ResizeObserver((entries) => {
      if (!graphInstance || !container) return;
      const bounds = entries[0]?.contentRect;
      const width = Math.round(bounds?.width ?? container.clientWidth);
      const height = Math.round(bounds?.height ?? container.clientHeight);
      if (width <= 0 || height <= 0 || (width === lastWidth && height === lastHeight)) return;
      lastWidth = width;
      lastHeight = height;
      if (resizeFrame !== null) cancelAnimationFrame(resizeFrame);
      resizeFrame = requestAnimationFrame(() => {
        resizeFrame = null;
        if (!graphInstance) return;
        graphInstance.resize(width, height);
        void graphInstance.fitView({ when: 'always', direction: 'both' }, { duration: 0 });
      });
    });
    resizeObserver.observe(container);
  }

  /** 以编程方式选中节点（下拉定位用），同步画布高亮与回调 */
  function selectNode(node: DataFlowNode | null) {
    if (!graphInstance) {
      onNodeClick(node);
      return;
    }
    selectElement(node?.id ?? null);
    onNodeClick(node);
  }

  return { renderGraph, destroyGraph, fitView, selectNode };
}
