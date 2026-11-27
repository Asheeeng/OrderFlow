"""Verify live RAG and merchant inventory on 5473 without reading model keys."""
from urllib.request import build_opener,HTTPCookieProcessor
from http.cookiejar import CookieJar
import json
from http_check import Client
base="http://127.0.0.1:5473/api"
def client(role):
 c=Client(); c.opener=build_opener(HTTPCookieProcessor(CookieJar())); c.login("demo_"+role,role.upper()); return c
customer=client("customer")
rag=customer.call("/ai/chat","POST",{"question":"已付款订单可以取消或退款吗？"})
print(json.dumps({"module":"RAG","source":"live config saved in 5473","answerSource":rag.get("answerSource"),
 "answer":rag.get("answer"),"references":[{"document":r.get("document"),"title":r.get("title"),
 "similarity":r.get("similarity"),"excerpt":r.get("excerpt")} for r in rag.get("references",[])]},ensure_ascii=False))
assert rag.get("references") and any(r.get("title")=="可取消范围" for r in rag["references"])
merchant=client("merchant")
report=merchant.call("/ai/inventory","POST",{"productId":1})
snap=report["snapshot"]["products"][0]
print(json.dumps({"module":"inventory","report":report["id"],"snapshot":{"asOf":report["snapshot"]["asOf"],
 "product":snap,"source":report["snapshot"]["source"]},"advice":report["advice"]},ensure_ascii=False))
assert report["advice"] and snap["product_id"]==1
