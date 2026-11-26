package cn.orderflow;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
class AiController {
    record Chat(@NotBlank @Size(max=800) String question, @Positive Long conversationId) {}
    record Query(@NotBlank @Size(max=800) String question) {}
    record Index(boolean force) {}
    record Inventory(LocalDate asOf, @Positive Long productId) {}

    private final Auth auth;
    private final AiStore store;
    private final AiWork work;
    private final AiProvider provider;
    private final AiAgent agent;
    private final AiKnowledge knowledge;
    private final AiInventory inventory;

    AiController(Auth auth, AiStore store, AiWork work, AiProvider provider, AiAgent agent,
                 AiKnowledge knowledge, AiInventory inventory) {
        this.auth=auth; this.store=store; this.work=work; this.provider=provider;
        this.agent=agent; this.knowledge=knowledge; this.inventory=inventory;
    }

    @GetMapping("/status")
    Map<String,Boolean> status(HttpServletRequest request) {
        auth.require(request);
        return Map.of("chat",provider.chatReady(),"embedding",provider.embeddingReady());
    }

    @PostMapping("/chat")
    Map<String,Object> chat(HttpServletRequest request, @Valid @RequestBody Chat body) {
        var actor = auth.require(request,"ADMIN","CUSTOMER");
        boolean admin = actor.role().equals("ADMIN");
        if (!provider.chatReady() || (!admin && !provider.embeddingReady())) throw AiProvider.disabled();
        return work.run(actor, () -> {
            long id = store.conversation(actor, admin ? "AGENT" : "RAG",body.conversationId());
            String input = AiWork.safePrompt(body.question());
            var history = store.history(id);
            store.remember(id,"user",input);
            if (admin) {
                var result = agent.answer(actor,id,input,history);
                store.remember(id,"assistant",result.answer());
                return Map.of("conversationId",id,"answer",result.answer(),"tools",result.tools());
            }
            var result = knowledge.answer(actor,input,history);
            store.remember(id,"assistant",result.answer());
            return Map.of("conversationId",id,"answer",result.answer(),"references",result.references());
        });
    }

    @GetMapping("/conversations")
    List<Map<String,Object>> conversations(HttpServletRequest request) {
        var actor = auth.require(request,"ADMIN","CUSTOMER");
        return store.db.queryForList("""
            SELECT id,mode,updated_at FROM ai_conversations WHERE user_id=? ORDER BY updated_at DESC,id DESC LIMIT 20
            """,actor.id());
    }

    @GetMapping("/conversations/{id}/messages")
    List<Map<String,Object>> history(HttpServletRequest request, @PathVariable @Positive long id) {
        var actor = auth.require(request,"ADMIN","CUSTOMER");
        store.conversation(actor,actor.role().equals("ADMIN")?"AGENT":"RAG",id);
        return store.history(id);
    }

    @GetMapping("/knowledge")
    List<Map<String,Object>> documents(HttpServletRequest request) {
        auth.require(request,"ADMIN");
        return knowledge.documents();
    }

    @GetMapping("/knowledge/{id}")
    Map<String,Object> document(HttpServletRequest request,@PathVariable @Pattern(regexp="[a-z-]{1,48}") String id) {
        auth.require(request,"ADMIN");
        return knowledge.document(id);
    }

    @PostMapping("/knowledge/rebuild")
    Map<String,Object> rebuild(HttpServletRequest request,@RequestBody Index body) {
        var actor = auth.require(request,"ADMIN");
        return work.run(actor, () -> knowledge.rebuild(actor.id(),body.force()));
    }

    @PostMapping("/knowledge/search")
    List<AiKnowledge.Reference> search(HttpServletRequest request,@Valid @RequestBody Query body) {
        var actor = auth.require(request,"ADMIN");
        return work.run(actor, () -> knowledge.search(actor.id(),AiWork.safePrompt(body.question())));
    }

    @PostMapping("/inventory")
    Map<String,Object> inventory(HttpServletRequest request,@Valid @RequestBody Inventory body) {
        var actor = auth.require(request,"MERCHANT");
        return work.run(actor, () -> inventory.report(actor,body.asOf(),body.productId()));
    }
}
