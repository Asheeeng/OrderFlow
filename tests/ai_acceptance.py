"""End-to-end fixture evaluation through Nginx, Java and the actual MySQL volumes."""
from datetime import datetime, timezone
from functools import partial
from time import perf_counter
import json
import os
import sys
from uuid import uuid4

from ai_cases import AGENT, RAG
from http_check import Client, connect, create_product, new_customer

NONCE = uuid4().hex[:7]
READ_SQL = {
    "query_sales": ("SELECT COALESCE(SUM(amount),0),COUNT(*) FROM payments WHERE status='SUCCESS' AND paid_at>=%s AND paid_at<%s",
                    ("start","endExclusive"), ("sales","paid_orders")),
    "query_top_products": ("""SELECT i.product_id,SUM(i.quantity) qty FROM order_items i
        WHERE i.order_id IN(SELECT order_id FROM payments WHERE status='SUCCESS' AND paid_at>=%s AND paid_at<%s)
        GROUP BY i.product_id ORDER BY qty DESC,i.product_id LIMIT %s""",
        ("start","endExclusive","limit"),("product_id","units")),
    "query_low_stock": ("""SELECT i.product_id,i.available FROM inventory i
        WHERE i.available<=%s AND EXISTS(SELECT 1 FROM products p WHERE p.id=i.product_id AND p.active=1)
        ORDER BY i.available,i.product_id LIMIT %s""", ("threshold","limit"),("product_id","available")),
    "query_order_status": ("SELECT status,COUNT(id) FROM orders WHERE created_at>=%s AND created_at<%s GROUP BY status ORDER BY status",
                           ("start","endExclusive"),("label","orders")),
    "query_sales_trend": ("""SELECT DATE_FORMAT(paid_at,'%%Y-%%m-%%d'),SUM(amount) FROM payments
        WHERE status='SUCCESS' AND paid_at>=%s AND paid_at<%s GROUP BY DATE_FORMAT(paid_at,'%%Y-%%m-%%d') ORDER BY 1""",
        ("start","endExclusive"),("label","sales")),
    "query_product_stock": ("SELECT product_id,available FROM inventory WHERE product_id=%s",("productId",),("product_id","available")),
    "query_category_sales": ("""SELECT p.category_id,SUM(i.unit_price*i.quantity) sales FROM order_items i JOIN products p ON p.id=i.product_id
        WHERE i.order_id IN(SELECT order_id FROM payments WHERE status='SUCCESS' AND paid_at>=%s AND paid_at<%s)
        GROUP BY p.category_id ORDER BY sales DESC,p.category_id LIMIT %s""",
        ("start","endExclusive","limit"),("category_id","sales")),
    "query_inventory_summary": ("""SELECT COUNT(*),SUM(available),SUM(available<=%s) FROM inventory WHERE product_id IN
        (SELECT id FROM products WHERE active=1)""",("threshold",),("active_products","available","low_stock")),
}


def measure(kind, case, expected, callback, verify):
    started = perf_counter()
    response = callback()
    facts = verify(response)
    print(json.dumps({"mode":"mock","suite":kind,"input":case,"expected":expected,"result":"PASS",
                      "elapsed_ms":round((perf_counter()-started)*1000),"actual":facts},ensure_ascii=False),flush=True)
    return response


def chat(client, question, expected=200, conversation=None):
    return client.call("/ai/chat","POST",{"question":question,"conversationId":conversation},expected=expected)


def agent_cases(admin, primary):
    first = None
    with primary.cursor() as cursor:
        for question, plan, status in AGENT:
            def verify(reply):
                if status != 200:
                    return {"status":status,"message":reply["message"]}
                tools = reply["tools"]
                assert [(tool["name"],tool["arguments"]) for tool in tools] == plan
                facts = []
                for tool in tools:
                    query, keys, fields = READ_SQL[tool["name"]]
                    cursor.execute(query,tuple(tool["arguments"][key] for key in keys))
                    expected = cursor.fetchall()
                    values = [tuple(row[key] for key in fields) for row in tool["rows"]]
                    # JSON decimals can be decoded as binary floats on the client.
                    assert json.dumps(expected,default=float) == json.dumps(values,default=float) or (
                        len(expected)==len(values) and all(
                            all(float(a)==float(b) if hasattr(a,"as_tuple") else a==b for a,b in zip(x,y))
                            for x,y in zip(expected,values))), (tool["name"],expected,values)
                    facts.append({"tool":tool["name"],"source":tool["source"],"rows":len(expected),
                                  "first":values[0] if values else []})
                if "编造" in question:
                    assert "无法与本次查询结果核对" in reply["answer"]
                return facts
            result = measure("agent",question,{"status":status,"tools":[name for name,_ in plan]},
                             partial(chat,admin,question,status),verify)
            if first is None:
                first = result["conversationId"]
    follow_up = chat(admin,AGENT[5][0],conversation=first)
    assert follow_up["conversationId"]==first and len(admin.call(f"/ai/conversations/{first}/messages"))==4
    return first


def rag_cases(admin, customer, primary):
    built = admin.call("/ai/knowledge/rebuild","POST",{"force":False})
    assert built["documents"]==4 and built["dimension"]==11 and built["embedded"]==10, built
    unchanged = admin.call("/ai/knowledge/rebuild","POST",{"force":False})
    assert unchanged["changed"]==unchanged["embedded"]==0
    with primary.cursor() as cursor:
        cursor.execute("UPDATE ai_knowledge_documents SET content_hash=REPEAT('0',64) WHERE id='account'")
    primary.commit()
    incremental = admin.call("/ai/knowledge/rebuild","POST",{"force":False})
    assert incremental["changed"]==1 and incremental["embedded"]==2
    indexed = admin.call("/ai/knowledge")
    assert len(indexed)==4 and all(row["compatible"] and row["dimension"]==11 for row in indexed)
    live = {chunk["id"]:chunk["body"] for doc in indexed for chunk in admin.call(f"/ai/knowledge/{doc['id']}")["chunks"]}
    assert len(live)==10
    print("PASS: 4 documents / 10 chunks, 11-dimension fixture vectors, unchanged rebuild and one-document incremental rebuild.",flush=True)
    for question,doc,title in RAG:
        def verify(reply):
            refs = reply["references"]
            if doc is None:
                assert not refs and "无法根据当前知识库回答" in reply["answer"]
                return {"refs":[],"refusal":True}
            assert len(refs)==1 and refs[0]["documentId"]==doc and refs[0]["title"]==title
            assert live[refs[0]["id"]]==refs[0]["excerpt"] and 0.34<=refs[0]["similarity"]<=1
            return {"id":refs[0]["id"],"document":doc,"heading":title,"score":refs[0]["similarity"]}
        measure("rag",question,{"document":doc,"heading":title},partial(chat,customer,question),verify)


def inventory_cases(merchant, primary):
    buyer, _, address = new_customer("ai_"+NONCE)
    profiles = [(10,0,"LOW_STOCK"),(0,0,"OUT_OF_STOCK"),(40,0,"NO_SALES"),(44,1,"SLOW_STOCK"),
                (50,4,"NORMAL"),(14,12,"HIGH_DEMAND_LOW_STOCK"),(12,12,"OUT_OF_STOCK"),
                (30,10,"NORMAL"),(3,1,"LOW_STOCK"),(80,2,"SLOW_STOCK")]
    today = datetime.now(timezone.utc).date().isoformat()

    def product_fixture(opening, qty, suffix):
        product, _ = create_product(merchant, "AI"+NONCE+str(suffix), opening, f"AI 合成样本 {suffix}")
        if qty:
            buyer.call(f"/cart/{product}","PUT",{"quantity":qty})
            order = buyer.call("/orders","POST",{"addressId":address})["ids"][0]
            buyer.call(f"/orders/{order}/pay","POST")
        return product

    for i,(opening,qty,risk) in enumerate(profiles,1):
        product = product_fixture(opening,qty,i)
        # Confirm an unpaid/cancelled order neither changes snapshot stock nor counts as sold.
        if i==5:
            buyer.call(f"/cart/{product}","PUT",{"quantity":3})
            order = buyer.call("/orders","POST",{"addressId":address})["ids"][0]
            buyer.call(f"/orders/{order}/cancel","POST")

        def verify(reply):
            data = reply["snapshot"]["products"][0]
            with primary.cursor() as cursor:
                cursor.execute("SELECT available FROM inventory WHERE product_id=%s",(product,))
                assert cursor.fetchone()[0]==data["available"]==opening-qty
                cursor.execute("""SELECT COALESCE(SUM(quantity),0) FROM order_items WHERE product_id=%s
                    AND order_id IN(SELECT order_id FROM payments WHERE status='SUCCESS' AND DATE(paid_at)=%s)""",(product,today))
                assert cursor.fetchone()[0]==data["sales_30"]==data["sales_7"]==qty
            assert data["risk"]==risk and (data["days_left"] is None)==(qty==0), data
            if qty:
                assert abs(data["days_left"] - (opening-qty)*30/qty) <= .051
            assert reply["advice"][0]["basis"]=={"available":opening-qty,"sales30":qty}
            return {"product_id":product,"risk":risk,"sales_30":qty,"available":data["available"],"days_left":data["days_left"],
                    "advice":reply["advice"][0]["action"]}
        measure("inventory",{"initialStock":opening,"paidUnits":qty},
                {"risk":risk,"available":opening-qty,"sales_30":qty},
                partial(merchant.call,"/ai/inventory","POST",{"asOf":today,"productId":product}),verify)
    merchant.call("/ai/inventory","POST",{"productId":2},expected=404)  # Same seed: product 2 belongs to merchant 2.
    invalid = product_fixture(1000,3,11)
    merchant.call("/ai/inventory","POST",{"productId":invalid},expected=502)


def cli():
    mode = sys.argv[1] if len(sys.argv)>1 else "mock"
    customer, merchant, admin, anonymous = [Client() for _ in range(4)]
    customer.login("demo_customer","CUSTOMER")
    merchant.login("demo_merchant","MERCHANT")
    admin.login("demo_admin","ADMIN")
    if mode=="disabled":
        assert admin.call("/ai/status")=={"chat":False,"embedding":False}
        admin.call("/ai/chat","POST",{"question":"库存"},expected=503)
        assert admin.call("/admin/system")["replica"]["ready"]
        print("PASS: no-key state and existing database/system API")
        return
    if mode=="mismatch":
        assert all(not row["compatible"] for row in admin.call("/ai/knowledge"))
        customer.call("/ai/chat","POST",{"question":RAG[0][0]},expected=409)
        result = admin.call("/ai/knowledge/rebuild","POST",{"force":False})
        assert result["changed"]==4 and result["embedded"]==10
        assert all(row["compatible"] for row in admin.call("/ai/knowledge"))
        print("PASS: changed embedding namespace blocked retrieval until all 10 vectors rebuilt.")
        return

    assert admin.call("/ai/status")=={"chat":True,"embedding":True}
    anonymous.call("/ai/status",expected=401)
    customer.call("/ai/knowledge",expected=403)
    merchant.call("/ai/knowledge/rebuild","POST",{"force":True},expected=403)
    admin.call("/ai/inventory","POST",{},expected=403)
    customer.call("/ai/chat","POST",{"question":"库存"},expected=403,csrf=False)
    merchant.call("/ai/chat","POST",{"question":"库存"},expected=403)
    admin.call("/ai/chat","POST",{"question":"x"*801},expected=400)
    primary = connect("MYSQL_PRIMARY_PORT")
    try:
        conversation = agent_cases(admin,primary)
        customer.call(f"/ai/conversations/{conversation}/messages",expected=404)
        chat(customer,"请求越权会话",expected=404,conversation=conversation)
        rag_cases(admin,customer,primary)
        inventory_cases(merchant,primary)
        with primary.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM ai_tool_call_logs WHERE status='BLOCKED'")
            assert cursor.fetchone()[0]>=5
            cursor.execute("SELECT COUNT(*) FROM ai_request_logs WHERE status='TIMEOUT'")
            assert cursor.fetchone()[0]>=1
            cursor.execute("SELECT COUNT(*) FROM ai_request_logs WHERE status='HTTP_503'")
            assert cursor.fetchone()[0]>=2
            cursor.execute("SELECT COUNT(*) FROM ai_request_logs WHERE total_tokens IS NOT NULL")
            assert cursor.fetchone()[0]>=50
        print("PASS: permission and CSRF denials, cross-owner conversation, blocked tool logs, timeout, retry, tokens, inventory ownership and invalid recommendation.",flush=True)
    finally:
        primary.close()


if __name__ == "__main__":
    cli()
