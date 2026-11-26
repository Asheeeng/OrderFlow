<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import { api } from './api'
const AiChart=defineAsyncComponent(()=>import('./AiChart.vue'))
type Row=Record<string,unknown>
type Reference={id:number;documentId:string;document:string;title:string;excerpt:string;similarity:number}
type Tool={name:string;source:string;definition:string;arguments:Row;sqlTemplate:string;elapsedMs:number;rows:Row[];
  chart:string;labelField:string;valueField:string}
type Reply={conversationId:number;answer:string;tools?:Tool[];references?:Reference[]}
type Inventory={id:number;snapshot:{asOf:string;source:string;definition:string;totals:Row;products:Row[];trend:Row[]};advice:Row[]}
const props=defineProps<{mode:string}>()
const config=ref<{chat:boolean;embedding:boolean}>()
const question=ref('')
const busy=ref(false)
const error=ref('')
const notice=ref('')
const conversation=ref<number>()
const sessions=ref<{id:number;updated_at:string}[]>([])
const messages=ref<{speaker:string;text:string;reply?:Reply}[]>([])
const sources=ref<Reference[]>([])
const documents=ref<Row[]>([])
const selected=ref<Row & {chunks?:Row[]}>()
const asOf=ref('')
const productId=ref<number>()
const inventory=ref<Inventory>()
const usable=computed(()=>props.mode==='knowledge'?config.value?.embedding:
  config.value?.chat && (props.mode!=='rag' || config.value.embedding))
const examples=computed(()=>props.mode==='agent'?['最近 30 天的销售额是多少？','找出销量最高的五种商品。','查看最近一个月订单状态分布。','哪些畅销商品库存不足？']:
  ['待支付订单能取消吗？','已付款订单可以退款吗？','怎样修改购物车数量？','发货后怎样确认收货？'])
const labels:Record<string,string>={products:'在售 SKU',total_units:'可用总量',out_of_stock:'缺货 SKU',low_stock:'低库存 SKU',
  high_demand_low_stock:'畅销低库存',slow_stock:'积压风险',product_id:'商品 ID',sku:'SKU',label:'名称 / 日期',
  available:'可用库存',sales_7:'近 7 天销量',previous_7:'上一个 7 天销量',sales_30:'近 30 天销量',
  daily_sales:'日均销量',days_left:'预计可售天数',risk:'风险',sales:'销售额',paid_orders:'成功支付单',
  category_id:'分类 ID',units:'销量',orders:'订单数'}
const risk:Record<string,string>={OUT_OF_STOCK:'缺货',HIGH_DEMAND_LOW_STOCK:'畅销低库存',LOW_STOCK:'低库存',NO_SALES:'无销量',SLOW_STOCK:'低销量',NORMAL:'正常'}
const toolNames:Record<string,string>={query_sales:'销售额',query_top_products:'畅销商品',query_low_stock:'低库存',
  query_order_status:'订单状态',query_sales_trend:'销售趋势',query_product_stock:'指定商品库存',
  query_category_sales:'分类销售',query_inventory_summary:'库存概况'}
const columnNames=(rows:Row[])=>Object.keys(rows[0] ?? {})
const cell=(key:string,v:unknown)=>v==null?'—':key==='risk'?risk[String(v)] ?? String(v):String(v)

async function handle(fn:()=>Promise<void>) {
  if(busy.value)return
  busy.value=true
  error.value=notice.value=''
  try {await fn()}
  catch(e){error.value=e instanceof Error?e.message:String(e)}
  finally{busy.value=false}
}
async function refreshSessions() {sessions.value=await api('/ai/conversations')}
onMounted(()=>void handle(async()=>{
  config.value=await api('/ai/status')
  if(props.mode==='knowledge')documents.value=await api('/ai/knowledge')
  if(props.mode==='agent'||props.mode==='rag')await refreshSessions()
}))
function clear() {conversation.value=undefined;messages.value=[];error.value=''}
function restore(id:number) {void handle(async()=>{
  const history=await api<{role:string;content:string}[]>(`/ai/conversations/${id}/messages`)
  conversation.value=id
  messages.value=history.map(row=>({speaker:row.role,text:row.content}))
})}
function send(value=question.value) {void handle(async()=>{
  const input=value.trim()
  if(!input)return
  question.value=''
  messages.value.push({speaker:'user',text:input})
  const reply=await api<Reply>('/ai/chat','POST',{question:input,conversationId:conversation.value})
  conversation.value=reply.conversationId
  messages.value.push({speaker:'assistant',text:reply.answer,reply})
  await refreshSessions()
})}
function rebuild(force=false) {void handle(async()=>{
  const result=await api<{changed:number;embedded:number;dimension:number}>('/ai/knowledge/rebuild','POST',{force})
  notice.value=`更新 ${result.changed} 篇，生成 ${result.embedded} 个向量，维度 ${result.dimension}。`
  documents.value=await api('/ai/knowledge')
  selected.value=undefined
})}
function openDocument(id:unknown) {void handle(async()=>{selected.value=await api('/ai/knowledge/'+String(id))})}
function search() {void handle(async()=>{sources.value=await api('/ai/knowledge/search','POST',{question:question.value.trim()})})}
function analyse() {void handle(async()=>{inventory.value=await api('/ai/inventory','POST',{asOf:asOf.value||undefined,productId:productId.value||undefined})})}
</script>

<template>
  <p v-if="config && !usable" class="notice" role="status">AI 尚未启用或所需模型未配置。基础业务可继续使用。</p>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  <p v-if="notice" class="notice" role="status">{{ notice }}</p>

  <section v-if="mode==='agent'||mode==='rag'" class="surface ai-workspace">
    <div class="ai-actions">
      <el-button :disabled="busy" @click="clear">新会话</el-button>
      <el-select :model-value="conversation" placeholder="近期会话" :disabled="busy" @change="restore">
        <el-option v-for="entry in sessions" :key="entry.id" :label="'会话 '+entry.id+' · '+entry.updated_at" :value="entry.id"/>
      </el-select>
    </div>
    <div class="ai-actions">
      <el-button v-for="example in examples" :key="example" :disabled="!usable||busy" @click="send(example)">{{ example }}</el-button>
    </div>
    <div v-if="messages.length" class="ai-dialogue" aria-live="polite">
      <article v-for="(entry,i) in messages" :key="i" :class="entry.speaker">
        <strong>{{ entry.speaker==='user'?'你':mode==='agent'?'数据助手':'智能客服' }}</strong>
        <p>{{ entry.text }}</p>
        <details v-for="(tool,index) in entry.reply?.tools" :key="index" open class="ai-detail">
          <summary>{{ toolNames[tool.name] }} · {{ tool.source }} · {{ tool.elapsedMs }} ms</summary>
          <p>{{ tool.definition }}</p>
          <dl><dt>参数</dt><dd><code>{{ JSON.stringify(tool.arguments) }}</code></dd></dl>
          <details><summary>SQL 模板</summary><pre>{{ tool.sqlTemplate }}</pre></details>
          <div v-if="tool.rows.length" class="table-wrap"><table>
            <thead><tr><th v-for="key in columnNames(tool.rows)" :key="key">{{ labels[key] ?? key }}</th></tr></thead>
            <tbody><tr v-for="(row,n) in tool.rows" :key="n"><td v-for="key in columnNames(tool.rows)" :key="key">{{ cell(key,row[key]) }}</td></tr></tbody>
          </table></div>
          <p v-else>该范围没有记录。</p>
          <AiChart v-if="tool.chart && tool.rows.length" :type="tool.chart" :rows="tool.rows" :label-field="tool.labelField" :value-field="tool.valueField"/>
        </details>
        <details v-for="source in entry.reply?.references" :key="source.id" class="ai-detail">
          <summary>引用 #{{ source.id }} · {{ source.document }} / {{ source.title }} · {{ source.similarity.toFixed(3) }}</summary>
          <p>{{ source.excerpt }}</p>
        </details>
      </article>
    </div>
    <form class="ai-actions" @submit.prevent="send()">
      <el-input v-model="question" maxlength="800" placeholder="输入问题" aria-label="问题" :disabled="!usable"/>
      <el-button native-type="submit" type="primary" :loading="busy" :disabled="!usable||!question.trim()">发送</el-button>
    </form>
  </section>

  <section v-if="mode==='knowledge'" class="surface ai-workspace">
    <div class="ai-actions">
      <el-button type="primary" :disabled="!usable" :loading="busy" @click="rebuild()">增量构建</el-button>
      <el-button :disabled="!usable||busy" @click="rebuild(true)">重建所有向量</el-button>
    </div>
    <p v-if="!documents.length">知识库暂无索引数据。</p>
    <div class="ai-docs">
      <button v-for="doc in documents" :key="String(doc.id)" type="button" @click="openDocument(doc.id)">
        <strong>{{ doc.title }}</strong><span>{{ doc.chunks }} 个片段 · {{ doc.model }} / {{ doc.dimension }} 维</span>
        <small>{{ doc.compatible?'当前配置兼容':'需重新构建' }} · {{ doc.updated_at }}</small>
      </button>
    </div>
    <article v-if="selected" class="ai-detail">
      <h2>{{ selected.title }}</h2><details><summary>完整文档</summary><pre>{{ selected.body }}</pre></details>
      <details v-for="chunk in selected.chunks" :key="String(chunk.id)"><summary>#{{ chunk.id }} {{ chunk.title }}</summary><p>{{ chunk.body }}</p></details>
    </article>
    <form class="ai-actions" @submit.prevent="search">
      <el-input v-model="question" maxlength="800" placeholder="测试向量检索" aria-label="检索问题"/>
      <el-button native-type="submit" :disabled="!usable||busy||!question.trim()">测试检索</el-button>
    </form>
    <details v-for="source in sources" :key="source.id" open class="ai-detail">
      <summary>#{{ source.id }} · {{ source.document }} / {{ source.title }} · {{ source.similarity.toFixed(3) }}</summary><p>{{ source.excerpt }}</p>
    </details>
  </section>

  <section v-if="mode==='inventory'" class="surface ai-workspace">
    <form class="ai-actions" @submit.prevent="analyse">
      <label>统计截止日（UTC） <input v-model="asOf" type="date"/></label>
      <el-input-number v-model="productId" :min="1" :controls="false" placeholder="商品 ID（可选）" aria-label="商品 ID"/>
      <el-button native-type="submit" type="primary" :loading="busy" :disabled="!usable">生成库存报告</el-button>
    </form>
    <template v-if="inventory">
      <p>{{ inventory.snapshot.asOf }} · {{ inventory.snapshot.source }} · {{ inventory.snapshot.definition }}</p>
      <dl class="ai-totals"><template v-for="(value,key) in inventory.snapshot.totals" :key="key"><dt>{{ labels[key] ?? key }}</dt><dd>{{ value }}</dd></template></dl>
      <div class="table-wrap"><table>
        <thead><tr><th v-for="key in columnNames(inventory.snapshot.products)" :key="key">{{ labels[key] ?? key }}</th></tr></thead>
        <tbody><tr v-for="(row,n) in inventory.snapshot.products" :key="n"><td v-for="key in columnNames(inventory.snapshot.products)" :key="key">{{ cell(key,row[key]) }}</td></tr></tbody>
      </table></div>
      <h2>补货与库存建议</h2>
      <ul><li v-for="item in inventory.advice" :key="String(item.productId)">{{ item.label }} · {{ item.advice }} · 基于库存 {{ (item.basis as Row).available }}、近 30 天销量 {{ (item.basis as Row).sales30 }}</li></ul>
      <template v-if="inventory.snapshot.trend.length">
        <h2>每日成功支付销量</h2><AiChart type="line" :rows="inventory.snapshot.trend" label-field="label" value-field="sold"/>
        <h2>每日库存流水净变化</h2><AiChart type="line" :rows="inventory.snapshot.trend" label-field="label" value-field="delta"/>
      </template>
    </template>
  </section>
</template>
