<script setup lang="ts">
import { computed } from 'vue'
import { useAdminStatistics } from '../admin-statistics'
import { shanghaiDate, statisticsTaskLabels } from '../statistics'
import StatisticsChart from '../components/StatisticsChart.vue'

const { fromDate, toDate, statistics, appliedRange, loading, error, load, applyRange, preset } = useAdminStatistics()
const subjects = computed(() => statistics.value?.subjects.map(item => ({ label: item.key, count: item.count })) || [])
const uploads = computed(() => statistics.value?.uploadTrend.map(item => ({ label: item.date, count: item.count })) || [])
const statuses = computed(() => statistics.value?.taskStatuses.map(item => ({ label: statisticsTaskLabels[item.key] || item.key, count: item.count })) || [])
const completionRate = computed(() => statistics.value?.taskCompletionRate == null ? '暂无可计算任务' :
  new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 }).format(statistics.value.taskCompletionRate))
const waiting = computed(() => statistics.value?.taskStatuses.find(item => item.key === 'WAIT_CONFIRM')?.count)
</script>

<template>
  <div class="materials-layout statistics-layout">
    <div class="section-heading"><div><span class="intro-eyebrow">STATISTICS</span><h1>统计大屏</h1><p>查看当前资料库存与指定期间的整理活动。</p></div><span class="timezone-label">上海时间 · Asia/Shanghai</span></div>
    <section class="materials-panel statistics-filter" aria-label="统计日期范围" :aria-busy="loading">
      <form class="range-form" @submit.prevent="applyRange">
        <label for="statistics-from">开始日期（含）<input id="statistics-from" v-model="fromDate" type="date" required :disabled="loading" /></label>
        <label for="statistics-to">结束日期（不含）<input id="statistics-to" v-model="toDate" type="date" required :disabled="loading" /></label>
        <button type="submit" class="query-button" :disabled="loading">查询</button><button type="button" :disabled="loading" @click="load">刷新已应用范围</button>
        <div class="range-presets" aria-label="快捷日期范围"><button type="button" :disabled="loading" @click="preset(7)">近 7 天</button><button type="button" :disabled="loading" @click="preset(30)">近 30 天</button></div>
      </form>
      <p class="range-help">按上海自然日统计，最多 90 天；结束日期当天不计入。修改日期后点击“查询”应用。</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="loading-state" role="status">正在读取统计…</p>
      <p v-else-if="appliedRange" class="applied-range" role="status">已应用范围：{{ shanghaiDate(appliedRange.from) }} 00:00（含）至 {{ shanghaiDate(appliedRange.to) }} 00:00（不含）</p>
    </section>
    <template v-if="statistics">
      <section class="materials-panel inventory-panel" aria-labelledby="inventory-title">
        <div class="panel-heading"><div><h2 id="inventory-title">当前资料</h2><p>当前库存不受日期筛选；包含未删除的在用与归档资料。</p></div></div>
        <dl class="inventory-counts"><div><dt>当前有效资料</dt><dd>{{ statistics.activeMaterialCount }}<small>份</small></dd></div><div><dt>其中受限资料</dt><dd>{{ statistics.restrictedMaterialCount }}<small>份</small></dd></div></dl>
        <h3>当前学科分布</h3><StatisticsChart kind="subjects" title="当前学科分布" :data="subjects" empty-label="暂无当前资料" />
      </section>
      <section class="period-section" aria-labelledby="period-title">
        <div class="period-heading"><h2 id="period-title">期间整理活动</h2><p>以下活动按已应用范围读取；尝试按发送时间、任务按创建时间。</p></div>
        <dl class="metric-grid">
          <div class="metric"><dt>模型发送尝试</dt><dd>{{ statistics.modelAttemptCount }}</dd><small>包含重试、失败及 UNKNOWN</small></div>
          <div class="metric"><dt>OCR 发送尝试</dt><dd>{{ statistics.ocrAttemptCount }}</dd><small>按实际发送尝试计数</small></div>
          <div class="metric"><dt>已知重试尝试</dt><dd>{{ statistics.retryAttemptCount }}</dd><small>历史归属未知：{{ statistics.unknownRetryAttempts }} 次</small></div>
          <div class="metric"><dt>曾产生合格候选的任务</dt><dd>{{ statistics.candidateTaskCount }}</dd><small>含 MOCK 与后来失效的合格候选</small></div>
          <div class="metric rate-metric"><dt>任务完成率</dt><dd :class="{ 'empty-rate': statistics.taskCompletionRate === null }">{{ completionRate }}</dd><small>仅已完成 ÷（已完成 + 失败）</small></div>
          <div class="metric"><dt>待确认任务</dt><dd>{{ waiting }}</dd><small>不计入完成率分母</small></div>
        </dl>
        <div class="period-charts">
          <section class="materials-panel" aria-labelledby="uploads-title"><h3 id="uploads-title">资料上传趋势</h3><p class="chart-note">含后来删除的上传事件，缺失日期记为 0。</p><StatisticsChart kind="uploads" title="资料上传趋势" :data="uploads" empty-label="暂无上传事件" /></section>
          <section class="materials-panel" aria-labelledby="statuses-title"><h3 id="statuses-title">任务状态分布</h3><p class="chart-note">期间创建任务的当前状态。</p><StatisticsChart kind="statuses" title="任务状态分布" :data="statuses" empty-label="暂无任务" /></section>
        </div>
      </section>
      <section class="materials-panel cost-panel" aria-labelledby="cost-title">
        <div class="panel-heading"><div><h2 id="cost-title">已提供费用</h2><p>按币种分别列示，发送尝试不代表厂商成功或账单确认。</p></div><p class="unknown-cost">费用未知：<strong>{{ statistics.unknownCostAttempts }}</strong> 次尝试</p></div>
        <p v-if="!statistics.knownCosts.length" class="cost-empty">未提供</p>
        <dl v-else class="cost-list"><div v-for="cost in statistics.knownCosts" :key="cost.currency"><dt>{{ cost.currency }}</dt><dd>{{ cost.amount }}</dd><small>{{ cost.knownAttempts }} 次已提供费用的尝试</small></div></dl>
        <p class="range-help">金额或币种未知的尝试单列，不补为 0，不估算费用。</p>
      </section>
      <details class="materials-panel metric-notes"><summary>查看统计口径说明</summary><ul><li v-for="note in statistics.metricNotes" :key="note">{{ note }}</li></ul></details>
    </template>
  </div>
</template>

<style scoped>
.statistics-layout { --stats-border: #dce5f2; --stats-blue: #315bc2; --stats-muted: #64748b; width: min(100%, 1200px); }
.timezone-label, .range-help, .chart-note { color: var(--stats-muted); font-size: 13px; line-height: 1.7; }
.timezone-label { flex-shrink: 0; }.statistics-filter { margin-bottom: 24px; }.range-form { display: flex; align-items: end; flex-wrap: wrap; gap: 12px; }
.range-form label { display: grid; gap: 8px; font-size: 13px; color: #43516a; }.range-form input, button { min-height: 42px; padding: 10px 12px; border: 1px solid var(--stats-border); border-radius: 8px; background: #fff; color: var(--stats-blue); font: inherit; }
button { cursor: pointer; font-size: 13px; }.query-button { background: var(--stats-blue); color: white; border-color: var(--stats-blue); }.range-presets { display: flex; gap: 8px; margin-left: auto; }
:disabled { opacity: .5; cursor: default; } :focus-visible { outline: 2px solid var(--stats-blue); outline-offset: 3px; }
.range-help { margin-bottom: 0; }.applied-range { margin-bottom: 0; color: #315bc2; font-size: 13px; line-height: 1.8; }.loading-state { color: var(--stats-muted); margin-bottom: 0; }
h2 { margin: 0; font-size: 21px; } h3 { margin: 0; font-size: 17px; }.panel-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px; }.panel-heading p, .period-heading p { margin: 8px 0 0; color: var(--stats-muted); font-size: 13px; line-height: 1.7; }
.inventory-panel { margin-bottom: 32px; }.inventory-counts { display: flex; gap: 64px; margin: 24px 0; padding-bottom: 24px; border-bottom: 1px solid #eef1f6; }.inventory-counts dt, .metric dt, .cost-list dt { color: #53647b; font-size: 13px; }.inventory-counts dd, .metric dd { font-size: 30px; font-weight: 700; margin: 10px 0; font-variant-numeric: tabular-nums; }.inventory-counts small { font-size: 13px; font-weight: 400; color: var(--stats-muted); margin-left: 8px; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin: 20px 0 24px; }.metric { padding: 20px 24px; border: 1px solid #ecf0f7; border-radius: 12px; background: #fff; }.metric small { color: var(--stats-muted); font-size: 12px; line-height: 1.7; }.metric dd.empty-rate { font-size: 20px; line-height: 36px; }
.period-charts { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 24px; }.period-charts > section { min-width: 0; }.chart-note { margin: 8px 0 16px; }.cost-panel { margin-top: 24px; }.cost-list { display: flex; flex-wrap: wrap; gap: 24px 48px; margin: 24px 0; }.cost-list dd { margin: 8px 0; font-size: 24px; font-weight: 600; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }.cost-list small { font-size: 12px; color: var(--stats-muted); }.cost-empty { padding: 12px 0; color: var(--stats-muted); }.metric-notes { margin-top: 24px; padding-top: 20px; padding-bottom: 20px; }.metric-notes summary { color: var(--stats-blue); cursor: pointer; }.metric-notes ul { padding-left: 24px; color: #53647b; font-size: 13px; line-height: 1.9; overflow-wrap: anywhere; }
@media (max-width: 980px) { .period-charts { grid-template-columns: 1fr; }.metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.range-presets { margin-left: 0; } }
@media (max-width: 480px) { .metric-grid { grid-template-columns: 1fr; }.inventory-counts { gap: 24px; }.inventory-counts dd { font-size: 26px; }.range-form label { width: 100%; }.range-form input { width: 100%; }.range-form button { flex: 1; }.range-presets { width: 100%; }.timezone-label { font-size: 12px; }.cost-list { display: block; }.cost-list > div + div { margin-top: 24px; } }
</style>
