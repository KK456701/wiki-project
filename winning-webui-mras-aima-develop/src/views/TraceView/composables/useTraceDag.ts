import { computed } from 'vue';
import { MarkerType, type Edge, type Node } from '@vue-flow/core';
import { graphlib, layout } from 'dagre';
import type { TraceEdgeFull, TraceNodeFull } from '../types';
import { FLOW_STAGE_CONFIG, EDGE_TYPE_STYLE } from '../constants';

/** Vue Flow 自定义节点数据 */
export interface TraceFlowNodeData {
  node: TraceNodeFull;
}

export type TraceFlowNode = Node<TraceFlowNodeData>;
export type TraceFlowEdge = Edge;

const NODE_WIDTH = 160;
const NODE_HEIGHT = 64;

function buildDagreLayout(nodes: TraceFlowNode[], edges: TraceFlowEdge[]) {
  const g = new graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'TB', nodesep: 20, ranksep: 60, marginx: 20, marginy: 20 });

  for (const node of nodes) {
    g.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  }

  for (const edge of edges) {
    g.setEdge(edge.source, edge.target);
  }

  layout(g);

  for (const node of nodes) {
    const dagreNode = g.node(node.id);
    if (dagreNode) {
      node.position = {
        x: dagreNode.x - NODE_WIDTH / 2,
        y: dagreNode.y - NODE_HEIGHT / 2,
      };
    }
  }
}

export function useTraceDag(nodesData: () => TraceNodeFull[], edgesData: () => TraceEdgeFull[]) {
  const dagNodes = computed<TraceFlowNode[]>(() => {
    const nodeMap = new Map<string, TraceNodeFull>();
    for (const n of nodesData()) {
      nodeMap.set(n.nodeId, n);
    }

    const flowNodes: TraceFlowNode[] = nodesData().map((n) => ({
      id: n.nodeId,
      type: 'trace-node',
      position: { x: 0, y: 0 },
      data: { node: n },
      style: {
        borderColor: FLOW_STAGE_CONFIG[n.flowStage]?.color ?? '#888',
        borderWidth: '2px',
      },
    }));

    const incoming = new Set<string>();
    const outgoing = new Set<string>();
    for (const e of edgesData()) {
      incoming.add(e.toNodeId);
      outgoing.add(e.fromNodeId);
    }

    // Handle orphan nodes — only nodes that are connected via edges
    const connectedNodes = new Set<string>([...incoming, ...outgoing]);
    const orphanNodes = flowNodes.filter((n) => !connectedNodes.has(n.id));

    // Add orphan nodes as isolated (they'll still render but without edges)
    // Set them off to the right side by their flow_stage_order offset
    for (const orphan of orphanNodes) {
      if (!orphan.data) continue;
      const stageConfig = FLOW_STAGE_CONFIG[orphan.data.node.flowStage];
      orphan.position = {
        x: (stageConfig?.order ?? 1) * 200 - 100,
        y: 50,
      };
    }

    return flowNodes;
  });

  const dagEdges = computed<TraceFlowEdge[]>(() => {
    return edgesData().map((e) => {
      const style = EDGE_TYPE_STYLE[e.edgeType] ?? EDGE_TYPE_STYLE.sequence;
      return {
        id: `${e.fromNodeId}->${e.toNodeId}`,
        source: e.fromNodeId,
        target: e.toNodeId,
        style: {
          stroke: style.color,
          ...(style.strokeDasharray ? { strokeDasharray: style.strokeDasharray } : {}),
        },
        animated: style.animated,
        label: e.label || '',
        markerEnd: { type: MarkerType.ArrowClosed, color: style.color, width: 16, height: 16 },
      };
    });
  });

  return { dagNodes, dagEdges };
}

// Layout function to run after Vue Flow's 'nodesInitialized' event or when DAG data changes
export function layoutDagNodes(
  nodes: TraceFlowNode[],
  edges: TraceFlowEdge[],
): { nodes: TraceFlowNode[]; center: { x: number; y: number } } {
  buildDagreLayout(nodes, edges);

  // Calculate center for fit-view
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  for (const n of nodes) {
    if (n.position.x < minX) minX = n.position.x;
    if (n.position.y < minY) minY = n.position.y;
    if (n.position.x + NODE_WIDTH > maxX) maxX = n.position.x + NODE_WIDTH;
    if (n.position.y + NODE_HEIGHT > maxY) maxY = n.position.y + NODE_HEIGHT;
  }

  return {
    nodes,
    center: {
      x: (minX + maxX) / 2,
      y: (minY + maxY) / 2,
    },
  };
}
