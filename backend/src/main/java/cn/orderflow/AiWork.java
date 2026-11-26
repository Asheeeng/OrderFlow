package cn.orderflow;

import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class AiWork {
    private final Semaphore capacity = new Semaphore(2);
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    <T> T run(Actor actor, Supplier<T> work) {
        if (!running.add(actor.id())) throw busy();
        try {
            if (!capacity.tryAcquire()) throw busy();
            try { return work.get(); }
            finally { capacity.release(); }
        } finally { running.remove(actor.id()); }
    }

    private ApiException busy() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI 正在处理请求，请稍后再试");
    }

    static String safePrompt(String input) {
        return input.strip()
            .replaceAll("(?im)((?:密码|口令|密钥|token|收货地址|详细地址|身份证号)\\s*[:：=]\\s*)[^\\r\\n，。；]+", "$1[已遮盖]")
            .replaceAll("(?i)(?:sk-[0-9A-Za-z_-]{8,}|[0-9A-Za-z.!#$%&'*+/=?^_`{|}~-]+@[0-9A-Za-z.-]+\\.[A-Za-z]{2,}|(?<![0-9])[0-9]{11,}(?![0-9]))", "[已遮盖]");
    }
}
