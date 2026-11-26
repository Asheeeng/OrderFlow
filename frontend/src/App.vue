<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Box, Collection, DataAnalysis, Goods, Odometer, Refresh,
  Search, ShoppingCart, SwitchButton, Tickets, User, Van } from '@element-plus/icons-vue'
import { actor, api, currency, displayTime, integer, roles, statuses, type Actor, type Page, type Role, type Row } from './api'

const Trend = defineAsyncComponent(() => import('./Trend.vue'))
const AiPanel = defineAsyncComponent(() => import('./AiPanel.vue'))
const aiModes = ['agent','rag','knowledge','inventory']
const menus = {
  CUSTOMER: [['catalog','商品目录',Goods], ['cart','我的购物车',ShoppingCart], ['orders','我的订单',Tickets], ['rag','智能客服',Collection]],
  MERCHANT: [['overview','销售概览',DataAnalysis], ['products','商品与库存',Box], ['orders','订单处理',Van], ['inventory','AI 库存分析',DataAnalysis]],
  ADMIN: [['overview','平台概览',DataAnalysis], ['orders','订单管理',Tickets], ['products','商品管理',Box],
    ['users','用户管理',User], ['merchants','商家管理',Goods], ['labs','数据库实验',Collection], ['system','运行状态',Odometer], ['agent','AI 数据助手',DataAnalysis], ['knowledge','客服知识库',Collection]],
} as const
const menu = computed(() => actor.value ? menus[actor.value.role] : [])
const view = ref('')
const title = computed(() => menu.value.find(item => item[0] === view.value)?.[1] ?? '')
const intro: Record<string,string> = {
  agent: '用自然语言查询平台数据，核对 SQL 工具结果。',
  rag: '根据公共业务规则回答问题，并显示引用。',
  inventory: '按店铺实际销量和库存生成只读补货建议。',
  knowledge: '管理客服文档、向量空间与检索结果。',
  overview: '订单、销售及商品统计由只读从库提供。',
  catalog: '按商品名称或分类查找在售商品。',
  cart: '检查商品数量并选择收货地址。',
  orders: '查看订单进度、成交明细与物流信息。',
  products: '维护商品资料、上架状态与可用库存。',
  users: '查看角色和账号状态。',
  merchants: '查看商家资料并管理营业状态。',
  labs: '按需核验业务表数据和已保存的实验记录。',
  system: '检查 MySQL 主从复制与 Redis 缓存。',
}

const booting = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const registering = ref(false)
const username = ref('')
const password = ref('')
const displayName = ref('')
const rows = ref<Row[]>([])
const categories = ref<Row[]>([])
const addresses = ref<Row[]>([])
const cursor = ref(0)
const previous = ref<number[]>([])
const next = ref<number | null>(null)
const query = ref('')
const appliedQuery = ref('')
const category = ref<number>()
const orderStatus = ref('')
const addressId = ref<number>()
const chosen = ref<Row>()
const dialog = ref('')
const tracking = ref('')
const qty = ref(1)
const delta = ref(0)
const recipient = ref('')
const destination = ref('')
const product = ref({name:'', sku:'', categoryId:0, price:0.01, active:true, openingStock:0})
const analytics = ref<{summary:{orders:number; sales:number; products:number}; trend:{day:string; sales:number; orders:number}[]; source:{serverId:number; lagSeconds:number}}>()
const system = ref<{primary:Record<string,unknown>; replica:Record<string,unknown>; redis:Record<string,unknown>}>()
const counts = ref<Record<string,number>>()
const logs = [
  ['indexes','索引与慢查询'], ['concurrency','事务与并发'], ['binlog','日志恢复'],
  ['replication','主从复制'], ['acceptance','业务验收'],
]
const activeLog = ref('')
const logText = ref('')
const pageSize = 16
let requestId = 0

const cartTotal = computed(() => rows.value.reduce((sum,row) => sum + (row.price ?? 0) * (row.quantity ?? 0), 0))
const countTotal = computed(() => Object.values(counts.value ?? {}).reduce((sum,n) => sum+n, 0))
const tableNames: Record<string,string> = {
  users:'用户', merchants:'商家', categories:'分类', products:'商品', inventory:'库存',
  addresses:'收货地址', cart_items:'购物车', orders:'订单', order_items:'订单明细', payments:'支付',
  shipments:'物流', inventory_records:'库存流水', product_price_history:'价格审计',
}

async function action(work: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try { await work() }
  catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}

onMounted(async () => {
  try { actor.value = await api<Actor>('/auth/me') }
  catch { /* 首次进入时无会话 */ }
  finally { booting.value = false }
})
watch(actor, who => {
  if (who) choose(menus[who.role][0][0])
  else {
    requestId++
    dialog.value = ''
    view.value = ''
    rows.value = []
  }
})

function signIn() {
  return action(async () => {
    if (registering.value) {
      await api('/auth/register','POST',{username:username.value,password:password.value,name:displayName.value})
      registering.value = false
      notice.value = '注册成功，请登录。'
    } else {
      actor.value = await api<Actor>('/auth/login','POST',{username:username.value,password:password.value})
      password.value = ''
    }
  })
}
function logout() {
  return action(async () => { await api('/auth/logout','POST'); actor.value=null })
}
function choose(value: string) {
  view.value = value
  cursor.value = 0
  previous.value = []
  rows.value = []
  next.value = null
  query.value = appliedQuery.value = orderStatus.value = ''
  category.value = undefined
  dialog.value = activeLog.value = ''
  counts.value = undefined
  notice.value = ''
  void load()
}

async function fetchView(id: number) {
  const tab = view.value
  const params = new URLSearchParams({ after: String(cursor.value), size: String(pageSize) })
  if (['products','catalog'].includes(tab)) {
    if (category.value) params.set('category',String(category.value))
    if (appliedQuery.value) params.set('q',appliedQuery.value)
    const [choices,page] = await Promise.all([
      api<Row[]>('/categories'),
      api<Page>(`${tab==='products'?'/manage/products':'/products'}?${params}`),
    ])
    if (id===requestId) { categories.value=choices; rows.value=page.rows; next.value=page.next }
  } else if (tab==='orders' || tab==='users' || tab==='merchants') {
    if (tab==='orders') {
      params.set('before',String(cursor.value))
      if (orderStatus.value) params.set('status',orderStatus.value)
    }
    const page = await api<Page>(`${tab==='orders'?'/orders':'/admin/'+tab}?${params}`)
    if (id===requestId) { rows.value=page.rows; next.value=page.next }
  } else if (tab==='cart') {
    const [items, delivery] = await Promise.all([api<Row[]>('/cart'),api<Row[]>('/addresses')])
    if (id===requestId) {
      rows.value=items
      addresses.value=delivery
      if (!delivery.some(row => row.id===addressId.value)) addressId.value=delivery[0]?.id
    }
  } else if (tab==='overview') {
    analytics.value = undefined
    const result = await api<NonNullable<typeof analytics.value>>('/analytics')
    if (id===requestId) analytics.value=result
  } else if (tab==='system' || tab==='labs') {
    const result = await api<NonNullable<typeof system.value>>('/admin/system')
    if (id===requestId) system.value=result
  }
}

const loading = ref(false)
async function load() {
  const id = ++requestId
  loading.value = true
  error.value = ''
  try { await fetchView(id) }
  catch (e) { if (id===requestId) error.value = e instanceof Error ? e.message : String(e) }
  finally { if (id===requestId) loading.value=false }
}
function search() {
  cursor.value=0; previous.value=[]; appliedQuery.value=query.value.trim()
  void load()
}
function turn(forward: boolean) {
  if (forward && next.value!==null) { previous.value.push(cursor.value); cursor.value=next.value }
  if (!forward && previous.value.length) cursor.value=previous.value.pop() ?? 0
  void load()
}
function showCartItem(row: Row) {
  return action(async () => {
    const existing = await api<Row[]>('/cart')
    chosen.value=await api<Row>(`/products/${row.id}`)
    qty.value=existing.find(item => item.id===row.id)?.quantity ?? 1
    dialog.value='buy'
  })
}
function setCart(id: number, quantity: number) {
  return action(async () => {
    await api(`/cart/${id}`,'PUT',{quantity})
    dialog.value=''
    if (view.value==='cart') await load()
    notice.value=quantity ? '购物车已更新。' : '商品已移除。'
  })
}
function addAddress() {
  return action(async () => {
    const row = await api<{id:number}>('/addresses','POST',{recipient:recipient.value, destination:destination.value})
    addressId.value=row.id
    dialog.value=''
    await load()
  })
}
function buy() {
  return action(async () => {
    const order = await api<{ids:number[]}>('/orders','POST',{addressId:addressId.value})
    choose('orders')
    notice.value=`已创建 ${order.ids.length} 笔订单：${order.ids.map(id=>'#'+id).join('、')}`
  })
}
function showOrder(id: number) {
  return action(async () => { chosen.value=await api<Row>(`/orders/${id}`); tracking.value='';dialog.value='order' })
}
function transition(operation: string) {
  return action(async () => {
    if (!chosen.value) return
    const id = chosen.value.id
    await api(`/orders/${id}/${operation}`,'POST',operation==='ship'?{trackingNo:tracking.value}:undefined)
    chosen.value=await api<Row>(`/orders/${id}`)
    await load()
    notice.value='订单状态已更新。'
  })
}
function editProduct(row?: Row) {
  chosen.value=row
  product.value={name:row?.name ?? '', sku:row?.sku ?? '', categoryId:row?.category_id ?? categories.value[0]?.id ?? 0,
    price:row?.price ?? 0.01, active:row?.active ?? true, openingStock:0}
  dialog.value='product'
}
function saveProduct() {
  return action(async () => {
    const id=chosen.value?.id
    await api(id?`/manage/products/${id}`:'/manage/products',id?'PUT':'POST',product.value)
    dialog.value=''
    await load()
    notice.value='商品资料已保存。'
  })
}
function adjustStock() {
  return action(async () => {
    if (!chosen.value) return
    await api(`/manage/products/${chosen.value.id}/stock`,'POST',{delta:delta.value})
    dialog.value=''
    await load()
    notice.value='库存已调整。'
  })
}
function toggle(row: Row) {
  return action(async () => {
    const route=view.value==='products'?`/manage/products/${row.id}/active`:`/admin/${view.value}/${row.id}`
    await api(route,'PATCH',{active:!row.active})
    await load()
    notice.value='状态已更新。'
  })
}
function readCounts() { return action(async () => { counts.value=await api<Record<string,number>>('/admin/counts') }) }
function readLog(name:string) {
  return action(async () => {
    activeLog.value=name
    logText.value=''
    logText.value=await api<string>(`/admin/evidence/${name}`)
  })
}
function setAddressForm() { recipient.value='';destination.value='';dialog.value='address' }
const roleName = (role?: Role) => role ? roles[role] : ''
</script>

<template>
  <div v-if="booting" class="loading-screen" role="status">正在连接 OrderFlow…</div>
  <main v-else-if="!actor" class="auth-layout">
    <div class="auth-header"><span class="brand-mark"><Box /></span><strong>OrderFlow</strong></div>
    <div class="auth-content">
      <form class="auth-form" @submit.prevent="signIn">
        <h1>{{ registering ? '创建顾客账户' : '登录 OrderFlow' }}</h1>
        <p>{{ registering ? '填写用户名、密码和显示名称。' : '使用顾客、商家或管理员账号登录。' }}</p>
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <p v-if="notice" class="notice" role="status">{{ notice }}</p>
        <label>用户名<el-input v-model="username" aria-label="用户名" autocomplete="username" maxlength="40" required /></label>
        <label>密码<el-input v-model="password" aria-label="密码" type="password" show-password
          :autocomplete="registering?'new-password':'current-password'" required /></label>
        <label v-if="registering">显示名称<el-input v-model="displayName" aria-label="显示名称" maxlength="80" required /></label>
        <el-button native-type="submit" type="primary" :loading="busy">{{ registering ? '注册' : '登录' }}</el-button>
        <button type="button" class="auth-link" @click="registering=!registering;error='';notice=''">
          {{ registering ? '已有账号，返回登录' : '注册顾客账号' }}
        </button>
      </form>
    </div>
  </main>
  <div v-else class="app-layout">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark"><Box /></span><div><strong>OrderFlow</strong><small>订单与库存</small></div></div>
      <nav aria-label="角色菜单">
        <button v-for="[key,label,Icon] in menu" :key="key" :class="{selected:view===key}"
          :aria-current="view===key?'page':undefined" @click="choose(key)">
          <component :is="Icon" /><span>{{ label }}</span>
        </button>
      </nav>
      <div class="side-footer"><span class="avatar">{{ actor.name.slice(0,1) }}</span><div><strong>{{ actor.name }}</strong><small>{{ roles[actor.role] }}</small></div></div>
    </aside>
    <section class="workspace">
      <header class="topbar"><div>OrderFlow <span aria-hidden="true">/</span> {{ title }}</div>
        <button class="text-button" @click="logout"><SwitchButton /> 退出登录</button>
      </header>
      <main class="main" :aria-busy="loading">
        <div class="page-title"><div><h1>{{ title }}</h1><p>{{ intro[view] }}</p></div>
          <el-button plain :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
        <AiPanel v-if="aiModes.includes(view)" :key="view" :mode="view" />
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <p v-if="notice" class="notice" role="status">{{ notice }}</p>

        <template v-if="view==='overview' && analytics">
          <div class="metrics">
            <section><span>订单总数</span><strong>{{ integer(analytics.summary.orders) }}</strong></section>
            <section><span>累计已支付销售额</span><strong>{{ currency(analytics.summary.sales) }}</strong></section>
            <section><span>商品数量</span><strong>{{ integer(analytics.summary.products) }}</strong></section>
          </div>
          <section class="surface graph"><div class="section-head"><div><h2>销售趋势</h2><p>最近 30 天 · 按订单创建日期 · UTC</p></div>
            <span class="connection"><i></i>从库 {{ analytics.source.serverId }} · 延迟 {{ analytics.source.lagSeconds }} 秒</span>
          </div><Trend :values="analytics.trend" /></section>
        </template>

        <section v-if="view==='catalog' || view==='products'" class="surface">
          <form class="toolbar" @submit.prevent="search">
            <el-input v-model="query" :prefix-icon="Search" aria-label="商品名称" placeholder="商品名称前缀" clearable />
            <el-select v-model="category" aria-label="分类" clearable placeholder="全部分类" @change="search">
              <el-option v-for="row in categories" :key="row.id" :label="row.name ?? ''" :value="row.id" />
            </el-select>
            <el-button native-type="submit" type="primary">搜索</el-button>
            <el-button v-if="view==='products' && actor.role==='MERCHANT'" class="toolbar-end" @click="editProduct()">新建商品</el-button>
          </form>
          <div class="table-wrap"><table>
            <thead><tr><th>商品</th><th>分类</th><th>店铺</th><th class="numeric">单价</th><th class="numeric">可用库存</th>
              <th v-if="view==='products'">状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td><b>{{ row.name }}</b><small>{{ row.sku }}</small></td><td>{{ row.category }}</td><td>{{ row.merchant }}</td>
                <td class="numeric">{{ currency(row.price) }}</td><td class="numeric">{{ row.available }}</td>
                <td v-if="view==='products'"><span class="status" :class="row.active?'active':''">{{ row.active?'已上架':'已下架' }}</span></td>
                <td class="actions">
                  <template v-if="view==='products'">
                    <el-button link type="primary" @click="editProduct(row)">编辑</el-button>
                    <el-button v-if="actor.role==='MERCHANT'" link type="primary" @click="chosen=row;delta=0;dialog='stock'">调库存</el-button>
                    <el-button link @click="toggle(row)">{{ row.active?'下架':'上架' }}</el-button>
                  </template>
                  <el-button v-else link type="primary" @click="showCartItem(row)">商品详情</el-button>
                </td>
              </tr>
              <tr v-if="!rows.length"><td colspan="7" class="empty">{{ loading?'加载中…':'没有符合条件的商品' }}</td></tr>
            </tbody>
          </table></div>
        </section>

        <section v-if="view==='cart'" class="surface">
          <div class="table-wrap"><table><thead><tr><th>商品</th><th>店铺</th><th>单价</th><th>数量</th><th>操作</th></tr></thead>
          <tbody><tr v-for="row in rows" :key="row.id">
            <td><b>{{ row.name }}</b><small>{{ row.active?('库存 '+row.available):'已下架' }}</small></td><td>{{ row.merchant }}</td><td>{{ currency(row.price) }}</td>
            <td><el-input-number :model-value="row.quantity" :min="1" :max="999" size="small" aria-label="商品数量"
              @change="(n?:number) => n && setCart(row.id,n)" /></td>
            <td><el-button link type="danger" @click="setCart(row.id,0)">移除</el-button></td>
          </tr><tr v-if="!rows.length"><td colspan="5" class="empty">{{ loading?'加载中…':'购物车为空' }}</td></tr></tbody></table></div>
          <div class="toolbar checkout"><el-select v-model="addressId" aria-label="收货地址" placeholder="选择收货地址">
            <el-option v-for="row in addresses" :key="row.id" :value="row.id" :label="row.recipient+' · '+row.destination"/>
            </el-select><el-button @click="setAddressForm">添加地址</el-button>
            <div class="toolbar-end price"><span>购物车金额</span><strong>{{ currency(cartTotal) }}</strong></div>
            <el-button type="primary" :loading="busy" :disabled="!rows.length || !addressId" @click="buy">提交订单</el-button>
          </div>
        </section>

        <section v-if="view==='orders'" class="surface">
          <div class="toolbar"><el-select v-model="orderStatus" aria-label="订单状态" placeholder="全部状态" clearable @change="search">
            <el-option v-for="(label,key) in statuses" :key="key" :label="label" :value="key" />
          </el-select></div>
          <div class="table-wrap"><table><thead><tr><th>订单编号</th><th>店铺</th><th>下单时间（UTC）</th>
            <th class="numeric">成交金额</th><th>订单状态</th><th>操作</th></tr></thead><tbody>
            <tr v-for="row in rows" :key="row.id"><td><b>#{{ row.id }}</b></td><td>{{ row.merchant }}</td>
              <td>{{ displayTime(row.created_at) }}</td><td class="numeric">{{ currency(row.total) }}</td>
              <td><span class="status" :class="row.status?.toLowerCase()">{{ statuses[row.status ?? ''] }}</span></td>
              <td><el-button link type="primary" @click="showOrder(row.id)">查看订单</el-button></td></tr>
            <tr v-if="!rows.length"><td colspan="6" class="empty">{{ loading?'加载中…':'没有符合条件的订单' }}</td></tr>
          </tbody></table></div>
        </section>

        <section v-if="view==='users'||view==='merchants'" class="surface">
          <div class="table-wrap"><table><thead><tr><th>编号</th><th>名称</th><th>{{ view==='users'?'用户名':'关联用户' }}</th>
            <th v-if="view==='users'">角色</th><th>状态</th><th>操作</th></tr></thead>
            <tbody><tr v-for="row in rows" :key="row.id"><td>#{{ row.id }}</td><td><b>{{ row.display_name ?? row.name }}</b></td>
              <td>{{ row.username ?? '#'+row.user_id }}</td><td v-if="view==='users'">{{ roleName(row.role) }}</td>
              <td><span class="status" :class="row.active?'active':''">{{ row.active?'启用':'停用' }}</span></td>
              <td><el-button link :type="row.active?'danger':'primary'" :disabled="view==='users' && actor.id===row.id" @click="toggle(row)">{{ row.active?'停用':'启用' }}</el-button></td>
            </tr><tr v-if="!rows.length"><td colspan="6" class="empty">{{ loading?'加载中…':'暂无记录' }}</td></tr></tbody>
          </table></div>
        </section>

        <div v-if="['catalog','products','orders','users','merchants'].includes(view)" class="pager">
          <span>第 {{ previous.length+1 }} 页</span>
          <el-button :icon="ArrowLeft" :disabled="loading || !previous.length" @click="turn(false)">上一页</el-button>
          <el-button :disabled="loading || next===null" @click="turn(true)">下一页 <ArrowRight class="next-icon" /></el-button>
        </div>

        <template v-if="view==='system' || view==='labs'">
          <div v-if="system" class="metrics health">
            <section><span>MySQL 主库</span><strong>{{ system.primary.serverId }}</strong>
              <small>MySQL {{ system.primary.version }} · GTID {{ system.primary.gtidMode }}</small></section>
            <section><span>MySQL 从库</span><strong>{{ system.replica.ready?'复制正常':'复制异常' }}</strong>
              <small>接收 {{ system.replica.receiver ?? '—' }} · 应用 {{ system.replica.applier ?? '—' }} · 延迟 {{ system.replica.lagSeconds ?? '—' }} 秒</small></section>
            <section><span>Redis</span><strong>{{ system.redis.available?'在线':'MySQL 降级' }}</strong>
              <small>命中 {{ system.redis.hits }} · 未命中 {{ system.redis.misses }} · 降级 {{ system.redis.fallbackOperations }} · TTL {{ system.redis.ttlSeconds }} 秒</small></section>
          </div>
          <section v-if="view==='labs'" class="surface lab">
            <div class="section-head"><div><h2>业务表行数</h2><p v-if="counts">精确统计合计 {{ integer(countTotal) }} 行</p></div>
              <el-button :loading="busy" @click="readCounts">运行 COUNT(*)</el-button></div>
            <div v-if="counts" class="table-wrap"><table><thead><tr><th>表名</th><th>用途</th><th class="numeric">记录数</th></tr></thead>
              <tbody><tr v-for="(n,name) in counts" :key="name"><td><code>{{ name }}</code></td><td>{{ tableNames[name] }}</td><td class="numeric">{{ integer(n) }}</td></tr></tbody></table></div>
          </section>
          <section v-if="view==='labs'" class="surface lab">
            <div class="section-head"><div><h2>实验记录</h2><p>运行独立脚本后产生的证据文件。</p></div></div>
            <div class="toolbar log-tabs">
              <el-button v-for="[key,label] in logs" :key="key" :type="activeLog===key?'primary':'default'" @click="readLog(key)">{{ label }}</el-button>
            </div><pre v-if="logText" class="log">{{ logText }}</pre>
          </section>
        </template>
      </main>
    </section>

    <el-dialog :model-value="!!dialog" :title="({buy:'商品详情', order:'订单详情', product:'商品资料', stock:'调整库存',address:'添加收货地址'} as Record<string,string>)[dialog]"
      width="min(92vw, 590px)" @close="dialog=''">
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <template v-if="dialog==='buy' && chosen">
        <h2>{{ chosen.name }}</h2><p>{{ chosen.merchant }} · {{ chosen.category }}</p>
        <p class="price"><strong>{{ currency(chosen.price) }}</strong> · 库存 {{ chosen.available }}</p>
        <div class="dialog-actions"><el-input-number v-model="qty" :min="1" :max="999" aria-label="购买数量" />
          <el-button type="primary" :disabled="!chosen.available" :loading="busy" @click="setCart(chosen.id,qty)">保存到购物车</el-button></div>
      </template>
      <template v-if="dialog==='order' && chosen">
        <div class="order-heading"><h2>#{{ chosen.id }}</h2><span class="status" :class="chosen.status?.toLowerCase()">{{ statuses[chosen.status ?? ''] }}</span></div>
        <dl class="order-info"><dt>收货地址</dt><dd>{{ chosen.ship_to }}</dd><dt>支付时间（UTC）</dt><dd>{{ displayTime(chosen.paid_at) }}</dd>
          <dt>物流单号</dt><dd>{{ chosen.tracking_no ?? '—' }}</dd><dt>发货时间（UTC）</dt><dd>{{ displayTime(chosen.shipped_at) }}</dd>
          <dt>收货时间（UTC）</dt><dd>{{ displayTime(chosen.delivered_at) }}</dd></dl>
        <table><thead><tr><th>商品</th><th>成交单价</th><th class="numeric">数量</th></tr></thead><tbody>
          <tr v-for="item in chosen.items" :key="item.product_id"><td>{{ item.product_name }}</td><td>{{ currency(item.unit_price) }}</td><td class="numeric">{{ item.quantity }}</td></tr>
        </tbody></table>
        <div class="dialog-actions"><b class="price">合计 {{ currency(chosen.total) }}</b>
          <template v-if="actor.role==='CUSTOMER' && chosen.status==='PENDING'"><el-button :disabled="busy" @click="transition('cancel')">取消订单</el-button>
            <el-button type="primary" :loading="busy" @click="transition('pay')">模拟支付</el-button></template>
          <el-button v-if="actor.role==='ADMIN' && chosen.status==='PENDING'" :disabled="busy" @click="transition('cancel')">取消待支付订单</el-button>
          <el-button v-if="actor.role==='CUSTOMER' && chosen.status==='SHIPPED'" type="primary" :loading="busy" @click="transition('receive')">确认收货</el-button>
          <template v-if="actor.role==='MERCHANT' && chosen.status==='PAID'">
            <el-input v-model="tracking" aria-label="物流单号" placeholder="输入模拟物流单号" maxlength="48" />
            <el-button type="primary" :disabled="!tracking" :loading="busy" @click="transition('ship')">模拟发货</el-button>
          </template>
        </div>
      </template>
      <form v-if="dialog==='product'" class="form-grid" @submit.prevent="saveProduct">
        <label>商品名称<el-input v-model="product.name" aria-label="商品名称" maxlength="120" required /></label>
        <label v-if="!chosen">SKU<el-input v-model="product.sku" aria-label="SKU" maxlength="48" required /></label>
        <label>分类<el-select v-model="product.categoryId" aria-label="商品分类"><el-option v-for="row in categories" :key="row.id" :label="row.name ?? ''" :value="row.id"/></el-select></label>
        <label>单价<el-input-number v-model="product.price" aria-label="单价" :min="0.01" :precision="2" /></label>
        <label v-if="!chosen">期初库存<el-input-number v-model="product.openingStock" aria-label="期初库存" :min="0" :max="1000000" /></label>
        <label>上架状态 <el-switch v-model="product.active" aria-label="上架状态" /></label>
        <el-button native-type="submit" type="primary" :loading="busy">保存商品</el-button>
      </form>
      <form v-if="dialog==='stock'" class="form-grid" @submit.prevent="adjustStock">
        <p>{{ chosen?.name }} · 当前库存 {{ chosen?.available }}</p>
        <label>调整量（负数为减库存）<el-input-number v-model="delta" aria-label="调整量" :min="-1000000" :max="1000000" /></label>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="delta===0">保存调整</el-button>
      </form>
      <form v-if="dialog==='address'" class="form-grid" @submit.prevent="addAddress">
        <label>收件人<el-input v-model="recipient" aria-label="收件人" maxlength="80" required /></label>
        <label>详细地址<el-input v-model="destination" aria-label="详细地址" maxlength="255" required /></label>
        <el-button native-type="submit" type="primary" :loading="busy">保存地址</el-button>
      </form>
    </el-dialog>
  </div>
</template>
