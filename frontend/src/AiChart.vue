<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { use, init, type ECharts } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([BarChart,LineChart,PieChart,GridComponent,TooltipComponent,LegendComponent,CanvasRenderer])
const props=defineProps<{type:string; rows:Record<string,unknown>[]; labelField:string; valueField:string}>()
const element=ref<HTMLDivElement>()
let chart:ECharts|undefined
let observer:ResizeObserver
function draw() {
  const labels=props.rows.map(row=>String(row[props.labelField] ?? ''))
  const values=props.rows.map(row=>Number(row[props.valueField] ?? 0))
  const colors=['#3568e8','#347f8a','#8db1fd','#7b8cae','#b4c3dd']
  if(props.type==='pie') {
    chart?.setOption({color:colors,tooltip:{trigger:'item'},legend:{bottom:0},
      series:[{type:'pie',radius:['35%','65%'],center:['50%','45%'],data:values.map((value,i)=>({name:labels[i],value}))}]})
  } else {
    chart?.setOption({color:colors,tooltip:{trigger:'axis'},grid:{left:10,right:20,top:18,bottom:18,containLabel:true},
      xAxis:{type:'category',data:labels,axisLabel:{hideOverlap:true,width:90,overflow:'truncate'}},
      yAxis:{type:'value'},series:[{type:props.type==='line'?'line':'bar',data:values}]})
  }
}
onMounted(()=>{ if(!element.value)return; chart=init(element.value);draw();observer=new ResizeObserver(()=>chart?.resize());observer.observe(element.value) })
watch(()=>[props.type,props.rows],draw)
onUnmounted(()=>{observer?.disconnect();chart?.dispose()})
</script>
<template><div ref="element" class="ai-chart" role="img" :aria-label="'SQL 统计图 '+valueField"></div></template>
