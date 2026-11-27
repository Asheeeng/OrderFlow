<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { use, init, type ECharts } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{values: {day: string; sales: number; orders: number}[]}>()
const target = ref<HTMLDivElement>()
let chart: ECharts | undefined
let observer: ResizeObserver | undefined
function draw() {
  const sales = new Map(props.values.map(row => [row.day.slice(0,10), row.sales]))
  const days = Array.from({length:30}, (_, index) => new Date(Date.now() - (29-index)*86400000).toISOString().slice(0,10))
  chart?.setOption({
    animationDuration: 450,
    color: ['#e0642a'],
    grid: {left: 12, right: 20, top: 28, bottom: 12, containLabel: true},
    tooltip: {trigger: 'axis', valueFormatter: (value: unknown) => `¥${Number(value).toLocaleString()}`},
    xAxis: {type:'category', boundaryGap:false, data:days.map(day => day.slice(5)),
      axisLine:{lineStyle:{color:'#eadfd2'}}, axisLabel:{color:'#8a7a6a', hideOverlap:true}, axisTick:{show:false}},
    yAxis: {type:'value', axisLabel:{color:'#8a7a6a', formatter: (value: number) => value >= 10000 ? `${value/10000}万` : String(value)},
      splitLine:{lineStyle:{color:'#f0e8db', type:'dashed'}}},
    series: [{name:'销售额', type:'line', symbol:'none', smooth: 0.22, lineStyle:{width:3},
      areaStyle:{color:{type:'linear', x:0, y:0, x2:0, y2:1, colorStops:[
        {offset:0,color:'rgba(224,100,42,0.20)'},{offset:1,color:'rgba(224,100,42,0.01)'},
      ]}}, data:days.map(day => sales.get(day) ?? 0)}],
  })
}
onMounted(() => {
  if (!target.value) return
  chart = init(target.value)
  draw()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(target.value)
})
watch(() => props.values, draw)
onUnmounted(() => { observer?.disconnect(); chart?.dispose() })
</script>

<template><div ref="target" class="trend" role="img" aria-label="最近 30 天销售额趋势图"></div></template>
