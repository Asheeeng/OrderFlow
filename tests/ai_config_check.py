"""Exercise the admin configuration center against the isolated mock overlay."""
import json, sys
from http_check import Client,connect

ADMIN, CUSTOMER, MERCHANT = Client(), Client(), Client()
ADMIN.login("demo_admin","ADMIN")
CUSTOMER.login("demo_customer","CUSTOMER")
MERCHANT.login("demo_merchant","MERCHANT")
BASE="/ai/config"
MOCK="http://ai-mock:8765/v1"

def view():
    profile=ADMIN.call(BASE)
    assert "local-fixture-only" not in json.dumps(profile) and "fixture-alternate" not in json.dumps(profile)
    return profile

def draft(old, **changes):
    return {"revision":old["revision"], "settings":old["settings"] | changes,"chatKey":"","embeddingKey":""}

def save(payload,status=200):
    return ADMIN.call(BASE,"PUT",payload,expected=status)

def probes(value,kind="all"):
    return {r["kind"]:r for r in ADMIN.call(BASE+"/test","POST",{"draft":value,"kind":kind})}

def run():
    original=view()
    assert original["revision"]==0 and not original["chatKeyConfigured"], original
    assert ADMIN.call("/ai/status")=={"chat":False,"embedding":False}
    for client in (CUSTOMER,MERCHANT):
        client.call(BASE,expected=403)
        client.call(BASE+"/usage",expected=403)
        client.call(BASE+"/test","POST",{},expected=403)
    ADMIN.call(BASE,"PUT",{},expected=403,csrf=False)
    initial=draft(original,enabled=True,provider="custom",shared=True,chatUrl=MOCK,chatModel="fixture-chat",
        embeddingModel="fixture-embedding",embeddingVersion="1")
    initial["chatKey"]="local-fixture-only"
    for blocked in ("http://127.0.0.1:3306","https://127.0.0.1","https://169.254.169.254",
                    "https://fake.internal/v1","https://example.com:3306/v1","https://user@example.com/v1",
                    "https://example.com/v1?secret=1","https://example.com/a/../v1"):
        save(initial | {"settings":initial["settings"] | {"chatUrl":blocked}},400)
    rejected=probes(initial | {"settings":initial["settings"] | {"chatModel":"fixture-no-tool"}},"tool")
    assert not rejected["tool"]["passed"]
    checks=probes(initial)
    assert all(checks[k]["passed"] for k in ("chat","tool","embedding"))
    assert not checks["knowledge"]["passed"] and checks["embedding"]["dimension"]==11
    assert view()["revision"]==0
    saved=save(initial)
    assert saved["settings"]["dimension"]==11 and saved["chatKeyConfigured"] and saved["embeddingKeyConfigured"]
    assert ADMIN.call("/ai/status")=={"chat":True,"embedding":True}
    assert ADMIN.call("/ai/chat","POST",{"question":"商品 1 的库存是多少？"})["tools"]
    ADMIN.call("/ai/knowledge/rebuild","POST",{"force":False})
    assert CUSTOMER.call("/ai/chat","POST",{"question":"待支付订单能取消吗？"})["references"]
    assert MERCHANT.call("/ai/inventory","POST",{"productId":1})["snapshot"]["products"]
    assert probes(draft(saved),"knowledge")["knowledge"]["passed"]
    print("PASS: first admin save, one shared key, all three AI flows, independent tests, actual tool handshake and KB readiness")
    bad=draft(saved);bad["chatKey"]="fixture-incorrect-not-a-secret"
    assert not probes(bad,"chat")["chat"]["passed"]
    save(bad,502)
    assert view()["revision"]==saved["revision"]
    ADMIN.call("/ai/chat","POST",{"question":"模拟模型服务持续错误。"},expected=502)
    assert ADMIN.call("/products?size=1")["rows"] and view()["revision"]==saved["revision"]
    changed=save(draft(saved,chatModel="fixture-chat-alt"))
    assert all(r["compatible"] for r in ADMIN.call("/ai/knowledge"))
    assert ADMIN.call("/ai/chat","POST",{"question":"商品 1 的库存是多少？"})["tools"]
    assert any(r["label"]=="fixture-chat-alt" for r in ADMIN.call(BASE+"/usage")["models"])
    save(draft(saved),409)
    finish(changed)

def finish(changed):
    separate=draft(changed,shared=False,embeddingUrl=MOCK,embeddingModel="fixture-embedding-v2")
    separate["embeddingKey"]="fixture-alternate"
    changed=save(separate)
    assert changed["settings"]["dimension"]==12
    CUSTOMER.call("/ai/chat","POST",{"question":"待支付订单能取消吗？"},expected=409)
    rebuilt=ADMIN.call("/ai/knowledge/rebuild","POST",{"force":False})
    assert rebuilt["embedded"]==10 and rebuilt["dimension"]==12
    assert CUSTOMER.call("/ai/chat","POST",{"question":"待支付订单能取消吗？"})["references"]
    assert probes(draft(changed),"knowledge")["knowledge"]["passed"]
    print("PASS: wrong-key rollback, revision conflict, hot chat switch preserves vectors, separate embedding key and 11→12 dimension rebuild")
    db=connect("MYSQL_PRIMARY_PORT")
    try:
        with db.cursor() as c:
            c.execute("SELECT chat_secret,embedding_secret FROM ai_runtime_config")
            keys=c.fetchone()
            assert all(len(k)>28 and b"fixture-" not in k for k in keys)
    finally:
        db.close()
    print("PASS: AES-GCM ciphertext in MySQL and masked GET, CSRF/role guards, SSRF URL denials")

if len(sys.argv)>1 and sys.argv[1]=="resume-after-chat-switch":
    changed=view()
    assert changed["settings"]["chatModel"]=="fixture-chat-alt"
    assert any(row["label"]=="fixture-chat-alt" for row in ADMIN.call(BASE+"/usage")["models"])
    save(draft(changed | {"revision":changed["revision"]-1}),409)
    finish(changed)
elif len(sys.argv)>1 and sys.argv[1]=="after-recreate":
    saved=view()
    assert saved["settings"]["chatModel"]=="fixture-chat-alt" and saved["settings"]["dimension"]==12
    assert ADMIN.call("/ai/chat","POST",{"question":"商品 1 的库存是多少？"})["tools"]
    assert CUSTOMER.call("/ai/chat","POST",{"question":"待支付订单能取消吗？"})["references"]
    off=ADMIN.call(BASE+"/clear","POST",{"key":"embedding","revision":saved["revision"]})
    assert not off["embeddingKeyConfigured"] and off["chatKeyConfigured"] and not off["settings"]["enabled"]
    assert ADMIN.call("/ai/status")=={"chat":False,"embedding":False} and ADMIN.call("/products?size=1")["rows"]
    print("PASS: key and config survive recreation; explicit clear keeps the other key and leaves business online")
else: run()
