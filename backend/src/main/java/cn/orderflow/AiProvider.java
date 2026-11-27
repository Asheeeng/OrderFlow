package cn.orderflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

interface AiModel {
    JsonNode chat(long user, String operation, List<?> messages, List<?> tools);
    double[] embed(long user, String operation, String input);
}

@Component
class AiProvider implements AiModel {
    private final AiStore audit;
    private final AiConfig config;

    AiProvider(AiStore audit, AiConfig config) {
        this.audit=audit; this.config=config;
    }

    String embeddingModel() { return config.current().settings().embeddingModel(); }
    String embeddingSpace() {
        var snapshot=config.current();
        var s=snapshot.settings();
        return AiKnowledge.hash(s.embeddingUrl().replaceAll("/+$","")+"|"+s.embeddingModel()+"|"+s.embeddingVersion()
                +(snapshot.legacy()?"":"|"+s.dimension()));
    }
    int embeddingDimension() { return config.current().settings().dimension(); }
    boolean chatReady() {
        var c=config.current(); var s=c.settings();
        return s.enabled() && !s.chatUrl().isBlank() && !c.chatKey().isBlank() && !s.chatModel().isBlank();
    }
    boolean embeddingReady() {
        var c=config.current(); var s=c.settings();
        return s.enabled() && !s.embeddingUrl().isBlank() && !c.embeddingSecret().isBlank() && !s.embeddingModel().isBlank();
    }

    @Override public JsonNode chat(long user, String operation, List<?> messages, List<?> tools) {
        if (!chatReady()) throw disabled();
        var payload = new LinkedHashMap<String,Object>();
        payload.put("model", config.current().settings().chatModel());
        payload.put("messages", messages);
        payload.put("max_tokens", config.current().settings().maxTokens());
        payload.put("temperature", 0);
        URI base=URI.create(config.current().settings().chatUrl());
        if(base.getHost()!=null && (base.getHost().equals("dashscope.aliyuncs.com") || base.getHost().endsWith(".maas.aliyuncs.com"))) {
            payload.put("enable_thinking",false);
        }
        if (operation.equals("CONFIG_TOOL") && !tools.isEmpty() && messages.stream().noneMatch(
                message -> message instanceof Map<?,?> item && "tool".equals(item.get("role")))) {
            payload.put("tool_choice",Map.of("type","function","function",Map.of("name","verify_echo")));
        }
        if (!tools.isEmpty()) {
            payload.put("tools", tools);
            payload.putIfAbsent("tool_choice", "auto");
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
        var snapshot=config.current();
        var settings=snapshot.settings();
        boolean embedding=prefix.equals("AI_EMBEDDING");
        String address=(embedding?settings.embeddingUrl():settings.chatUrl()).replaceAll("/+$","");
        boolean fixture=config.fixture(address) || (snapshot.legacy() && address.equals("http://ai-mock:8765/v1")
                && "true".equals(config.env("AI_ALLOW_INSECURE_HTTP")));
        URI endpoint=AiTransport.validatedUri(address,fixture);
        String key=embedding?snapshot.embeddingSecret():snapshot.chatKey();
        for (int attempt = 0; attempt < 2; attempt++) {
            long id = audit.reserve(user, operation, (String) payload.get("model"));
            long start = System.nanoTime();
            String status = "NETWORK_ERROR";
            JsonNode usage = MissingNode.getInstance();
            try {
                var response=AiTransport.post(endpoint,path,key,audit.encode(payload),settings.timeoutSeconds(),fixture);
                status="HTTP_"+response.status();
                if ((response.status()==429 || response.status()==503) && attempt==0) {
                    Thread.sleep(300); continue;
                }
                if (response.status()!=200) throw new ApiException(HttpStatus.BAD_GATEWAY,
                        response.status()==401 || response.status()==403 ? "模型密钥或模型授权无效" : "模型服务拒绝请求或暂不可用");
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
            } catch (java.io.IOException error) {
                status = "NETWORK_ERROR";
                if (attempt == 0 && !(error.getMessage()!=null && error.getMessage().contains("body limit"))) {
                    try { Thread.sleep(300); continue; }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"模型连接重试已中断");
                    }
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "模型连接失败、地址受限或响应超限");
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
