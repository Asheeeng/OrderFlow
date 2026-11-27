package cn.orderflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class AiStore {
    final JdbcTemplate db;
    private final ObjectMapper json;
    private final AiConfig config;

    AiStore(JdbcTemplate db, ObjectMapper json, AiConfig config) {
        this.db=db; this.json=json; this.config=config;
    }

    long conversation(Actor actor, String mode, Long id) {
        if (id == null) return Db.insert(db, "INSERT INTO ai_conversations(user_id,mode) VALUES(?,?)", actor.id(), mode);
        Db.one(db, "SELECT id FROM ai_conversations WHERE id=? AND user_id=? AND mode=?", "会话", id, actor.id(), mode);
        return id;
    }

    List<Map<String,Object>> history(long id) {
        var recent = db.queryForList("""
            SELECT speaker AS role,body AS content FROM ai_messages WHERE conversation_id=? ORDER BY id DESC LIMIT 6
            """, id);
        Collections.reverse(recent);
        return recent;
    }

    void remember(long id, String speaker, String text) {
        db.update("INSERT INTO ai_messages(conversation_id,speaker,body) VALUES(?,?,?)", id, speaker, text);
        db.update("UPDATE ai_conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }

    void tool(long conversation, String name, Map<String,Object> args, String status, long elapsed) {
        db.update("INSERT INTO ai_tool_call_logs(conversation_id,tool,arguments,status,elapsed_ms) VALUES(?,?,?,?,?)",
                conversation, name, encode(args), status, elapsed);
    }

    // The application is a single process. Persist the budget on the primary so restarts cannot reset it.
    synchronized long reserve(long user, String operation, String model) {
        long day = db.queryForObject("SELECT COUNT(*) FROM ai_request_logs WHERE created_at>=UTC_DATE()", Long.class);
        long minute = db.queryForObject("""
            SELECT COUNT(*) FROM ai_request_logs WHERE user_id=? AND created_at>=UTC_TIMESTAMP()-INTERVAL 60 SECOND
            """, Long.class, user);
        if (day >= config.current().settings().dailyLimit() || minute >= config.current().settings().minuteLimit()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI 调用额度已达到上限，稍后再试");
        }
        return Db.insert(db, "INSERT INTO ai_request_logs(user_id,operation,model,status) VALUES(?,?,?,'STARTED')",
                user, operation, model);
    }

    void finish(long id, String status, long elapsed, JsonNode usage) {
        db.update("""
            UPDATE ai_request_logs SET status=?,elapsed_ms=?,input_tokens=?,output_tokens=?,total_tokens=? WHERE id=?
            """, status, elapsed, tokens(usage, "prompt_tokens"), tokens(usage, "completion_tokens"), tokens(usage, "total_tokens"), id);
    }

    private Integer tokens(JsonNode usage, String name) {
        JsonNode token = usage.path(name);
        return token.isIntegralNumber() && token.canConvertToInt() && token.intValue() >= 0 ? token.intValue() : null;
    }

    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("AI JSON serialization failed"); }
    }

    JsonNode parse(String value) {
        try { return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Invalid AI JSON"); }
    }

    static long millis(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
