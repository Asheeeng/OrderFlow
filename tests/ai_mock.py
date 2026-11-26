"""Deterministic OpenAI-compatible HTTP fixture. No real model or provider is used."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import time
from ai_cases import AGENT, RAG

HEADINGS = list(dict.fromkeys(title for _, _, title in RAG if title))
AGENT_CASES = {question: tools for question,tools,_ in AGENT}
RAG_CASES = {question:title for question,_,title in RAG}
USAGE = {"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}


def vector(text):
    head = RAG_CASES.get(text)
    if head is None and "\n" in text:
        fields = text.split("\n")
        head = fields[1] if len(fields)>2 else None
    if text.startswith("模拟伪造引用"):
        head = "可取消范围"
    if text == "待支付订单能取消吗？":
        head = "可取消范围"
    i = HEADINGS.index(head) if head in HEADINGS else len(HEADINGS)
    return [int(j==i) for j in range(len(HEADINGS)+1)]


def chat(data):
    messages = data["messages"]
    system = messages[0]["content"]
    user = next(message["content"] for message in reversed(messages) if message["role"]=="user")
    if "公共客服 RAG" in system:
        payload = json.loads(user)
        source = payload["sources"][0]
        citation = 999999 if payload["question"].startswith("模拟伪造") else source["id"]
        return {"content":json.dumps({"answer":source["excerpt"],"citations":[citation]},ensure_ascii=False)}
    if "商家库存建议" in system:
        chosen = {"OUT_OF_STOCK":"MONITOR","HIGH_DEMAND_LOW_STOCK":"REPLENISH","LOW_STOCK":"MONITOR",
                  "NO_SALES":"CLEARANCE","SLOW_STOCK":"CLEARANCE","NORMAL":"HOLD"}
        rows = json.loads(user)
        return {"content":json.dumps({"actions":[
            {"productId":row["productId"],"action": "REPLENISH" if row["available"]==997 else chosen[row["risk"]]}
            for row in rows]})}
    if user == "模拟模型响应超时。":
        time.sleep(9)
    if messages[-1]["role"] == "tool":
        ids = {call["id"] for m in messages if m["role"]=="assistant" for call in m.get("tool_calls",[])}
        assert all(m["tool_call_id"] in ids for m in messages if m["role"]=="tool")
        if user=="模拟模型编造销量。":
            return {"content":"销量为 888888888888 元。"}
        return {"content":"已核对成功返回的工具结果，具体数值和范围见查询表。"}
    calls = AGENT_CASES.get(user, [("query_inventory_summary", {"threshold":10})])
    if not calls:
        return {"content":"没有查询结果"}
    assert isinstance(data.get("tools"),list)
    return {"content":None,"tool_calls":[
        {"id":f"fixture_{i}","type":"function","function":{"name":name,"arguments":json.dumps(arguments)}}
        for i,(name,arguments) in enumerate(calls,1)]}


class Fixture(BaseHTTPRequestHandler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if not self.headers.get("Authorization","").startswith("Bearer "):
            self.send_error(401)
            return
        if self.path == "/v1/embeddings":
            payload = {"data":[{"embedding":vector(request["input"])}],"usage":{"prompt_tokens":3,"total_tokens":3}}
        elif self.path == "/v1/chat/completions":
            if request["messages"][-1]["content"] == "模拟模型服务持续错误。":
                self.send_error(503)
                return
            payload = {"choices":[{"message":chat(request)}],"usage":USAGE}
        else:
            self.send_error(404)
            return
        raw = json.dumps(payload,ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(raw)))
        self.end_headers()
        try: self.wfile.write(raw)
        except BrokenPipeError: pass

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("",8765),Fixture).serve_forever()
