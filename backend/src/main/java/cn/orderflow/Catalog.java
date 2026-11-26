package cn.orderflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

record ProductForm(@Positive long categoryId, @NotBlank @Size(max=120) String name,
                   @NotNull @DecimalMin("0.01") @Digits(integer=10, fraction=2) BigDecimal price,
                   boolean active, @Min(0) @Max(1000000) Integer openingStock,
                   @Pattern(regexp="[A-Za-z0-9_-]{1,48}") String sku) {}

@Service
class Catalog {
    private static final Duration TTL = Duration.ofSeconds(90);
    private static final String FIELDS = """
        SELECT p.id,p.name,p.sku,p.price,p.active,p.category_id,c.name AS category,
               m.name AS merchant,i.available
        FROM products p JOIN inventory i ON p.id=i.product_id
        JOIN categories c ON p.category_id=c.id JOIN merchants m ON p.merchant_id=m.id
        """;
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();

    Catalog(JdbcTemplate db, StringRedisTemplate redis, ObjectMapper json) {
        this.db = db;
        this.redis = redis;
        this.json = json;
    }

    Db.Page list(Actor actor, long after, int size, Long category, String prefix, boolean manage) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(after);
        StringBuilder sql = new StringBuilder(FIELDS).append(" WHERE p.id>?");
        if (!manage) {
            sql.append(" AND p.active=1 AND m.active=1");
        } else if (actor.merchantId() != null) {
            sql.append(" AND p.merchant_id=?");
            parameters.add(actor.merchantId());
        }
        if (category != null) {
            sql.append(" AND p.category_id=?");
            parameters.add(category);
        }
        if (!prefix.isBlank()) {
            sql.append(" AND p.name LIKE ? ESCAPE '!'");
            parameters.add(prefix.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        sql.append(" ORDER BY p.id LIMIT ?");
        parameters.add(size + 1);
        return Db.page(db.queryForList(sql.toString(), parameters.toArray()), size);
    }

    Map<String, Object> detail(long id) {
        // 库存与可售状态始终来自主库。只缓存展示字段，键携带数据库记录版本。
        Map<String, Object> stock = Db.one(db, """
                SELECT i.available,CAST(p.updated_at AS CHAR) AS version
                FROM inventory i JOIN products p ON p.id=i.product_id
                JOIN merchants m ON m.id=p.merchant_id
                WHERE p.id=? AND p.active=1 AND m.active=1
                """, "商品", id);
        String key = cacheKey(id, stock.get("version"));
        Map<String, Object> metadata = null;
        try {
            String value = redis.opsForValue().get(key);
            if (value != null) {
                metadata = new LinkedHashMap<>(json.readValue(value, new TypeReference<Map<String, Object>>() {}));
                hits.increment();
            } else {
                misses.increment();
            }
        } catch (Exception unavailable) {
            fallbacks.increment();
        }
        if (metadata == null) {
            metadata = Db.one(db, """
                SELECT p.id,p.name,p.sku,p.price,c.name AS category,m.name AS merchant
                FROM products p JOIN categories c ON p.category_id=c.id JOIN merchants m ON p.merchant_id=m.id
                WHERE p.id=? AND p.active=1 AND m.active=1
                """, "商品", id);
            try {
                redis.opsForValue().set(key, json.writeValueAsString(metadata), TTL);
            } catch (Exception unavailable) {
                fallbacks.increment();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.put("available", stock.get("available"));
        return result;
    }

    Map<String, Object> cacheState() {
        boolean available = false;
        try {
            available = "PONG".equals(redis.execute(connection -> connection.ping(), true));
        } catch (RuntimeException unavailable) {
            // 连接失败时仍保留当前 JVM 的计数。
        }
        return Map.of("available", available, "hits", hits.sum(), "misses", misses.sum(),
                "fallbackOperations", fallbacks.sum(), "ttlSeconds", TTL.toSeconds());
    }

    @Transactional
    public long create(long merchant, ProductForm form) {
        if (form.sku() == null || form.openingStock() == null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "创建商品需要 SKU 和期初库存");
        }
        long product = Db.insert(db,
                "INSERT INTO products(merchant_id,category_id,sku,name,price,active) VALUES(?,?,?,?,?,?)",
                merchant, form.categoryId(), form.sku(), form.name().strip(), form.price(), form.active());
        db.update("INSERT INTO inventory(product_id,opening_stock,available) VALUES(?,?,?)",
                product, form.openingStock(), form.openingStock());
        return product;
    }

    @Transactional
    public void update(Actor actor, long id, ProductForm form) {
        Map<String, Object> current = owns(actor, id);
        db.update("UPDATE products SET category_id=?,name=?,price=?,active=? WHERE id=?",
                form.categoryId(), form.name().strip(), form.price(), form.active(), id);
        invalidateAfterCommit(id, current.get("version"));
    }

    @Transactional
    public void active(Actor actor, long id, boolean active) {
        Map<String, Object> current = owns(actor, id);
        db.update("UPDATE products SET active=? WHERE id=?", active, id);
        invalidateAfterCommit(id, current.get("version"));
    }

    @Transactional
    public void adjust(Actor actor, long id, int delta) {
        if (delta == 0) throw ApiException.conflict("库存调整量不能为零");
        owns(actor, id);
        if (db.update("UPDATE inventory SET available=available+? WHERE product_id=? AND available+? BETWEEN 0 AND 2147483647",
                delta, id, delta) != 1) throw ApiException.conflict("调整后库存超出允许范围");
        db.update("INSERT INTO inventory_records(product_id,reason,delta) VALUES(?,'ADJUST',?)", id, delta);
    }

    private Map<String, Object> owns(Actor actor, long id) {
        String sql = "SELECT id,CAST(updated_at AS CHAR) AS version FROM products WHERE id=?";
        if (actor.merchantId() == null) return Db.one(db, sql + " FOR UPDATE", "商品", id);
        return Db.one(db, sql + " AND merchant_id=? FOR UPDATE", "商品", id, actor.merchantId());
    }

    private String cacheKey(long id, Object version) {
        return "orderflow:product:" + id + ":" + version;
    }

    private void invalidateAfterCommit(long id, Object version) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redis.delete(cacheKey(id, version));
                } catch (RuntimeException unavailable) {
                    fallbacks.increment();
                }
            }
        });
    }
}

@RestController
@RequestMapping("/api")
class CatalogController {
    record Active(@NotNull Boolean active) {}
    record Adjustment(@Min(-1000000) @Max(1000000) int delta) {}

    private final Auth auth;
    private final Catalog catalog;
    private final JdbcTemplate db;

    CatalogController(Auth auth, Catalog catalog, JdbcTemplate db) {
        this.auth = auth;
        this.catalog = catalog;
        this.db = db;
    }

    @GetMapping("/categories")
    List<Map<String, Object>> categories() {
        return db.queryForList("SELECT id,name FROM categories ORDER BY id");
    }

    @GetMapping("/products")
    Db.Page products(HttpServletRequest request, @RequestParam(defaultValue="0") @Min(0) long after,
                     @RequestParam(defaultValue="24") @Min(1) @Max(100) int size,
                     @RequestParam(required=false) Long category, @RequestParam(defaultValue="") @Size(max=120) String q) {
        return catalog.list(null, after, size, category, q, false);
    }

    @GetMapping("/products/{id}")
    Map<String, Object> product(@PathVariable @Positive long id) {
        return catalog.detail(id);
    }

    @GetMapping("/manage/products")
    Db.Page manage(HttpServletRequest request, @RequestParam(defaultValue="0") @Min(0) long after,
                   @RequestParam(defaultValue="24") @Min(1) @Max(100) int size,
                   @RequestParam(required=false) Long category, @RequestParam(defaultValue="") @Size(max=120) String q) {
        return catalog.list(auth.require(request, "ADMIN", "MERCHANT"), after, size, category, q, true);
    }

    @PostMapping("/manage/products")
    Map<String, Long> create(HttpServletRequest request, @Valid @RequestBody ProductForm form) {
        Actor actor = auth.require(request, "MERCHANT");
        return Map.of("id", catalog.create(actor.merchantId(), form));
    }

    @PutMapping("/manage/products/{id}")
    void update(HttpServletRequest request, @PathVariable @Positive long id, @Valid @RequestBody ProductForm form) {
        catalog.update(auth.require(request, "ADMIN", "MERCHANT"), id, form);
    }

    @PatchMapping("/manage/products/{id}/active")
    void active(HttpServletRequest request, @PathVariable @Positive long id, @Valid @RequestBody Active form) {
        catalog.active(auth.require(request, "ADMIN", "MERCHANT"), id, form.active());
    }

    @PostMapping("/manage/products/{id}/stock")
    void stock(HttpServletRequest request, @PathVariable @Positive long id, @Valid @RequestBody Adjustment form) {
        catalog.adjust(auth.require(request, "MERCHANT"), id, form.delta());
    }
}
