"""Opt-in live provider probes. Run against your configured, isolated deployment only."""
from argparse import ArgumentParser
import json
from time import perf_counter

from http_check import Client, connect

def main():
    parser=ArgumentParser()
    parser.add_argument("--live-provider",action="store_true",required=True,
                        help="the selected deployment uses live billing credentials, not the Mock overlay")
    parser.parse_args()
    admin,customer,merchant=Client(),Client(),Client()
    admin.login("demo_admin","ADMIN")
    customer.login("demo_customer","CUSTOMER")
    merchant.login("demo_merchant","MERCHANT")
    assert admin.call("/ai/status")=={"chat":True,"embedding":True}
    probes = [
        ("index", lambda: admin.call("/ai/knowledge/rebuild","POST",{"force":False}),
         lambda r: r["documents"] > 0 and r["dimension"] > 0),
        ("agent", lambda: admin.call("/ai/chat","POST",{"question":"比较 UTC 2026-11-10 至 2026-11-17（不含）的成功支付额与前一周，每个区间调用一次 query_sales。"}),
         lambda r: len([t for t in r["tools"] if t["name"]=="query_sales"]) >= 2),
        ("rag", lambda: customer.call("/ai/chat","POST",{"question":"已付款订单可以取消或退款吗？"}),
         lambda r: any(item["title"]=="可取消范围" for item in r["references"])),
        ("inventory", lambda: merchant.call("/ai/inventory","POST",{"productId":1}),
         lambda r: bool(r["advice"] and r["snapshot"]["products"])),
    ]
    for name,run,check in probes:
        start=perf_counter()
        result=run()
        assert check(result), name
        safe={key:result[key] for key in ("documents","embedded","dimension","model","id") if key in result}
        if name=="agent": safe["tools"]=[{"name":t["name"],"arguments":t["arguments"],"rows":t["rows"]} for t in result["tools"]]
        if name=="rag": safe["references"]=[{"document":r["document"],"id":r["id"]} for r in result["references"]]
        print(json.dumps({"probe":name,"elapsed_ms":round((perf_counter()-start)*1000),"result":safe},ensure_ascii=False))
    db=connect("MYSQL_PRIMARY_PORT")
    try:
        with db.cursor() as cursor:
            cursor.execute("""SELECT operation,model,status,elapsed_ms,total_tokens
                FROM ai_request_logs ORDER BY id DESC LIMIT 20""")
            for row in cursor.fetchall():
                if row[1].startswith("fixture-"): raise AssertionError("Fixture model in candidate evidence")
                print(json.dumps({"modelAudit":row},ensure_ascii=False))
    finally:
        db.close()

if __name__=="__main__":
    main()
