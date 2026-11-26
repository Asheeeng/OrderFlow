package cn.orderflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

interface AiModel {
    JsonNode chat(long user, String operation, List<?> messages, List<?> tools);
    double[] embed(long user, String operation, String input);
}

@Component
class AiProvider implements AiModel {
    private final AiStore audit;
    private final Environment env;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    AiProvider(AiStore audit, Environment env) {
        this.audit = audit;
        this.env = env;
    }

    String setting(String name) { return env.getProperty(name, "").trim(); }
    String embeddingModel() { return setting("AI_EMBEDDING_MODEL"); }
    String embeddingSpace() { return AiKnowledge.hash(setting("AI_EMBEDDING_BASE_URL").replaceAll("/+$","") + "|" +
            embeddingModel() + "|" + env.getProperty("AI_EMBEDDING_VERSION","1")); }
    boolean chatReady() { return configured("AI_BASE_URL", "AI_API_KEY", "AI_CHAT_MODEL"); }
    boolean embeddingReady() { return configured("AI_EMBEDDING_BASE_URL", "AI_EMBEDDING_API_KEY", "AI_EMBEDDING_MODEL"); }

    private boolean configured(String base, String key, String model) {
        return "true".equalsIgnoreCase(setting("AI_ENABLED")) && !setting(base).isBlank()
                && !setting(key).isBlank() && !setting(model).isBlank() && setting(model).length() <= 160;
    }

    @Override public JsonNode chat(long user, String operation, List<?> messages, List<?> tools) {
        if (!chatReady()) throw disabled();
        var payload = new LinkedHashMap<String,Object>();
        payload.put("model", setting("AI_CHAT_MODEL"));
        payload.put("messages", messages);
        payload.put("max_tokens", 650);
        payload.put("temperature", 0);
        if (!tools.isEmpty()) {
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
        }
        JsonNode result = send(user, operation, "AI", "chat/completions", payload)
                .path("choices").path(0).path("message");
        if (!result.isObject()) throw invalidReply();
        return result;
    }

    @Override public double[] embed(long user, String operation, String input) {
        if (!embeddingReady()) throw disabled();
        var data = send(user, operation, "AI_EMBEDDING", "embeddings",
                Map.of("model", embeddingModel(), "input", input)).path("data").path(0).path("embedding");
        if (!data.isArray() || data.isEmpty() || data.size() > 4096) throw invalidReply();
        double[] vector = new double[data.size()];
        double norm = 0;
        for (int i=0; i<vector.length; i++) {
            if (!data.get(i).isNumber()) throw invalidReply();
            vector[i] = data.get(i).doubleValue();
            norm += vector[i] * vector[i];
        }
        if (!Double.isFinite(norm) || norm == 0) throw invalidReply();
        return vector;
    }

    private JsonNode send(long user, String operation, String prefix, String path, Map<String,Object> payload) {
        URI endpoint;
        try {
            var base = URI.create(setting(prefix + "_BASE_URL").replaceAll("/+$",""));
            boolean secure = "https".equalsIgnoreCase(base.getScheme()) ||
                    ("http".equalsIgnoreCase(base.getScheme()) && "true".equalsIgnoreCase(setting("AI_ALLOW_INSECURE_HTTP")));
            if (!secure || base.getHost() == null || base.getUserInfo() != null ||
                    base.getQuery() != null || base.getFragment() != null) throw new IllegalArgumentException();
            endpoint = URI.create(base + "/" + path);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "模型地址配置无效");
        }
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + setting(prefix + "_API_KEY"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(audit.encode(payload))).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            long id = audit.reserve(user, operation, (String) payload.get("model"));
            long start = System.nanoTime();
            String status = "NETWORK_ERROR";
            JsonNode usage = MissingNode.getInstance();
            try {
                var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> response;
                try { response = pending.get(8, TimeUnit.SECONDS); }
                catch (TimeoutException | InterruptedException deadline) { pending.cancel(true); throw deadline; }
                status = "HTTP_" + response.statusCode();
                if ((response.statusCode() == 429 || response.statusCode() == 503) && attempt == 0) {
                    Thread.sleep(300);
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "模型服务调用失败，请稍后再试");
                }
                status = "INVALID_REPLY";
                if (response.body().length() > 512_000) throw invalidReply();
                JsonNode node = audit.parse(response.body());
                if (!node.isObject()) throw invalidReply();
                usage = node.path("usage");
                status = "OK";
                return node;
            } catch (TimeoutException error) {
                status = "TIMEOUT";
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "模型服务响应超时");
            } catch (ExecutionException error) {
                if (error.getCause() instanceof HttpTimeoutException) {
                    status = "TIMEOUT";
                    throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "模型服务响应超时");
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "无法连接模型服务");
            } catch (IllegalArgumentException error) {
                throw invalidReply();
            } catch (InterruptedException error) {
                status = "INTERRUPTED";
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "模型调用已中断");
            } finally {
                audit.finish(id, status, AiStore.millis(start), usage);
            }
        }
        throw invalidReply();
    }

    static ApiException disabled() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 尚未启用或模型未配置"); }
    static ApiException invalidReply() { return new ApiException(HttpStatus.BAD_GATEWAY, "模型返回内容无法验证"); }
}
