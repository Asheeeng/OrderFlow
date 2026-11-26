package cn.orderflow;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class AdminController {
    private static final List<String> TABLES = List.of("users", "merchants", "categories", "products", "inventory",
            "addresses", "cart_items", "orders", "order_items", "payments", "shipments", "inventory_records",
            "product_price_history");
    private static final Map<String,String> EVIDENCE = Map.of(
            "indexes", "indexes.txt", "concurrency", "concurrency.txt", "binlog", "binlog.txt",
            "replication", "replication.txt", "acceptance", "api-acceptance.txt");

    record Switch(@NotNull Boolean active) {}

    private final Auth auth;
    private final JdbcTemplate primary;
    private final JdbcTemplate replica;
    private final Catalog catalog;
    private final Path evidenceDir;

    AdminController(Auth auth, JdbcTemplate primary, @Qualifier("replicaJdbc") JdbcTemplate replica,
                    Catalog catalog, @Value("${orderflow.evidence-dir}") String evidenceDir) {
        this.auth = auth;
        this.primary = primary;
        this.replica = replica;
        this.catalog = catalog;
        this.evidenceDir = Path.of(evidenceDir);
    }

    @GetMapping("/analytics")
    Map<String,Object> analytics(HttpServletRequest request) {
        Actor actor = auth.require(request, "ADMIN", "MERCHANT");
        String orderScope = actor.merchantId() == null ? "" : " WHERE merchant_id=?";
        Object[] arguments = actor.merchantId() == null ? new Object[0] : new Object[]{actor.merchantId()};
        // 先判定从库是否可用。复制中断时只返回状态并让前端展示统计不可用。
        Map<String,Object> state = replicaStatus();
        Object lag = state.get("lagSeconds");
        if (!Boolean.TRUE.equals(state.get("ready")) || !(lag instanceof Number seconds) || seconds.longValue() > 60) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "统计从库未就绪或延迟超过 60 秒");
        }
        Map<String,Object> summary = replica.queryForMap("""
            SELECT COUNT(*) AS orders,COALESCE(SUM(CASE WHEN status IN ('PAID','SHIPPED','COMPLETED')
            THEN total ELSE 0 END),0) AS sales FROM orders
            """ + orderScope, arguments);
        summary.put("products", replica.queryForObject("SELECT COUNT(*) FROM products" + orderScope, Long.class, arguments));
        List<Map<String,Object>> trend = replica.queryForList("""
            SELECT DATE(created_at) AS day, COUNT(*) AS orders, SUM(total) AS sales FROM orders
            WHERE status IN ('PAID','SHIPPED','COMPLETED') AND created_at>=UTC_DATE()-INTERVAL 29 DAY
              AND created_at < UTC_DATE()+INTERVAL 1 DAY
            """ + (actor.merchantId() == null ? "" : " AND merchant_id=?")
                + " GROUP BY DATE(created_at) ORDER BY day", arguments);
        return Map.of("summary", summary, "trend", trend, "source", state);
    }

    @GetMapping("/admin/users")
    Db.Page users(HttpServletRequest request, @RequestParam(defaultValue="0") @Min(0) long after,
                  @RequestParam(defaultValue="24") @Min(1) @Max(100) int size) {
        auth.require(request, "ADMIN");
        return Db.page(primary.queryForList(
                "SELECT id,username,display_name,role,active FROM users WHERE id>? ORDER BY id LIMIT ?", after, size+1), size);
    }

    @PatchMapping("/admin/users/{id}")
    void user(HttpServletRequest request, @PathVariable @Positive long id, @Valid @RequestBody Switch change) {
        Actor actor = auth.require(request, "ADMIN");
        if (actor.id() == id) throw ApiException.conflict("不能停用当前管理员账号");
        Db.one(primary, "SELECT id FROM users WHERE id=?", "用户", id);
        primary.update("UPDATE users SET active=? WHERE id=?", change.active(), id);
    }

    @GetMapping("/admin/merchants")
    Db.Page merchants(HttpServletRequest request, @RequestParam(defaultValue="0") @Min(0) long after,
                      @RequestParam(defaultValue="24") @Min(1) @Max(100) int size) {
        auth.require(request, "ADMIN");
        return Db.page(primary.queryForList(
                "SELECT id,user_id,name,active FROM merchants WHERE id>? ORDER BY id LIMIT ?", after, size+1), size);
    }

    @PatchMapping("/admin/merchants/{id}")
    void merchant(HttpServletRequest request, @PathVariable @Positive long id, @Valid @RequestBody Switch change) {
        auth.require(request, "ADMIN");
        Db.one(primary, "SELECT id FROM merchants WHERE id=?", "商家", id);
        primary.update("UPDATE merchants SET active=? WHERE id=?", change.active(), id);
    }

    @GetMapping("/admin/system")
    Map<String,Object> system(HttpServletRequest request) {
        auth.require(request, "ADMIN");
        return Map.of("primary", primary.queryForMap("SELECT @@server_id AS serverId,VERSION() AS version,@@global.gtid_mode AS gtidMode"),
                "replica", replicaStatus(), "redis", catalog.cacheState());
    }

    @GetMapping("/admin/counts")
    Map<String,Long> counts(HttpServletRequest request) {
        auth.require(request, "ADMIN");
        Map<String,Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            counts.put(table, primary.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    @GetMapping(value="/admin/evidence/{name}", produces=MediaType.TEXT_PLAIN_VALUE)
    String evidence(HttpServletRequest request, @PathVariable String name) throws IOException {
        auth.require(request, "ADMIN");
        String file = EVIDENCE.get(name);
        if (file == null || !Files.isRegularFile(evidenceDir.resolve(file))) throw ApiException.missing("实验记录");
        return Files.readString(evidenceDir.resolve(file));
    }

    private Map<String,Object> replicaStatus() {
        try {
            Map<String,Object> instance = replica.queryForMap("""
                SELECT @@server_id AS serverId, @@global.read_only AS readOnly,
                       @@global.super_read_only AS superReadOnly
                """);
            var rows = replica.queryForList("SHOW REPLICA STATUS");
            Map<String,Object> status = rows.isEmpty() ? Map.of() : rows.get(0);
            boolean running = "Yes".equals(status.get("Replica_IO_Running")) && "Yes".equals(status.get("Replica_SQL_Running"));
            instance.put("ready", running);
            instance.put("receiver", status.get("Replica_IO_Running"));
            instance.put("applier", status.get("Replica_SQL_Running"));
            instance.put("lagSeconds", status.get("Seconds_Behind_Source"));
            return instance;
        } catch (DataAccessException unavailable) {
            return Map.of("ready", false);
        }
    }
}
