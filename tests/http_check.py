"""通过前端代理调用实际 API，只创建带本次标识的合成验收资料。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import http.cookiejar
import json
import os
from pathlib import Path
import threading
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPCookieProcessor
import uuid

import pymysql

BASE = "http://127.0.0.1:" + os.environ["WEB_PORT"] + "/api"
RUN = uuid.uuid4().hex[:9]
SECRET = os.environ["DEMO_PASSWORD"]


class Client:
    def __init__(self):
        self.opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None

    def call(self, url, method="GET", body=None, expected=200, csrf=True):
        headers = {"Content-Type":"application/json"} if body is not None else {}
        if self.csrf and csrf and method != "GET":
            headers["X-OrderFlow-CSRF"] = self.csrf
        request = Request(BASE + url, data=json.dumps(body).encode() if body is not None else None,
                          method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=35)
        except HTTPError as error:
            response = error
        with response:
            payload = response.read()
            if response.code != expected:
                raise AssertionError(f"{method} {url}: expected {expected}, got {response.code} {payload[:350].decode(errors="replace")}")
            return json.loads(payload) if payload else None

    def login(self, username, role):
        actor = self.call("/auth/login", "POST", {"username":username,"password":SECRET})
        assert actor["role"] == role
        self.csrf = actor["csrf"]
        return actor["id"]


def passed(message):
    print("PASS — " + message, flush=True)


def connect(port, user="of_app"):
    password = os.environ["DB_APP_PASSWORD" if user == "of_app" else "DB_INSPECT_PASSWORD"]
    return pymysql.connect(host="127.0.0.1", port=int(os.environ[port]), user=user,
                           password=password, database="orderflow", charset="utf8mb4")


def create_product(merchant, suffix, stock, name="验收商品"):
    sku = f"TEST-{RUN}-{suffix}"
    body = {"sku":sku,"name":name,"categoryId":1,"price":12.35,"active":True,"openingStock":stock}
    id_ = merchant.call("/manage/products","POST",body)["id"]
    return id_, body


def new_customer(suffix):
    client = Client()
    username = f"qa_{RUN}_{suffix}"
    client.call("/auth/register","POST",{"username":username,"password":SECRET,"name":"合成验收顾客"},201)
    identifier = client.login(username,"CUSTOMER")
    address = client.call("/addresses","POST",{"recipient":"样例收件人","destination":"合成市／验收地址"})["id"]
    return client, identifier, address


def stock(client, product):
    return client.call(f"/products/{product}")["available"]


def constraints(product, order):
    conn = connect("MYSQL_PRIMARY_PORT")
    try:
        with conn.cursor() as cursor:
            statements = [
                ((1216, 1452), "INSERT INTO addresses(user_id,recipient,destination) VALUES (9000000000,'x','x')", ()),
                ((3819,), "UPDATE inventory SET available=-1 WHERE product_id=%s", (product,)),
                ((1062,), "INSERT INTO payments(order_id,amount) VALUES(%s,12.35)", (order,)),
                ((1048,), "UPDATE orders SET ship_to=NULL WHERE id=%s", (order,)),
            ]
            actual = []
            for codes, sql, args in statements:
                try:
                    cursor.execute(sql, args)
                    raise AssertionError(f"数据库应拒绝 {codes}")
                except pymysql.MySQLError as error:
                    assert error.args[0] in codes, (codes, error)
                    actual.append(error.args[0])
            passed(f"外键 {actual[0]}、CHECK {actual[1]}、唯一键 {actual[2]}、NOT NULL {actual[3]}")
    finally:
        conn.rollback()
        conn.close()


def main():
    print(datetime.now(timezone.utc).isoformat(), "synthetic=true", "run=" + RUN, flush=True)
    anon, admin, merchant, demo = (Client() for _ in range(4))
    anon.call("/admin/system", expected=401)
    anon.call("/auth/login","POST",{"username":"demo_customer","password":"incorrect-demo-password"},401)
    admin.login("demo_admin","ADMIN")
    merchant.login("demo_merchant","MERCHANT")
    demo.login("demo_customer","CUSTOMER")
    demo.call("/admin/users", expected=403)
    merchant.call("/admin/system", expected=403)
    admin.call("/cart", expected=403)
    admin.call("/admin/users")
    admin.call("/admin/merchants")
    admin.call("/manage/products")
    assert demo.call("/addresses")
    passed("三角色登录、未认证、错误密码、角色越权、管理列表与演示地址")
    buyer, buyer_id, address = new_customer("buyer")
    other, other_id, other_address = new_customer("other")
    product, form = create_product(merchant,"single",8)
    assert buyer.call("/products?" + urlencode({"q":"验收商品","category":1,"size":1}))["rows"]
    page = buyer.call("/products?size=5")
    further = buyer.call("/products?size=5&after=" + str(page["next"]))
    assert page["rows"][-1]["id"] < further["rows"][0]["id"]
    buyer.call(f"/cart/{product}","PUT",{"quantity":1},403,csrf=False)
    merchant.call("/manage/products/2/stock","POST",{"delta":1},404)
    merchant.call(f"/manage/products/{product}/active","PATCH",{"active":False})
    assert buyer.call(f"/products/{product}",expected=404)
    merchant.call(f"/manage/products/{product}/active","PATCH",{"active":True})
    admin.call(f"/admin/users/{other_id}","PATCH",{"active":False})
    other.call("/orders",expected=401)
    admin.call(f"/admin/users/{other_id}","PATCH",{"active":True})
    admin.call("/admin/merchants/1","PATCH",{"active":False})
    merchant.call("/manage/products",expected=403)
    admin.call("/admin/merchants/1","PATCH",{"active":True})
    passed("注册、分类搜索、游标分页、CSRF、对象越权、上下架、用户及商家启停")
    buyer.call(f"/cart/{product}","PUT",{"quantity":3})
    assert buyer.call("/cart")[0]["quantity"] == 3
    buyer.call("/orders","POST",{"addressId":other_address},404)
    buyer.call(f"/cart/{product}","PUT",{"quantity":2})
    order = buyer.call("/orders","POST",{"addressId":address})["ids"][0]
    assert not buyer.call("/cart") and stock(buyer,product) == 6
    other.call(f"/orders/{order}",expected=404)
    buyer.call(f"/orders/{order}/ship","POST",{"trackingNo":"ILLEGAL"},403)
    buyer.call(f"/orders/{order}/pay","POST")
    buyer.call(f"/orders/{order}/pay","POST",expected=409)
    buyer.call(f"/orders/{order}/cancel","POST",expected=409)
    updated = dict(form, price=17.85)
    merchant.call(f"/manage/products/{product}","PUT",updated)
    assert buyer.call(f"/products/{product}")["price"] == 17.85
    merchant.call(f"/orders/{order}/ship","POST",{"trackingNo":f"QA-{RUN}"})
    buyer.call(f"/orders/{order}/receive","POST")
    result = buyer.call(f"/orders/{order}")
    assert result["status"] == "COMPLETED" and result["total"] == 24.70 and result["items"][0]["unit_price"] == 12.35
    assert admin.call(f"/orders/{order}")["id"] == order
    passed(f"下单、减库存、模拟支付、单次支付、发货、收货、改价后成交价保留，order_id={order}")
    constraints(product,order)

    buyer.call(f"/cart/{product}","PUT",{"quantity":2})
    cancelled = buyer.call("/orders","POST",{"addressId":address})["ids"][0]
    assert stock(buyer,product) == 4
    buyer.call(f"/orders/{cancelled}/cancel","POST")
    buyer.call(f"/orders/{cancelled}/cancel","POST",expected=409)
    assert stock(buyer,product) == 6
    passed(f"取消归还库存、重复取消 409，order_id={cancelled}")

    buyer.call(f"/cart/{product}","PUT",{"quantity":1})
    assisted = buyer.call("/orders","POST",{"addressId":address})["ids"][0]
    admin.call(f"/orders/{assisted}/cancel","POST")
    assert buyer.call(f"/orders/{assisted}")["status"] == "CANCELLED" and stock(buyer,product) == 6
    passed("管理员可取消待支付订单，顾客端和库存同步更新")

    zero, _ = create_product(merchant,"zero",0,"回滚验收商品")
    buyer.call(f"/cart/{product}","PUT",{"quantity":1})
    buyer.call(f"/cart/{zero}","PUT",{"quantity":1})
    buyer.call("/orders","POST",{"addressId":address},409)
    assert stock(buyer,product) == 6 and len(buyer.call("/cart")) == 2
    for item in (product,zero):
        buyer.call(f"/cart/{item}","PUT",{"quantity":0})
    passed("前一项先减库存、后一项库存不足触发整单回滚，购物车保留")

    limited, _ = create_product(merchant,"parallel",3,"并发验收商品")
    workers = 8
    barrier = threading.Barrier(workers, timeout=30)

    def compete(index):
        client, _, target = new_customer(f"bid_{index}")
        client.call(f"/cart/{limited}","PUT",{"quantity":1})
        barrier.wait()
        try:
            return client.call("/orders","POST",{"addressId":target})["ids"][0]
        except AssertionError as error:
            if "got 409" not in str(error) or "库存不足" not in str(error):
                raise
            return None

    with ThreadPoolExecutor(max_workers=workers) as pool:
        outcomes = list(pool.map(compete, range(workers)))
    successful = [id_ for id_ in outcomes if id_ is not None]
    assert len(successful) == 3 and stock(buyer,limited) == 0, outcomes
    passed(f"并发 {workers} 请求 / 3 份库存，3 成功、5 库存冲突，最终库存 0；成功订单 {successful}")
    merchant.call(f"/manage/products/{limited}/stock","POST",{"delta":5})
    assert stock(buyer,limited) == 5
    passed("商家库存调整与流水")

    state = admin.call("/admin/system")
    assert state["primary"]["serverId"] == 71 and state["replica"]["serverId"] == 72
    assert state["replica"]["ready"] and state["replica"]["superReadOnly"]
    stock(buyer, product)
    initial_hits = admin.call("/admin/system")["redis"]["hits"]
    stock(buyer, product)
    stock(buyer, product)
    state = admin.call("/admin/system")
    assert state["redis"]["available"] and state["redis"]["hits"] >= initial_hits + 2
    assert state["redis"]["ttlSeconds"] == 90 and state["redis"]["misses"] > 0
    platform, shop = admin.call("/analytics"), merchant.call("/analytics")
    assert platform["source"]["serverId"] == shop["source"]["serverId"] == 72
    assert platform["summary"]["orders"] > shop["summary"]["orders"] > 0
    passed("主库 71、统计只读从库 72、Redis 实际命中 / 未命中 / 更新失效")
    buyer.call("/auth/logout","POST",expected=204)
    buyer.call("/orders",expected=401)
    passed("登出后原会话失效")
    Path(".runtime/last-api-test.json").write_text(json.dumps({
        "product":product, "order":order, "sku":form["sku"],
    }))


if __name__ == "__main__":
    main()
