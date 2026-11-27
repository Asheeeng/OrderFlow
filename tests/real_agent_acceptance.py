"""Six real-model requests through the already configured 5473 service."""
from datetime import datetime,timezone,timedelta
import json,os,re
from urllib.request import build_opener
from urllib.request import HTTPCookieProcessor
from http.cookiejar import CookieJar
from http_check import Client

BASE="http://127.0.0.1:5473/api"
client=Client();client.opener=build_opener(HTTPCookieProcessor(CookieJar()))
client.login("demo_admin","ADMIN")
status=client.call("/ai/config")
assert status["chatKeyConfigured"] and status["settings"]["enabled"]
today=datetime.now(timezone.utc).date()
end=today
start=end-timedelta(days=30)
previous=start-timedelta(days=30)
last30={"start":start.isoformat(),"endExclusive":end.isoformat()}
queries=[
 "最近30天的销售额是多少？",
 "查看最近一个月订单状态分布，分别说清每种状态的订单量。",
 "找出最近30天销量最高的5种商品。",
 "最近30天畅销商品及库存情况如何？请标出畅销但库存偏低的商品。",
 "比较最近30天和前30天销售额的变化，并说明增减。",
 "分析最近30天销售趋势，并结合畅销低库存工具结果给出运营建议。",
]
cases=[
 {"query_sales":last30},
 {"query_order_status":last30},
 {"query_top_products":last30},
 {"query_top_products":last30,"query_low_stock":{"threshold":10,"limit":10}},
 [{"query_sales":{"start":previous.isoformat(),"endExclusive":start.isoformat()}},{"query_sales":last30}],
 {"query_sales_trend":last30,"query_top_products":last30,"query_low_stock":{"threshold":10,"limit":10}},
]
output={"testedAtUtc":datetime.now(timezone.utc).isoformat(),"endpoint":BASE,
 "savedProvider":status["settings"]["provider"],"chatModel":status["settings"]["chatModel"],
 "embeddingModel":status["settings"]["embeddingModel"],"credentialsConfigured":True,"queries":[]}
for n,(question,expected) in enumerate(zip(queries,cases),1):
 try:
  reply=client.call("/ai/chat","POST",{"question":question})
  assert reply.get("tools"),"No SQL tool result"
  tools=reply["tools"]; names=[item["name"] for item in tools]
  if isinstance(expected,list):
   assert sum(item["name"]=="query_sales" for item in tools)>=2,names
  else:
   assert set(expected).issubset(names),(expected,names)
  record={"index":n,"question":question,"actualToolArguments":[{"name":item["name"],"arguments":item["arguments"]} for item in tools],
    "source":reply["answerSource"],"answer":reply["answer"],"tools":tools}
  for key in ("modelDraft","validationReason"):
   if reply.get(key):
    record[key]=re.sub(r"(?i)(sk-[A-Za-z0-9_-]{8,})|([A-Za-z0-9.!#$%&'*+/=?^_{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})","[REDACTED]",reply[key])
  output["queries"].append(record)
 except Exception as error:
  output["queries"].append({"index":n,"question":question,"failureType":type(error).__name__,"failure":str(error)[:500]})
with open(os.environ.get("OF_REAL_AI_OUTPUT","evidence/v1.1.0/real-agent-fixed.json"),"w") as file:
 json.dump(output,file,ensure_ascii=False,indent=2)
print(json.dumps({"model":output["chatModel"],"indices":len(output["queries"]),
 "successful":sum("answer" in row for row in output["queries"]),
 "fallback":sum(row.get("source")=="system" for row in output["queries"])},ensure_ascii=False))
for row in output["queries"]: print(json.dumps(row,ensure_ascii=False))
if any("failure" in row for row in output["queries"]): raise SystemExit(1)
