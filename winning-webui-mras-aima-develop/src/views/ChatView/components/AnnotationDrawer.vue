<script setup lang="ts">
defineProps<{
  open: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

// 状态说明表格数据
const STATUS_ROWS = [
  {
    icon: '📋 覆盖指标',
    category: '统计基数',
    meaning: '纳入统计范围的指标总数。以国家卫健委18项医疗核心制度为框架。',
    rule: '覆盖指标 = COUNT(指标清单)；与数据是否可算无关，只要在清单中即计入。',
    note: '本例 35 个指标全部在清单中。',
  },
  {
    icon: '✅ 达标',
    category: '指标状态',
    meaning: '计算结果满足目标值，制度落实到位。',
    rule: '结果值 ≥ 目标值 AND 数据完整度 ≥ 90%',
    note: '结果值达标但完整度 < 90% → 降为"待确认"。',
  },
  {
    icon: '❌ 未达标',
    category: '指标状态',
    meaning: '计算结果未达到目标值，制度落地存在差距。',
    rule: '结果值 < 目标值 AND 数据完整度 ≥ 90%',
    note: '需区分"制度未落实"还是"数据有问题"——看数据质量。',
  },
  {
    icon: '○ 待确认',
    category: '指标状态',
    meaning: '需要人工确认的指标。满足以下任一条件即标记：',
    rule: `1. 多方案结果分歧：极差 > 10pp
2. 临界状态：结果值在目标值 ±5% 区间
3. 口径选择需确认：AI 推荐非公版口径`,
    note: '有多个口径方案 ≠ 待确认；只有实质分歧或需决策时才标记。',
  },
  {
    icon: '正常（正）',
    category: '数据质量',
    meaning: '数据可靠，结果可信。',
    rule: '数据完整度 ≥ 90%',
    note: '—',
  },
  {
    icon: '⚠ 警告（警）',
    category: '数据质量',
    meaning: '数据存在缺失或埋点不完整，结果存疑。',
    rule: '70% ≤ 数据完整度 < 90%',
    note: '建议核实后确认。重点关注列表中以此 Badge 标记。',
  },
  {
    icon: '❌ 异常（异）',
    category: '数据质量',
    meaning: '数据严重缺失，或计算引擎执行失败，无法得出可信结果。',
    rule: `数据完整度 < 70%；或
系统执行异常：SQL报错 / 查询超时 / 字段缺失等`,
    note: '需优先排查。重点关注列表中以此 Badge 标记。',
  },
] as const;
</script>

<template>
  <v-navigation-drawer
    :model-value="open"
    temporary
    location="right"
    width="800"
    @update:model-value="emit('update:open', $event)"
  >
    <template #prepend>
      <!-- 抽屉头部 -->
      <div class="d-flex align-center justify-space-between pa-4 border-b flex-shrink-0">
        <div>
          <h2 class="text-headline-small mb-1">📝 标注说明</h2>
          <p class="text-body-medium text-medium-emphasis mb-0">页面指标、图标、规则的含义说明</p>
        </div>
        <v-btn icon="mdi-close" variant="text" size="small" @click="emit('update:open', false)" />
      </div>
    </template>
    <!-- 抽屉内容 -->
    <div class="pa-4 overflow-y-auto flex-1-1-0">
      <h3 class="text-body-large font-weight-bold mb-3"><span class="mr-1">📊</span>状态说明</h3>

      <p class="text-body-medium text-medium-emphasis mb-4 line-height-lg">
        页面中所有状态图标、Badge 使用的规则<b>源自同一套判定逻辑</b>，以下为各状态的统一定义。
        <b>指标状态</b>（达标/未达标/待确认）用于页面上方概览统计；
        <b>数据质量</b>（正常/警告/异常）用于各指标明细中的质量评级； 下方<b>重点关注列表</b>的
        Badge 直接对应数据质量等级。
      </p>

      <!-- 状态表格 -->
      <div class="overflow-x-auto mb-4">
        <table class="annotation-table w-100 text-body-medium">
          <thead>
            <tr>
              <th class="text-left">图标 / Badge</th>
              <th class="text-left">类别</th>
              <th class="text-left">业务含义</th>
              <th class="text-left">判定规则</th>
              <th class="text-left">注意</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in STATUS_ROWS" :key="i">
              <td class="font-weight-bold">{{ row.icon }}</td>
              <td>{{ row.category }}</td>
              <td>{{ row.meaning }}</td>
              <td>
                <code class="rule-code">{{ row.rule }}</code>
              </td>
              <td>{{ row.note }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 补充说明卡片：多方案分歧 -->
      <div class="anno-card mb-3">
        <div class="d-flex align-center gap-1 mb-2 font-weight-bold text-body-medium">
          <span>📐</span> 补充：多方案结果分歧 · 判定细则
        </div>
        <div class="text-body-medium text-medium-emphasis line-height-lg">
          <p class="mb-1"><b>前提：</b>该指标有 ≥ 2 个方案成功计算出结果</p>
          <p class="mb-1">
            <b>公式：</b>
            <code>极差(pp) = Max(各方案结果值) - Min(各方案结果值)</code>
            （百分点差，非相对差异率）
          </p>
          <p class="mb-1"><b>阈值：</b><code>极差 > 10pp → 标记分歧</code></p>
          <p class="mb-1"><b>跳过：</b>已有人工选定最终方案的，不重新标记</p>
          <p class="mb-0"><b>示例：</b>推荐 9.00%，备选A 94.50% → 极差 = 85.50pp > 10pp → 分歧</p>
        </div>
      </div>

      <!-- 补充说明卡片：数据完整度公式 -->
      <div class="anno-card">
        <div class="d-flex align-center gap-1 mb-2 font-weight-bold text-body-medium">
          <span>📐</span> 补充：数据完整度计算公式
        </div>
        <div class="text-body-medium text-medium-emphasis line-height-lg">
          <p class="mb-1">
            <code>完整度 = 实际有效记录数 ÷ 理论应有记录数 × 100%</code>
          </p>
          <p class="mb-0">
            理论应有记录数：从 HIS 推断统计周期内该业务场景的总发生次数<br />
            实际有效记录数：源表中关键字段非空且业务逻辑有效的记录数
          </p>
        </div>
      </div>
    </div>
  </v-navigation-drawer>
</template>

<style lang="scss" scoped>
.annotation-table {
  border-collapse: collapse;
  font-size: 12px;

  th,
  td {
    padding: 6px 8px;
    border: 1px solid rgba(var(--v-theme-on-surface), 0.12);
    vertical-align: top;
    min-width: 60px;
  }

  th {
    font-weight: 500;
    color: rgba(var(--v-theme-on-surface), 0.6);
    background: rgba(var(--v-theme-on-surface), 0.04);
    white-space: nowrap;
  }

  td {
    color: rgba(var(--v-theme-on-surface), 0.87);
  }

  tbody tr:hover {
    background: rgba(var(--v-theme-on-surface), 0.03);
  }

  // 固定第 1、2 列列宽
  th:nth-child(1),
  td:nth-child(1) {
    width: 100px;
  }

  th:nth-child(2),
  td:nth-child(2) {
    width: 70px;
  }
}

.rule-code {
  font-size: 11px;
  font-family: 'SF Mono', Consolas, monospace;
  white-space: pre-line;
  line-height: 1.5;
}

.line-height-lg {
  line-height: 1.7;
}

.anno-card {
  background: rgba(var(--v-theme-on-surface), 0.03);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.1);
  border-radius: 10px;
  padding: 12px 14px;
}
</style>
