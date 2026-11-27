<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from './api'

type Settings={enabled:boolean;provider:string;chatUrl:string;chatModel:string;shared:boolean;embeddingUrl:string;embeddingModel:string;
  embeddingVersion:string;dimension:number;timeoutSeconds:number;maxTokens:number;dailyLimit:number;minuteLimit:number}
type Config={revision:number;settings:Settings;chatKeyConfigured:boolean;embeddingKeyConfigured:boolean;source:string;keyMask:string}
type Probe={kind:string;passed:boolean;model:string;elapsedMs:number;dimension?:number;detail:string}
type Row=Record<string,unknown>
const presets:Record<string,{url:string;chat:string[];embedding:string[]}>={
  alibaba:{url:'https://dashscope.aliyuncs.com/compatible-mode/v1',chat:['qwen4-max','qwen3.8-max-0902','kimi-k3.1-pro','deepseek-v4.1-flash-1017','qwen-plus'],embedding:['qwen3.7-text-embedding-flash']},
  siliconflow:{url:'https://api.siliconflow.cn/v1',chat:[],embedding:['BAAI/bge-m3']},
  custom:{url:'',chat:[],embedding:[]},
}
const categories:Record<string,string>={chat:'文本生成',tool:'Function Calling',embedding:'Embedding',knowledge:'知识库可用性'}
const fields:Record<string,string>={calls:'累计调用',today:'今日调用',input_tokens:'输入 Token',output_tokens:'输出 Token',
  total_tokens:'总 Token',average_ms:'平均耗时（ms）',successes:'成功',finished:'已结束',operation:'AI 功能',
  model:'模型',status:'状态',elapsed_ms:'耗时（ms）',created_at:'UTC 时间',label:'名称'}
const current=ref<Config>()
const state=ref<Settings>()
const chatKey=ref('')
const embeddingKey=ref('')
const probes=ref<Probe[]>([])
const documents=ref<Row[]>([])
const usage=ref<{summary:Row;functions:Row[];models:Row[];recent:Row[]}>()
const busy=ref(false)
const error=ref('')
const notice=ref('')
const ready=()=>documents.value.length>0&&documents.value.every(row=>Boolean(row.compatible)&&Number(row.chunks)>0)

function apply(next:Config){
  current.value=next;state.value={...next.settings};chatKey.value=embeddingKey.value=''
  if(next.revision===0&&!state.value.chatUrl&&!state.value.embeddingUrl) pick('alibaba')
}
function pick(provider:string){
  if(!state.value)return
  const p=presets[provider]
  Object.assign(state.value,{provider,chatUrl:p.url,chatModel:p.chat[0]??'',embeddingUrl:p.url,
    embeddingModel:p.embedding[0]??'',embeddingVersion:'1',shared:true})
}
const draft=()=>({revision:current.value?.revision,settings:state.value,
  chatKey:chatKey.value,embeddingKey:embeddingKey.value,clearChat:false,clearEmbedding:false})
async function refresh(){
  const [docs,report]=await Promise.all([api<Row[]>('/ai/knowledge'),api<NonNullable<typeof usage.value>>('/ai/config/usage')])
  documents.value=docs;usage.value=report
}
async function run(fn:()=>Promise<void>){
  if(busy.value)return
  busy.value=true;error.value=notice.value=''
  try{await fn()}catch(e){error.value=e instanceof Error?e.message:String(e)}
  finally{busy.value=false}
}
onMounted(()=>void run(async()=>{apply(await api('/ai/config'));await refresh()}))
function test(kind:string){void run(async()=>{probes.value=await api('/ai/config/test','POST',{draft:draft(),kind});await refresh()})}
function save(){void run(async()=>{apply(await api('/ai/config','PUT',draft()));notice.value='已保存，后续请求使用新配置。';await refresh()})}
function clear(key:string){void run(async()=>{
  apply(await api('/ai/config/clear','POST',{revision:current.value?.revision,key}))
  notice.value='已清除所选密钥并停用 AI。';await refresh()
})}
function rebuild(){void run(async()=>{
  const result=await api<{embedded:number}>('/ai/knowledge/rebuild','POST',{force:false})
  notice.value=`知识库更新完成，新增 ${result.embedded} 个向量。`;await refresh()
})}
const keys=(rows:Row[])=>Object.keys(rows[0]??{})
</script>

<template>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  <section v-if="state && current" class="surface ai-workspace">
    <header><h2>模型连接</h2><p>配置来源 {{current.source==='database'?'已保存配置':'部署环境'}} · 修订 {{current.revision}}</p></header>
    <form class="config-grid" @submit.prevent="save">
      <label><input v-model="state.enabled" type="checkbox"/> 启用 AI 服务</label>
      <label>服务商 <select :value="state.provider" @change="pick(($event.target as HTMLSelectElement).value)">
        <option value="alibaba">阿里云百炼</option><option value="siliconflow">硅基流动</option><option value="custom">自定义 OpenAI-compatible</option>
      </select></label>
      <label>聊天 Base URL <el-input v-model="state.chatUrl" aria-label="聊天 Base URL"/></label>
      <label>Chat Model <input v-model="state.chatModel" list="chat-models" aria-label="Chat Model"/>
        <datalist id="chat-models"><option v-for="item in presets[state.provider].chat" :key="item" :value="item"/></datalist>
      </label>
      <label>API Key <el-input v-model="chatKey" type="password" autocomplete="new-password" aria-label="API Key"
        :placeholder="current.chatKeyConfigured?current.keyMask+' 留空保留':'填写 API Key'"/></label>
      <label><input v-model="state.shared" type="checkbox"/> Embedding 共用接口和密钥</label>
      <template v-if="!state.shared">
        <label>Embedding Base URL <el-input v-model="state.embeddingUrl" aria-label="Embedding Base URL"/></label>
        <label>Embedding API Key <el-input v-model="embeddingKey" type="password" autocomplete="new-password" aria-label="Embedding API Key"
          :placeholder="current.embeddingKeyConfigured?current.keyMask+' 留空保留':'填写独立密钥'"/></label>
      </template>
      <label>Embedding Model <input v-model="state.embeddingModel" list="embedding-models" aria-label="Embedding Model"/>
        <datalist id="embedding-models"><option v-for="item in presets[state.provider].embedding" :key="item" :value="item"/></datalist>
      </label>
      <details class="config-wide ai-detail"><summary>高级参数</summary>
        <div class="config-grid">
          <label>Embedding 版本 <el-input v-model="state.embeddingVersion" aria-label="Embedding 版本"/></label>
          <label>已验证维度 <output>{{state.dimension||'待连接测试'}}</output></label>
          <label>响应超时（秒） <input v-model.number="state.timeoutSeconds" type="number" min="3" max="15"/></label>
          <label>最大输出 Token <input v-model.number="state.maxTokens" type="number" min="64" max="2000"/></label>
          <label>全局每日调用上限 <input v-model.number="state.dailyLimit" type="number" min="1" max="2000"/></label>
          <label>每用户每分钟上限 <input v-model.number="state.minuteLimit" type="number" min="1" max="120"/></label>
        </div>
      </details>
      <div class="config-wide ai-actions">
        <el-button type="primary" native-type="submit" :loading="busy">保存并立即应用</el-button>
        <el-button :disabled="busy" @click="test('all')">一键测试</el-button>
        <el-button v-for="(label,kind) in categories" :key="kind" :disabled="busy" @click="test(kind)">测试{{label}}</el-button>
      </div>
      <div class="config-wide ai-actions">
        <el-button type="danger" plain :disabled="busy||!current.chatKeyConfigured" @click="clear('chat')">清除聊天密钥并停用 AI</el-button>
        <el-button v-if="!current.settings.shared" type="danger" plain :disabled="busy||!current.embeddingKeyConfigured" @click="clear('embedding')">清除 Embedding 密钥并停用 AI</el-button>
      </div>
    </form>
    <ul v-if="probes.length"><li v-for="item in probes" :key="item.kind">
      {{categories[item.kind]}} · {{item.passed?'通过':'未通过'}} · {{item.model||'未配置'}} · {{item.elapsedMs}} ms
      <span v-if="item.dimension"> · {{item.dimension}} 维</span> · {{item.detail}}
    </li></ul>
  </section>
  <section v-if="current" class="surface ai-workspace">
    <h2>知识库</h2><p>{{ready()?'当前向量空间可用':'未建立当前向量空间，请保存配置后重建。'}}</p>
    <div class="ai-actions"><el-button :disabled="busy||!current.settings.enabled" @click="rebuild">重建不兼容向量</el-button></div>
    <ul><li v-for="doc in documents" :key="String(doc.id)">{{doc.title}} · {{doc.chunks}} 个片段 · {{doc.dimension}} 维</li></ul>
  </section>
  <section v-if="usage" class="surface ai-workspace">
    <header><h2>用量和最近请求</h2><p>Token 仅统计供应商返回值。成功率按已结束的提供商调用计算。</p></header>
    <div class="ai-actions"><el-button :disabled="busy" @click="run(refresh)">刷新用量</el-button></div>
    <dl class="ai-totals">
      <template v-for="key in ['today','calls','input_tokens','output_tokens','total_tokens','average_ms']" :key="key">
        <dt>{{fields[key]}}</dt><dd>{{usage.summary[key]??'—'}}</dd>
      </template>
      <dt>成功率</dt><dd>{{Number(usage.summary.finished)?(100*Number(usage.summary.successes)/Number(usage.summary.finished)).toFixed(1)+'%':'—'}}</dd>
      <dt>每日 / 每用户每分钟上限</dt><dd>{{current?.settings.dailyLimit}} / {{current?.settings.minuteLimit}}</dd>
    </dl>
    <template v-for="[title,rows] in [['按功能',usage.functions],['按模型',usage.models],['最近 30 条请求',usage.recent]] as [string,Row[]][]" :key="title">
      <h3>{{title}}</h3>
      <div v-if="rows.length" class="table-wrap"><table><thead><tr><th v-for="key in keys(rows)" :key="key">{{fields[key]??key}}</th></tr></thead>
        <tbody><tr v-for="(row,index) in rows" :key="index"><td v-for="key in keys(rows)" :key="key">{{row[key]??'—'}}</td></tr></tbody></table></div>
      <p v-else>暂无请求记录。</p>
    </template>
  </section>
</template>
