<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElDialog } from 'element-plus'
import { useAdminStatistics } from '../admin-statistics'
import { shanghaiDate, statisticsTaskLabels } from '../statistics'
import StatisticsChart from '../components/StatisticsChart.vue'
import UiIcon from '../components/UiIcon.vue'

const { fromDate, toDate, statistics, appliedRange, loading, error, load, applyRange, preset } = useAdminStatistics()
const dateDialogOpen = ref(false)
const subjects = computed(() => statistics.value?.subjects.map(item => ({ label: item.key, count: item.count })) || [])
const uploads = computed(() => statistics.value?.uploadTrend.map(item => ({ label: item.date, count: item.count })) || [])
const statuses = computed(() => statistics.value?.taskStatuses.map(item => ({ label: statisticsTaskLabels[item.key] || item.key, count: item.count })) || [])
const completionRate = computed(() => statistics.value?.taskCompletionRate == null ? '暂无可计算任务' :
  new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 }).format(statistics.value.taskCompletionRate))
const waiting = computed(() => statistics.value?.taskStatuses.find(item => item.key === 'WAIT_CONFIRM')?.count)

async function applyDateRange() {
  await applyRange()
  if (statistics.value && !error.value) dateDialogOpen.value = false
}
</script>

<template>
  <div class="materials-layout statistics-layout">
    <div class="section-heading">
      <div><span class="intro-eyebrow">STATISTICS</span><h1>统计大屏</h1><p>资料库存、整理活动与 AI 调用，一览掌握。</p></div>
      <span class="timezone-label">上海时间 · Asia/Shanghai</span>
    </div>

    <section class="materials-panel statistics-toolbar" aria-label="统计日期范围" :aria-busy="loading">
      <div class="range-summary"><span class="range-caption">期间活动范围</span><strong v-if="appliedRange">{{ shanghaiDate(appliedRange.from) }} 至 {{ shanghaiDate(appliedRange.to) }}</strong><span v-else>{{ loading ? '正在读取统计…' : '请选择日期范围' }}</span><small>开始日含，结束日不含</small></div>
      <div class="toolbar-actions">
        <button type="button" :disabled="loading" @click="preset(7)">近 7 天</button>
        <button type="button" :disabled="loading" @click="preset(30)">近 30 天</button>
        <button type="button" :disabled="loading" @click="dateDialogOpen = true">设置日期</button>
        <button type="button" class="query-button" :disabled="loading" @click="load">刷新统计</button>
      </div>
    </section>
    <p v-if="error && !dateDialogOpen" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="loading" class="loading-state" role="status">正在读取统计…</p>

    <div v-if="statistics" class="statistics-dashboard">
      <div class="dashboard-main">
        <div class="overview-grid">
          <section class="materials-panel dashboard-card" aria-labelledby="inventory-title">
            <header class="card-heading"><h2 id="inventory-title">当前资料</h2><UiIcon name="folder" /></header>
            <dl class="inventory-counts"><div><dt>当前有效资料</dt><dd>{{ statistics.activeMaterialCount }}<small>份</small></dd></div><div><dt>其中受限资料</dt><dd>{{ statistics.restrictedMaterialCount }}<small>份</small></dd></div></dl>
            <p class="chart-note">当前学科分布 · 库存不受日期筛选</p>
            <StatisticsChart kind="subjects" title="当前学科分布" :data="subjects" empty-label="暂无当前资料" compact />
          </section>
          <section class="materials-panel dashboard-card" aria-labelledby="uploads-title">
            <header class="card-heading"><h2 id="uploads-title">资料上传趋势</h2><UiIcon name="chart" /></header>
            <p class="chart-note">已应用期间内的每日上传事件</p>
            <StatisticsChart kind="uploads" title="资料上传趋势" :data="uploads" empty-label="暂无上传事件" compact />
            <p class="card-footnote">包含后来删除的上传事件，缺失日期记为 0。</p>
          </section>
          <section class="materials-panel dashboard-card" aria-labelledby="statuses-title">
            <header class="card-heading"><h2 id="statuses-title">任务状态分布</h2><UiIcon name="tasks" /></header>
            <p class="chart-note">期间创建任务的当前状态</p>
            <StatisticsChart kind="statuses" title="任务状态分布" :data="statuses" empty-label="暂无任务" compact />
            <router-link class="card-link" to="/admin/ai-tasks">查看任务监控<UiIcon name="arrow" /></router-link>
          </section>
        </div>

        <div class="activity-grid">
          <section class="materials-panel dashboard-card" aria-labelledby="calls-title">
            <header class="card-heading"><h2 id="calls-title">AI 调用概览</h2><UiIcon name="spark" /></header>
            <p class="chart-note">按发送时间统计，包含重试、失败及 UNKNOWN。</p>
            <dl class="metric-grid">
              <div class="metric"><dt>模型发送尝试</dt><dd>{{ statistics.modelAttemptCount }}<small>次</small></dd></div>
              <div class="metric"><dt>OCR 发送尝试</dt><dd>{{ statistics.ocrAttemptCount }}<small>次</small></dd></div>
              <div class="metric"><dt>已知重试尝试</dt><dd>{{ statistics.retryAttemptCount }}<small>次</small></dd></div>
            </dl>
            <p class="card-footnote">历史重试归属未知：{{ statistics.unknownRetryAttempts }} 次。发送尝试不代表厂商成功或账单确认。</p>
          </section>
          <section class="materials-panel dashboard-card" aria-labelledby="period-title">
            <header class="card-heading"><h2 id="period-title">整理工作总览</h2><UiIcon name="cards" /></header>
            <div class="completion-summary"><span>任务完成率</span><strong :class="{ 'empty-rate': statistics.taskCompletionRate === null }">{{ completionRate }}</strong></div>
            <p class="card-footnote">仅已完成 ÷（已完成 + 失败），待确认任务不计入分母。</p>
            <dl class="work-counts"><div><dt>曾产生合格候选的任务</dt><dd>{{ statistics.candidateTaskCount }}</dd></div><div><dt>待确认任务</dt><dd>{{ waiting }}</dd></div></dl>
            <p class="card-footnote">候选任务含 MOCK 与后来失效的合格候选，按任务创建时间筛选。</p>
          </section>
        </div>
      </div>

      <aside class="statistics-rail" aria-label="费用与统计说明">
        <section class="materials-panel dashboard-card cost-panel" aria-labelledby="cost-title">
          <header class="card-heading"><h2 id="cost-title">已提供费用</h2><span class="panel-badge">按币种</span></header>
          <p class="chart-note">分别列示已提供的费用，不估算厂商账单。</p>
          <p v-if="!statistics.knownCosts.length" class="cost-empty">未提供</p>
          <dl v-else class="cost-list"><div v-for="cost in statistics.knownCosts" :key="cost.currency"><dt>{{ cost.currency }}</dt><dd>{{ cost.amount }}</dd><small>{{ cost.knownAttempts }} 次已提供费用的尝试</small></div></dl>
          <div class="unknown-cost"><span>费用未知的尝试</span><strong>{{ statistics.unknownCostAttempts }}<small>次</small></strong></div>
          <p class="card-footnote">金额或币种未知的尝试单列，不补为 0。</p>
        </section>
        <section class="materials-panel dashboard-card metric-notes" aria-labelledby="notes-title">
          <header class="card-heading"><h2 id="notes-title">统计口径</h2><UiIcon name="note" /></header>
          <p class="chart-note">当前库存包含未删除的在用与归档资料；期间活动按上方日期范围读取。</p>
          <ul><li v-for="note in statistics.metricNotes" :key="note">{{ note }}</li></ul>
        </section>
      </aside>
    </div>

    <el-dialog v-model="dateDialogOpen" title="设置统计日期" width="560px" class="workspace-dialog" align-center append-to-body :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading">
      <form class="range-form editor-panel" @submit.prevent="applyDateRange">
        <div class="date-fields"><label for="statistics-from">开始日期（含）<input id="statistics-from" v-model="fromDate" type="date" required :disabled="loading" /></label><label for="statistics-to">结束日期（不含）<input id="statistics-to" v-model="toDate" type="date" required :disabled="loading" /></label></div>
        <p class="range-help">按上海自然日统计，最多 90 天；结束日期当天不计入。</p>
        <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
        <div class="date-actions"><button type="button" :disabled="loading" @click="dateDialogOpen = false">取消</button><button type="submit" class="query-button" :disabled="loading">{{ loading ? '正在查询…' : '应用日期' }}</button></div>
      </form>
    </el-dialog>
  </div>
</template>

<style scoped>
.statistics-layout { width: 100%; }
.statistics-layout, .range-form { --stats-border: #dce5f2; --stats-blue: var(--brand-hover); --stats-muted: #64748b; }
.timezone-label, .chart-note, .card-footnote, .range-help { color: var(--stats-muted); font-size: 12px; line-height: 1.7; }
.timezone-label { flex-shrink: 0; }
.statistics-toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px; padding: 24px; margin-bottom: 16px; }
.range-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; font-size: 13px; }
.range-caption, .range-summary small { color: var(--stats-muted); font-weight: 400; font-size: 12px; }
.range-summary strong { font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums; }
.toolbar-actions, .date-actions { display: flex; flex-wrap: wrap; gap: 8px; }
button { min-height: 40px; padding: 8px 12px; border: 1px solid var(--stats-border); border-radius: var(--r-md); background: #fff; color: var(--stats-blue); font: inherit; font-size: 14px; cursor: pointer; }
.query-button { background: var(--stats-blue); color: white; border-color: var(--stats-blue); }
:disabled { opacity: .5; cursor: default; }
:focus-visible { outline: 2px solid var(--stats-blue); outline-offset: 3px; }
.loading-state { color: var(--stats-muted); }
.statistics-dashboard { display: grid; grid-template-columns: minmax(0, 1fr); align-items: start; gap: 24px; }
.dashboard-main, .statistics-rail { display: grid; min-width: 0; gap: 24px; }
.statistics-rail { grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; }
.overview-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px; align-items: stretch; }
.activity-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.statistics-dashboard .dashboard-card { min-width: 0; margin: 0; padding: 24px; border-radius: var(--r-lg); }
.card-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.card-heading h2 { margin: 0; font-size: 18px; font-weight: 600; line-height: 1.6; }
.card-heading > .ui-icon { width: 16px; height: 16px; color: var(--muted); }
.chart-note { margin: 12px 0; }
.card-footnote { margin: 12px 0 0; font-size: 11px; }
.inventory-counts { display: flex; gap: 20px; margin: 16px 0; }
.inventory-counts > div { flex: 1; min-width: 0; }
dt { color: #53647b; font-size: 12px; line-height: 1.7; }
dd { margin: 8px 0 0; font-weight: 600; font-variant-numeric: tabular-nums; }
.inventory-counts dd { font-size: 28px; }
dd small, .unknown-cost small { margin-left: 6px; color: var(--stats-muted); font-size: 12px; font-weight: 400; }
.card-link { display: flex; justify-content: space-between; align-items: center; min-height: 40px; margin-top: 8px; color: var(--stats-blue); font-size: 12px; }
.card-link .ui-icon { width: 14px; height: 14px; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 20px 0; }
.metric { min-width: 0; padding-right: 12px; }
.metric + .metric { padding-left: 16px; border-left: 1px solid var(--line); }
.metric dd { font-size: 28px; overflow-wrap: anywhere; }
.completion-summary { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; margin: 20px 0 8px; color: #53647b; font-size: 12px; }
.completion-summary strong { color: var(--ink); font-size: 32px; font-variant-numeric: tabular-nums; }
.completion-summary .empty-rate { font-size: 18px; }
.work-counts { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin: 20px 0 0; padding-top: 16px; border-top: 1px solid var(--line); }
.work-counts dd { font-size: 22px; }
.panel-badge { padding: 4px 8px; border-radius: var(--r-sm); background: var(--surface-2); color: var(--muted); font-size: 11px; }
.cost-list { display: grid; gap: 20px; margin: 20px 0; }
.cost-list dd { font-size: 26px; overflow-wrap: anywhere; }
.cost-list small { color: var(--stats-muted); font-size: 11px; }
.cost-empty { margin: 24px 0; color: var(--stats-muted); }
.unknown-cost { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding-top: 16px; border-top: 1px solid var(--line); color: #53647b; font-size: 12px; }
.unknown-cost strong { color: var(--ink); font-size: 22px; font-variant-numeric: tabular-nums; }
.metric-notes ul { display: grid; gap: 12px; margin: 16px 0 0; padding-left: 16px; color: #53647b; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.date-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.range-form .date-fields label { margin: 0; line-height: 1.7; }
.range-form input { margin-top: 8px; }
.range-help { margin: 16px 0; }
.date-actions { justify-content: flex-end; padding-top: 8px; }
@media (max-width: 1360px) {
  .statistics-dashboard { grid-template-columns: 1fr; }
  .statistics-rail { grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; }
}
@media (max-width: 1080px) {
  .overview-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .overview-grid > :last-child { grid-column: 1 / -1; }
}
@media (max-width: 620px) {
  .overview-grid, .activity-grid, .statistics-rail, .date-fields { grid-template-columns: 1fr; }
  .overview-grid > :last-child { grid-column: auto; }
  .statistics-toolbar { padding: 16px; }
  .range-summary { width: 100%; }
  .range-summary small { width: 100%; }
  .toolbar-actions { width: 100%; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .toolbar-actions button { min-height: 44px; }
  .statistics-dashboard .dashboard-card { padding: 16px; }
  .metric + .metric { padding-left: 12px; }
  .metric dd { font-size: 24px; }
  .timezone-label { font-size: 11px; }
}

/* v3 视觉统一 */
.statistics-toolbar { padding: 16px 24px; border-radius: var(--r-lg); }
button { min-height: 40px; padding: 7px 13px; border-color: var(--line-strong); border-radius: var(--r-md); color: #47617f; font-size: 14px; font-weight: 500; transition: background .16s, border-color .16s, color .16s; }
button:hover:not(:disabled) { border-color: #bcd3f1; background: #f4f8ff; color: var(--brand-hover); }
.query-button { border-color: var(--brand); color: #fff; }
.query-button:hover:not(:disabled) { border-color: var(--brand-hover); background: var(--brand-hover); color: #fff; }
.statistics-dashboard .dashboard-card { padding: 24px; }
.card-heading h2 { color: #33445e; font-size: 18px; }
.card-heading > .ui-icon { color: #8b9cb2; }
.chart-note, .card-footnote, .timezone-label, .range-help { color: #7b8da4; font-size: 12px; }
.inventory-counts dd, .metric dd { font-size: 28px; color: #22375a; }
.completion-summary strong { font-size: 30px; color: #22375a; }
.work-counts dd { font-size: 20px; }
.cost-list dd { font-size: 24px; }
.unknown-cost strong { font-size: 20px; }
.metric-notes ul { font-size: 12px; color: #5d738f; }
.card-link { color: var(--brand-hover); font-size: 12px; font-weight: 500; }
</style>
