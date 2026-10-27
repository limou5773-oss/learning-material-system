import type { ComposeOption } from 'echarts/core'
import type { BarSeriesOption, LineSeriesOption, PieSeriesOption } from 'echarts/charts'
import type { AriaComponentOption, GridComponentOption, TooltipComponentOption } from 'echarts/components'

export type ChartKind = 'subjects' | 'uploads' | 'statuses'
export interface ChartDatum { label: string; count: number }
type ChartOption = ComposeOption<BarSeriesOption | LineSeriesOption | PieSeriesOption | AriaComponentOption | GridComponentOption | TooltipComponentOption>

export function statisticsChartOption(kind: ChartKind, data: ChartDatum[], title: string): ChartOption {
  const common: ChartOption = {
    animation: false,
    color: ['#4777de', '#60a5b9', '#d8a35c', '#5c9c79', '#c76e68', '#8b92a5'],
    textStyle: { fontFamily: 'Microsoft YaHei, sans-serif', color: '#53647b' },
    tooltip: { renderMode: 'richText', confine: true },
    aria: { enabled: true, label: { description: `${title}。完整数据可通过下方数据表阅读。` }, decal: { show: true } },
  }
  if (kind === 'statuses') return { ...common, series: [{ type: 'pie', radius: ['42%', '65%'], center: ['50%', '46%'],
    avoidLabelOverlap: true, label: { formatter: '{b}：{c}', fontSize: 12 },
    data: data.map(item => ({ name: item.label, value: item.count })) }] }
  const horizontal = kind === 'subjects'
  return { ...common,
    grid: { left: 12, right: 28, top: 16, bottom: 24, containLabel: true },
    xAxis: horizontal ? { type: 'value', minInterval: 1 } : { type: 'category', data: data.map(item => item.label), boundaryGap: false, axisLabel: { hideOverlap: true } },
    yAxis: horizontal ? { type: 'category', inverse: true, data: data.map(item => item.label), axisLabel: { width: 110, overflow: 'truncate' } } : { type: 'value', minInterval: 1 },
    series: [horizontal ? { type: 'bar', barMaxWidth: 20, data: data.map(item => item.count) } :
      { type: 'line', symbolSize: 7, smooth: false, data: data.map(item => item.count), areaStyle: { opacity: 0.07 } }],
  }
}
