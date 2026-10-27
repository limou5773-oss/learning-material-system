<script setup lang="ts">
import { useAdminTaskMonitor } from '../admin-task-monitor'
import { adminTaskStatuses, adminGoalLabel, adminStatusLabel, adminStageLabel, adminToolLabel, adminProviderLabel,
  adminStepStatusLabel, adminErrorLabel, formatAdminTime, formatAdminDuration } from '../admin-tasks'

const { status, fromDate, toDate, page, results, selected, steps, stepPage, loading, stepsLoading, error, stepsError,
  load, applyFilters, changePage, selectTask, loadSteps, changeStepPage } = useAdminTaskMonitor()
</script>

<template>
  <div class="materials-layout task-monitor">
    <div class="section-heading"><div><span class="intro-eyebrow">TASK MONITORING</span><h1>任务监控</h1><p>只读查看任务状态和工具执行记录。</p></div></div>
    <section class="materials-panel" :aria-busy="loading">
      <form class="task-filters" @submit.prevent="applyFilters">
        <label>任务状态<select v-model="status" :disabled="loading"><option value="">全部状态</option><option v-for="(text, value) in adminTaskStatuses" :key="value" :value="value">{{ text }}</option></select></label>
        <label>创建日自（含）<input v-model="fromDate" type="date" min="1000-01-01" max="9999-12-31" :disabled="loading" /></label>
        <label>创建日至（不含）<input v-model="toDate" type="date" min="1000-01-01" max="9999-12-31" :disabled="loading" /></label>
        <button type="submit" :disabled="loading">筛选</button><button type="button" :disabled="loading" @click="load">刷新</button>
      </form>
      <p class="task-help">日期按上海时区筛选创建时间，可留空。所有时间按上海时区显示。任务用时是开始至结束的跨度，包含人工确认等待。</p>
      <p class="task-help">工具记录数不等于厂商请求次数，调用统计请查看统计大屏。未知表示历史标识未识别；未记录表示没有对应时间或阶段。</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="list-state" role="status">正在读取任务…</p>
      <template v-else-if="results">
        <p v-if="!results.items.length" class="list-state">{{ page > 1 ? '本页暂无任务，请返回上一页查看。' : '暂无任务。' }}</p>
        <div v-else class="task-table-wrap" tabindex="0" aria-label="任务列表，可横向滚动">
          <table class="task-table">
            <thead><tr><th scope="col">任务 ID</th><th scope="col">用户 ID</th><th scope="col">资料 ID</th><th scope="col">目标</th><th scope="col">状态</th><th scope="col">阶段</th><th scope="col">步骤数</th><th scope="col">错误码及说明</th><th scope="col">创建时间</th><th scope="col">开始时间</th><th scope="col">结束时间</th><th scope="col">任务用时</th><th scope="col">工具步骤</th></tr></thead>
            <tbody><tr v-for="item in results.items" :key="item.id" :class="{ 'selected-row': selected?.id === item.id }">
              <td>{{ item.id }}</td><td>{{ item.userId }}</td><td>{{ item.materialId }}</td><td>{{ adminGoalLabel(item.goalType) }}</td><td>{{ adminStatusLabel(item.status) }}</td><td>{{ adminStageLabel(item.stage) }}</td><td>{{ item.stepCount }}</td><td class="task-error-cell">{{ adminErrorLabel(item.errorCode) }}</td>
              <td><time :datetime="item.createdAt">{{ formatAdminTime(item.createdAt) }}</time></td><td><time :datetime="item.startedAt || undefined">{{ formatAdminTime(item.startedAt) }}</time></td><td><time :datetime="item.finishedAt || undefined">{{ formatAdminTime(item.finishedAt) }}</time></td><td>{{ formatAdminDuration(item) }}</td>
              <td><button type="button" :disabled="loading || selected?.id === item.id" :aria-pressed="selected?.id === item.id" @click="selectTask(item)">{{ selected?.id === item.id ? '已选中' : '查看步骤' }}</button></td>
            </tr></tbody>
          </table>
        </div>
      </template>
      <div v-if="page > 1 || (results && results.total > results.pageSize)" class="pager" aria-label="任务列表分页">
        <button type="button" :disabled="page <= 1 || loading" @click="changePage(page - 1)">上一页</button>
        <span>第 {{ page }} 页<span v-if="results"> · 共 {{ results.total }} 条</span></span>
        <button type="button" :disabled="loading || !results || page * results.pageSize >= results.total" @click="changePage(page + 1)">下一页</button>
      </div>
      <section v-if="selected" class="task-steps" :aria-busy="stepsLoading" aria-labelledby="task-steps-title">
        <div class="steps-heading"><h2 id="task-steps-title">任务 #{{ selected.id }} 的工具步骤</h2><button type="button" :disabled="stepsLoading" @click="loadSteps">刷新步骤</button></div>
        <p class="task-help">步骤用时仅表示本条工具记录的开始至结束跨度。</p>
        <p v-if="stepsError" class="inline-error" role="alert">{{ stepsError }}</p>
        <p v-if="stepsLoading" class="list-state" role="status">正在读取步骤…</p>
        <template v-else-if="steps">
          <p v-if="!steps.items.length" class="list-state">{{ stepPage > 1 ? '本页暂无步骤，请返回上一页查看。' : '暂无步骤记录。' }}</p>
          <div v-else class="task-table-wrap" tabindex="0" aria-label="工具步骤，可横向滚动">
            <table class="task-table">
              <thead><tr><th scope="col">步骤</th><th scope="col">尝试</th><th scope="col">工具</th><th scope="col">类型</th><th scope="col">状态</th><th scope="col">错误码及说明</th><th scope="col">开始时间</th><th scope="col">结束时间</th><th scope="col">步骤用时</th></tr></thead>
              <tbody><tr v-for="item in steps.items" :key="`${item.stepNo}:${item.attemptNo}`"><td>{{ item.stepNo }}</td><td>{{ item.attemptNo }}</td><td>{{ adminToolLabel(item.toolName) }}</td><td>{{ adminProviderLabel(item.providerKind) }}</td><td>{{ adminStepStatusLabel(item.status) }}</td><td class="task-error-cell">{{ adminErrorLabel(item.errorCode) }}</td><td><time :datetime="item.startedAt">{{ formatAdminTime(item.startedAt) }}</time></td><td><time :datetime="item.finishedAt || undefined">{{ formatAdminTime(item.finishedAt) }}</time></td><td>{{ formatAdminDuration(item) }}</td></tr></tbody>
            </table>
          </div>
        </template>
        <div v-if="stepPage > 1 || (steps && steps.total > steps.pageSize)" class="pager" aria-label="工具步骤分页">
          <button type="button" :disabled="stepPage <= 1 || stepsLoading" @click="changeStepPage(stepPage - 1)">步骤上一页</button><span>第 {{ stepPage }} 页<span v-if="steps"> · 共 {{ steps.total }} 条</span></span><button type="button" :disabled="stepsLoading || !steps || stepPage * steps.pageSize >= steps.total" @click="changeStepPage(stepPage + 1)">步骤下一页</button>
        </div>
      </section>
    </section>
  </div>
</template>

<style scoped>
.task-monitor, .task-monitor .materials-panel, .task-steps { min-width: 0; max-width: 100%; }
.task-filters { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin-bottom: 16px; }
.task-filters label { display: grid; gap: 8px; min-width: 0; max-width: 100%; color: #43516a; font-size: 14px; }
.task-filters input, .task-filters select { min-width: 0; max-width: 100%; padding: 10px 12px; border: 1px solid #dfe6f0; border-radius: 8px; font: inherit; background: #fff; }
.task-monitor button { padding: 10px 12px; border: 1px solid #dce5f2; border-radius: 8px; color: #315bc2; background: #fff; cursor: pointer; }
.task-monitor :disabled { opacity: .5; cursor: default; }
.task-monitor :focus-visible { outline: 2px solid #315bc2; outline-offset: 2px; }
.task-help { color: #64748b; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.task-table-wrap { max-width: 100%; overflow-x: auto; }
.task-table { width: 100%; border-collapse: collapse; font-size: 13px; text-align: left; }
.task-table th, .task-table td { padding: 14px 10px; border-bottom: 1px solid #eef1f6; white-space: nowrap; }
.task-table th { color: #64748b; font-weight: 600; }
.task-table .task-error-cell { min-width: 100px; max-width: 240px; white-space: normal; overflow-wrap: anywhere; }
.selected-row { background: #f3f7ff; }
.task-steps { margin-top: 28px; padding-top: 24px; border-top: 1px solid #eef1f6; }
.steps-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.steps-heading h2 { margin: 0; font-size: 20px; overflow-wrap: anywhere; }
@media (max-width: 600px) { .task-filters label { flex: 1 1 100%; } .task-filters button { flex: 1; } }
</style>
