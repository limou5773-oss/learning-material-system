<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { init, use, type EChartsType } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { AriaComponent, GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { statisticsChartOption, type ChartDatum, type ChartKind } from '../statistics-charts'

use([BarChart, LineChart, PieChart, AriaComponent, GridComponent, TooltipComponent, SVGRenderer])
const props = defineProps<{ kind: ChartKind; title: string; data: ChartDatum[]; emptyLabel: string; compact?: boolean }>()
const host = ref<HTMLDivElement | null>(null)
const hasData = computed(() => props.data.some(item => item.count > 0))
const height = computed(() => {
  const base = props.compact ? 200 : 280
  return `${props.kind === 'subjects' ? Math.max(base, props.data.length * 34) : base}px`
})
let chart: EChartsType | undefined, observer: ResizeObserver | undefined
function dispose() { observer?.disconnect(); observer = undefined; chart?.dispose(); chart = undefined }
function render() {
  if (!host.value || !hasData.value) { dispose(); return }
  if (!chart) {
    chart = init(host.value, undefined, { renderer: 'svg' })
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(host.value)
  }
  chart.setOption(statisticsChartOption(props.kind, props.data, props.title, props.compact), { notMerge: true })
}
onMounted(render)
watch(() => [props.kind, props.title, props.data, props.compact], render, { deep: true, flush: 'post' })
onUnmounted(dispose)
</script>

<template>
  <div class="statistics-chart" :class="{ 'is-compact': compact }">
    <div v-if="hasData" class="chart-scroll" tabindex="0" :aria-label="`${title}图表，可滚动；下方提供数据表`">
      <div ref="host" class="chart-host" :style="{ height }" role="img" :aria-label="title"></div>
    </div>
    <p v-else class="chart-empty" role="status">{{ emptyLabel }}</p>
    <details class="chart-data">
      <summary>查看{{ title }}数据表（{{ data.length }} 项）</summary>
      <div class="chart-table-wrap" tabindex="0" :aria-label="`${title}完整数据，可滚动`">
        <table><caption>{{ title }}</caption><thead><tr><th scope="col">{{ kind === 'uploads' ? '上海日期' : kind === 'subjects' ? '学科' : '任务状态' }}</th><th scope="col">数量</th></tr></thead>
          <tbody><tr v-for="item in data" :key="item.label"><th scope="row">{{ item.label }}</th><td>{{ item.count }}</td></tr><tr v-if="!data.length"><td colspan="2">{{ emptyLabel }}</td></tr></tbody>
        </table>
      </div>
    </details>
  </div>
</template>

<style scoped>
.chart-scroll { max-height: 420px; overflow: auto; }.chart-host { width: 100%; min-width: 220px; }
.chart-empty { min-height: 160px; display: grid; place-items: center; margin: 0; color: #64748b; }
.is-compact .chart-empty { min-height: 200px; font-size: 12px; }
.is-compact .chart-data { font-size: 11px; }
.chart-data { border-top: 1px solid #eef1f6; padding-top: 12px; font-size: 13px; color: #53647b; }
summary { cursor: pointer; line-height: 1.8; } :focus-visible { outline: 2px solid #315bc2; outline-offset: 3px; }
.chart-table-wrap { overflow: auto; max-height: 320px; margin-top: 12px; } table { width: 100%; border-collapse: collapse; text-align: left; }
caption { text-align: left; margin-bottom: 8px; font-weight: 600; } th, td { padding: 10px; border-bottom: 1px solid #eef1f6; overflow-wrap: anywhere; } tbody th { font-weight: 400; } th:last-child, td:last-child { text-align: right; }
</style>
