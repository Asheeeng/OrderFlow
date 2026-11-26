import { shallowRef } from 'vue'

export type Role = 'CUSTOMER' | 'MERCHANT' | 'ADMIN'
export type Actor = { id: number; username: string; name: string; role: Role; csrf: string }
export type Row = {
  id: number; name?: string; username?: string; display_name?: string; role?: Role; status?: string
  sku?: string; price?: number; total?: number; active?: boolean; available?: number; quantity?: number
  category_id?: number; category?: string; merchant?: string; user_id?: number; merchant_id?: number
  created_at?: string; ship_to?: string; tracking_no?: string; destination?: string; recipient?: string
  paid_at?: string; shipped_at?: string; delivered_at?: string
  items?: {product_id: number; product_name: string; unit_price: number; quantity: number}[]
}
export type Page = { rows: Row[]; next: number | null }
export const actor = shallowRef<Actor | null>(null)

export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (actor.value && method !== 'GET') headers['X-OrderFlow-CSRF'] = actor.value.csrf
  const response = await fetch('/api' + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store',
  })
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/auth/')) actor.value = null
    const message = await response.json().catch(() => null)
    throw new Error(message?.message || `请求失败（${response.status}）`)
  }
  const data = await response.text()
  return (response.headers.get('content-type')?.includes('application/json') && data ? JSON.parse(data) : data) as T
}

export const roles = { CUSTOMER: '顾客', MERCHANT: '商家', ADMIN: '管理员' }
export const statuses: Record<string, string> = {
  PENDING: '待支付', PAID: '待发货', SHIPPED: '待收货', COMPLETED: '已完成', CANCELLED: '已取消',
}
export const currency = (n?: number) => n === undefined ? '—' : new Intl.NumberFormat('zh-CN', {
  style:'currency', currency:'CNY', minimumFractionDigits:2,
}).format(n)
export const integer = (n: number) => new Intl.NumberFormat('zh-CN').format(n)
export const displayTime = (s?: string) => s ? s.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19) : '—'
