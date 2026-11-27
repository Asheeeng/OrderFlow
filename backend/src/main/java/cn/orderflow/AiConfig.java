package cn.orderflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class AiConfig extends OncePerRequestFilter {
    record Settings(boolean enabled,String provider,String chatUrl,String chatModel,boolean shared,
                    String embeddingUrl,String embeddingModel,String embeddingVersion,int dimension,
                    int timeoutSeconds,int maxTokens,int dailyLimit,int minuteLimit) {}
    record Snapshot(long revision,Settings settings,String chatKey,String embeddingKey,boolean legacy) {
        String embeddingSecret() { return settings.shared ? chatKey : embeddingKey; }
    }
    record Draft(long revision,Settings settings,String chatKey,String embeddingKey,boolean clearChat,boolean clearEmbedding) {}

    private final ThreadLocal<Snapshot> pinned = new ThreadLocal<>();
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final ObjectMapper json;
    private final Environment env;
    private final SecureRandom random = new SecureRandom();

    AiConfig(org.springframework.jdbc.core.JdbcTemplate db,ObjectMapper json,Environment env) {
        this.db=db; this.json=json; this.env=env;
    }

    String env(String key) { return env.getProperty(key,"").strip(); }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/ai/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        try { chain.doFilter(request,response); } finally { pinned.remove(); }
    }

    Snapshot current() {
        Snapshot value=pinned.get();
        if(value==null) { value=load(); pinned.set(value); }
        return value;
    }

    <T> T use(Snapshot value,Supplier<T> action) {
        Snapshot old=pinned.get();
        pinned.set(value);
        try { return action.get(); }
        finally { if(old==null) pinned.remove(); else pinned.set(old); }
    }

    private Snapshot load() {
        var rows=db.queryForList("SELECT revision,settings,chat_secret,embedding_secret FROM ai_runtime_config WHERE id=1");
        if(rows.isEmpty()) {
            String chat=env("AI_BASE_URL");
            String embedding=env("AI_EMBEDDING_BASE_URL");
            String chatKey=env("AI_API_KEY");
            String embeddingKey=env("AI_EMBEDDING_API_KEY");
            var settings=new Settings("true".equalsIgnoreCase(env("AI_ENABLED")),"custom",chat,env("AI_CHAT_MODEL"),
                    !chatKey.isEmpty() && chatKey.equals(embeddingKey) && !chat.isEmpty() && chat.equals(embedding),
                    embedding,env("AI_EMBEDDING_MODEL"),env.getProperty("AI_EMBEDDING_VERSION","1"),0,8,650,
                    limit("AI_DAILY_CALL_LIMIT",300,2000),limit("AI_USER_MINUTE_LIMIT",20,120));
            return new Snapshot(0,settings,chatKey,embeddingKey,true);
        }
        var row=rows.get(0);
        try {
            var settings=json.readValue(row.get("settings").toString(),Settings.class);
            return new Snapshot(((Number)row.get("revision")).longValue(),settings,
                    decrypt((byte[])row.get("chat_secret"),"chat"),decrypt((byte[])row.get("embedding_secret"),"embedding"),false);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException error) { throw unavailable(); }
    }

    private int limit(String name,int fallback,int max) {
        try {return Math.max(1,Math.min(max,Integer.parseInt(env(name))));}
        catch(NumberFormatException e) {return fallback;}
    }

    Snapshot draft(Draft draft,boolean complete) {
        if(draft==null) throw invalid();
        Snapshot old=current();
        if(draft.revision!=old.revision) throw ApiException.conflict("配置已更新，请刷新后重试");
        Settings s=draft.settings;
        if(s==null || !Set.of("alibaba","siliconflow","custom").contains(s.provider==null?"":s.provider)
                || s.dimension<0 || s.dimension>4096 || s.timeoutSeconds<3 || s.timeoutSeconds>15
                || s.maxTokens<64 || s.maxTokens>2000 || s.dailyLimit<1 || s.dailyLimit>2000
                || s.minuteLimit<1 || s.minuteLimit>120) throw invalid();
        String chat=endpoint(s.chatUrl), embedding=s.shared?chat:endpoint(s.embeddingUrl);
        Settings normalized=new Settings(s.enabled,s.provider,chat,model(s.chatModel,160),s.shared,embedding,
                model(s.embeddingModel,160),model(s.embeddingVersion,64),s.dimension,s.timeoutSeconds,s.maxTokens,s.dailyLimit,s.minuteLimit);
        String ck=secret(draft.chatKey,draft.clearChat,old.chatKey,old.settings.chatUrl,chat);
        String ek=s.shared?"":secret(draft.embeddingKey,draft.clearEmbedding,
                old.settings.shared?"":old.embeddingKey,old.settings.embeddingUrl,embedding);
        if(complete && s.enabled && (chat.isBlank() || embedding.isBlank() || normalized.chatModel.isBlank()
                || normalized.embeddingModel.isBlank() || ck.isBlank() || (!s.shared && ek.isBlank()))) throw invalid();
        return new Snapshot(old.revision,normalized,ck,ek,false);
    }

    Snapshot withDimension(Snapshot source,int dimension) {
        var s=source.settings;
        return new Snapshot(source.revision,new Settings(s.enabled,s.provider,s.chatUrl,s.chatModel,s.shared,
                s.embeddingUrl,s.embeddingModel,s.embeddingVersion,dimension,s.timeoutSeconds,s.maxTokens,s.dailyLimit,s.minuteLimit),
                source.chatKey,source.embeddingKey,false);
    }

    Map<String,Object> view(Snapshot value) {
        return Map.of("revision",value.revision,"settings",value.settings,"chatKeyConfigured",!value.chatKey.isEmpty(),
                "embeddingKeyConfigured",!value.embeddingSecret().isEmpty(),"source",value.legacy?"environment":"database",
                "keyMask","••••••••");
    }

    void persist(Snapshot value) {
        try {
            String settings=json.writeValueAsString(value.settings);
            byte[] chat=encrypt(value.chatKey,"chat"), embedding=encrypt(value.embeddingKey,"embedding");
            int changed=value.revision==0 ? db.update("""
                INSERT IGNORE INTO ai_runtime_config(id,revision,settings,chat_secret,embedding_secret) VALUES(1,1,?,?,?)
                """,settings,chat,embedding) : db.update("""
                UPDATE ai_runtime_config SET revision=revision+1,settings=?,chat_secret=?,embedding_secret=? WHERE id=1 AND revision=?
                """,settings,chat,embedding,value.revision);
            if(changed!=1) throw ApiException.conflict("配置已更新，请刷新后重试");
        } catch (IOException | GeneralSecurityException | IllegalArgumentException error) { throw unavailable(); }
    }

    private String model(String value,int limit) {
        if(value==null || value.length()>limit || !value.matches("[A-Za-z0-9_./:@-]*")) throw invalid();
        return value;
    }

    private String endpoint(String value) {
        if(value==null || value.length()>512) throw invalid();
        if(value.isBlank()) return "";
        return AiTransport.validatedUri(value.strip().replaceAll("/+$",""),fixture(value)).toString();
    }

    boolean fixture(String address) {
        return "true".equals(env("AI_CONFIG_FIXTURE")) && address.equals("http://ai-mock:8765/v1");
    }

    private String secret(String next,boolean clear,String old,String oldEndpoint,String newEndpoint) {
        if(clear) return "";
        if(next!=null && !next.isBlank()) {
            if(next.length()>1024 || !next.matches("[!-~]+")) throw invalid();
            return next;
        }
        if(!old.isBlank() && !newEndpoint.equals(oldEndpoint.replaceAll("/+$",""))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"修改接口地址时请重新填写该密钥");
        }
        return old;
    }

    private Cipher cipher(int mode,String slot,byte[] iv) throws GeneralSecurityException,IOException {
        String location=env("AI_CONFIG_KEY_FILE");
        byte[] key=HexFormat.of().parseHex(Files.readString(Path.of(location.isEmpty()?".runtime/ai-master.key":location)).strip());
        if(key.length!=32) throw new GeneralSecurityException();
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(("orderflow-ai-config-v1:"+slot).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private byte[] encrypt(String value,String slot) throws GeneralSecurityException,IOException {
        if(value.isEmpty()) return null;
        byte[] iv=new byte[12]; random.nextBytes(iv);
        byte[] encrypted=cipher(Cipher.ENCRYPT_MODE,slot,iv).doFinal(value.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(iv.length+encrypted.length).put(iv).put(encrypted).array();
    }

    private String decrypt(byte[] value,String slot) throws GeneralSecurityException,IOException {
        if(value==null) return "";
        if(value.length<28) throw new GeneralSecurityException();
        return new String(cipher(Cipher.DECRYPT_MODE,slot,Arrays.copyOf(value,12))
                .doFinal(value,12,value.length-12),StandardCharsets.UTF_8);
    }

    static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"AI 配置参数无效"); }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI 配置主密钥不可用，请检查部署备份"); }
}
