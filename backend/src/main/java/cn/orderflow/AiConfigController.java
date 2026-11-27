package cn.orderflow;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/config")
class AiConfigController {
    record Test(AiConfig.Draft draft,String kind) {}
    record Clear(long revision,String key) {}
    record Probe(String kind,boolean passed,String model,long elapsedMs,Integer dimension,String detail) {}
    private final Auth auth;
    private final AiConfig config;
    private final AiStore store;
    private final AiProvider provider;
    private final AiWork work;
    private final AiKnowledge knowledge;

    AiConfigController(Auth auth,AiConfig config,AiStore store,AiProvider provider,AiWork work,AiKnowledge knowledge) {
        this.auth=auth;this.config=config;this.store=store;this.provider=provider;this.work=work;this.knowledge=knowledge;
    }

    @GetMapping
    Map<String,Object> get(HttpServletRequest request) {
        auth.require(request,"ADMIN");
        return config.view(config.current());
    }

    @GetMapping("/usage")
    Map<String,Object> usage(HttpServletRequest request) {
        auth.require(request,"ADMIN");
        var sql=store.db;
        var summary=sql.queryForMap("""
            SELECT COUNT(*) calls,COALESCE(SUM(created_at>=UTC_DATE()),0) today,
              SUM(input_tokens) input_tokens,SUM(output_tokens) output_tokens,SUM(total_tokens) total_tokens,
              COUNT(input_tokens) input_samples,COUNT(output_tokens) output_samples,
              SUM(status='OK') successes,SUM(status<>'STARTED') finished,
              ROUND(AVG(CASE WHEN status<>'STARTED' THEN elapsed_ms END),1) average_ms
            FROM ai_request_logs
            """);
        return Map.of("summary",summary,"functions",distribution("operation"),"models",distribution("model"),
                "recent",sql.queryForList("""
                    SELECT operation,model,status,elapsed_ms,input_tokens,output_tokens,total_tokens,created_at
                    FROM ai_request_logs ORDER BY id DESC LIMIT 30
                    """));
    }

    private List<Map<String,Object>> distribution(String field) {
        return store.db.queryForList("SELECT "+field+" label,COUNT(*) calls,SUM(input_tokens) input_tokens,"+
                "SUM(output_tokens) output_tokens FROM ai_request_logs GROUP BY "+field+
                " ORDER BY calls DESC,label LIMIT 30");
    }

    @PostMapping("/test")
    List<Probe> test(HttpServletRequest request,@RequestBody Test body) {
        var actor=auth.require(request,"ADMIN");
        List<String> kinds=List.of("chat","tool","embedding","knowledge");
        if(body.kind()==null || (!kinds.contains(body.kind()) && !body.kind().equals("all"))) throw AiConfig.invalid();
        var candidate=config.draft(body.draft(),false);
        return work.run(actor,()->config.use(candidate,()->(body.kind().equals("all")?kinds:List.of(body.kind()))
                .stream().map(kind->probe(actor.id(),kind)).toList()));
    }

    @PutMapping
    Map<String,Object> save(HttpServletRequest request,@RequestBody AiConfig.Draft draft) {
        var actor=auth.require(request,"ADMIN");
        var candidate=config.draft(draft,true);
        return work.run(actor,()->{
            AiConfig.Snapshot checked=candidate;
            if(candidate.settings().enabled()) {
                // Probe changed credentials/models before publishing the revision. Unchanged switches still
                // get an embedding measurement so an in-place dimension change is captured on save.
                var proofs=config.use(candidate,()->List.of(probe(actor.id(),"chat"),probe(actor.id(),"tool"),
                        probe(actor.id(),"embedding")));
                if(proofs.stream().anyMatch(proof->!proof.passed()))
                    throw new ApiException(HttpStatus.BAD_GATEWAY,"配置未保存。请查看连接测试中的失败项目");
                checked=config.withDimension(candidate,proofs.get(2).dimension());
            }
            config.persist(checked);
            return config.view(new AiConfig.Snapshot(checked.revision()+1,checked.settings(),checked.chatKey(),checked.embeddingKey(),false));
        });
    }

    @PostMapping("/clear")
    Map<String,Object> clear(HttpServletRequest request,@RequestBody Clear body) {
        var actor=auth.require(request,"ADMIN");
        var old=config.current();
        if(body.revision()!=old.revision() || body.key()==null || !Set.of("chat","embedding").contains(body.key())
                || (old.settings().shared() && body.key().equals("embedding"))) throw AiConfig.invalid();
        var s=old.settings();
        var off=new AiConfig.Settings(false,s.provider(),s.chatUrl(),s.chatModel(),s.shared(),s.embeddingUrl(),
                s.embeddingModel(),s.embeddingVersion(),s.dimension(),s.timeoutSeconds(),s.maxTokens(),s.dailyLimit(),s.minuteLimit());
        var next=new AiConfig.Snapshot(old.revision(),off,body.key().equals("chat")?"":old.chatKey(),
                body.key().equals("embedding")?"":old.embeddingKey(),false);
        return work.run(actor,()->{
            config.persist(next);
            return config.view(new AiConfig.Snapshot(next.revision()+1,next.settings(),next.chatKey(),next.embeddingKey(),false));
        });
    }

    private Probe probe(long user,String kind) {
        var snapshot=config.current();
        var s=snapshot.settings();
        String model=kind.equals("embedding")||kind.equals("knowledge")?s.embeddingModel():s.chatModel();
        long start=System.nanoTime();
        // A draft may be tested with its switch off without changing running traffic.
        var testing=new AiConfig.Settings(true,s.provider(),s.chatUrl(),s.chatModel(),s.shared(),s.embeddingUrl(),
                s.embeddingModel(),s.embeddingVersion(),s.dimension(),s.timeoutSeconds(),s.maxTokens(),s.dailyLimit(),s.minuteLimit());
        return config.use(new AiConfig.Snapshot(snapshot.revision(),testing,snapshot.chatKey(),snapshot.embeddingKey(),false),()->{
            try {
                var dimension=switch(kind) {
                    case "embedding" -> provider.embed(user,"CONFIG_EMBEDDING","OrderFlow connection check").length;
                    case "tool" -> { toolProbe(user); yield null; }
                    case "chat" -> {
                        var reply=provider.chat(user,"CONFIG_CHAT",List.of(Map.of("role","user","content","Reply with ORDERFLOW_OK.")),List.of());
                        if(!reply.path("content").asText().contains("ORDERFLOW_OK")) throw AiProvider.invalidReply();
                        yield null;
                    }
                    case "knowledge" -> {
                        int actual=provider.embed(user,"CONFIG_EMBEDDING","OrderFlow knowledge space check").length;
                        var measured=config.withDimension(config.current(),actual);
                        var docs=config.use(measured,knowledge::documents);
                        if(docs.isEmpty() || docs.stream().anyMatch(doc->!(Boolean.TRUE.equals(doc.get("compatible")) || (doc.get("compatible") instanceof Number n && n.intValue()==1))
                                || ((Number)doc.get("dimension")).intValue()!=actual || ((Number)doc.get("chunks")).intValue()==0)) {
                            yield -actual;
                        }
                        yield actual;
                    }
                    default -> throw AiConfig.invalid();
                };
                if(dimension!=null && dimension<0) return new Probe(kind,false,model,AiStore.millis(start),-dimension,"当前向量空间需要重建");
                return new Probe(kind,true,model,AiStore.millis(start),dimension,"通过");
            } catch(ApiException error) {return new Probe(kind,false,model,AiStore.millis(start),null,error.getMessage());}
            catch(IllegalArgumentException error) {return new Probe(kind,false,model,AiStore.millis(start),null,"模型返回内容无法验证");}
        });
    }

    private void toolProbe(long user) {
        String nonce=UUID.randomUUID().toString();
        var tool=Map.of("type","function","function",Map.of("name","verify_echo","description","Verify an echo challenge",
                "parameters",Map.of("type","object","properties",Map.of("nonce",Map.of("type","string")),
                        "required",List.of("nonce"),"additionalProperties",false)));
        var messages=new ArrayList<Object>();
        messages.add(Map.of("role","user","content","Call verify_echo with nonce "+nonce+
                ". After receiving the result, reply with its exact receipt."));
        var call=provider.chat(user,"CONFIG_TOOL",messages,List.of(tool)).path("tool_calls");
        if(!call.isArray() || call.size()!=1) throw AiProvider.invalidReply();
        var item=call.get(0);
        String id=item.path("id").asText();
        var f=item.path("function");
        var args=store.parse(f.path("arguments").asText());
        if(!id.matches("[a-zA-Z0-9_-]{1,160}") || !"function".equals(item.path("type").asText())
                || !"verify_echo".equals(f.path("name").asText()) || args==null || !args.isObject() ||
                args.size()!=1 || !nonce.equals(args.path("nonce").asText())) throw AiProvider.invalidReply();
        var assistant=new LinkedHashMap<String,Object>();
        assistant.put("role","assistant"); assistant.put("content",null); assistant.put("tool_calls",call);
        String receipt="ORDERFLOW_VERIFIED_"+UUID.randomUUID();
        messages.add(assistant);
        messages.add(Map.of("role","tool","tool_call_id",id,"content",receipt));
        if(!provider.chat(user,"CONFIG_TOOL",messages,List.of(tool)).path("content").asText().contains(receipt))
            throw AiProvider.invalidReply();
    }
}
