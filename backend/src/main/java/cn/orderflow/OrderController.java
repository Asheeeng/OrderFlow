package cn.orderflow;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class OrderController {
    enum Status { PENDING, PAID, SHIPPED, COMPLETED, CANCELLED }
    record Address(@NotBlank @Size(max=80) String recipient, @NotBlank @Size(max=255) String destination) {}
    record Quantity(@Min(0) @Max(999) int quantity) {}
    record Checkout(@Positive long addressId) {}
    record Shipping(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,48}") String trackingNo) {}

    private final Auth auth;
    private final JdbcTemplate db;
    private final OrderService orders;

    OrderController(Auth auth, JdbcTemplate db, OrderService orders) {
        this.auth = auth;
        this.db = db;
        this.orders = orders;
    }

    @GetMapping("/addresses")
    List<Map<String, Object>> addresses(HttpServletRequest request) {
        return db.queryForList("SELECT id,recipient,destination FROM addresses WHERE user_id=? ORDER BY id",
                auth.require(request, "CUSTOMER").id());
    }

    @PostMapping("/addresses")
    Map<String, Long> addAddress(HttpServletRequest request, @Valid @RequestBody Address body) {
        long user = auth.require(request, "CUSTOMER").id();
        return Map.of("id", Db.insert(db, "INSERT INTO addresses(user_id,recipient,destination) VALUES(?,?,?)",
                user, body.recipient().strip(), body.destination().strip()));
    }

    @GetMapping("/cart")
    List<Map<String, Object>> cart(HttpServletRequest request) {
        return db.queryForList("""
                SELECT p.id,p.name,p.price,p.active,i.available,c.quantity,m.name AS merchant
                FROM cart_items c JOIN products p ON p.id=c.product_id
                JOIN inventory i ON i.product_id=p.id JOIN merchants m ON m.id=p.merchant_id
                WHERE c.user_id=? ORDER BY p.id
                """, auth.require(request, "CUSTOMER").id());
    }

    @PutMapping("/cart/{id}")
    void cart(HttpServletRequest request, @PathVariable @Positive long id, @Valid @RequestBody Quantity body) {
        orders.cart(auth.require(request, "CUSTOMER").id(), id, body.quantity());
    }

    @PostMapping("/orders")
    Map<String, List<Long>> checkout(HttpServletRequest request, @Valid @RequestBody Checkout body) {
        long user = auth.require(request, "CUSTOMER").id();
        return Map.of("ids", retryDeadlock(() -> orders.checkout(user, body.addressId())));
    }

    @GetMapping("/orders")
    Db.Page list(HttpServletRequest request, @RequestParam(defaultValue="0") @Min(0) long before,
                 @RequestParam(defaultValue="24") @Min(1) @Max(100) int size,
                 @RequestParam(required=false) Status status) {
        Actor actor = auth.require(request);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT o.id,o.user_id,o.merchant_id,o.status,o.total,o.created_at,m.name AS merchant
            FROM orders o JOIN merchants m ON m.id=o.merchant_id WHERE 1=1
            """);
        scope(sql, args, actor);
        if (before > 0) {
            sql.append(" AND o.id<?");
            args.add(before);
        }
        if (status != null) {
            sql.append(" AND o.status=?");
            args.add(status.name());
        }
        sql.append(" ORDER BY o.id DESC LIMIT ?");
        args.add(size+1);
        return Db.page(db.queryForList(sql.toString(), args.toArray()), size);
    }

    @GetMapping("/orders/{id}")
    Map<String, Object> detail(HttpServletRequest request, @PathVariable @Positive long id) {
        Actor actor = auth.require(request);
        List<Object> args = new ArrayList<>();
        args.add(id);
        StringBuilder sql = new StringBuilder("""
            SELECT o.*,m.name AS merchant,p.paid_at,s.tracking_no,s.shipped_at,s.delivered_at
            FROM orders o JOIN merchants m ON o.merchant_id=m.id
            LEFT JOIN payments p ON p.order_id=o.id LEFT JOIN shipments s ON s.order_id=o.id WHERE o.id=?
            """);
        scope(sql, args, actor);
        var order = Db.one(db, sql.toString(), "订单", args.toArray());
        order.put("items", db.queryForList("""
                SELECT product_id,product_name,unit_price,quantity FROM order_items WHERE order_id=? ORDER BY id
                """, id));
        return order;
    }

    @PostMapping("/orders/{id}/pay")
    void pay(HttpServletRequest request, @PathVariable @Positive long id) {
        orders.pay(id, auth.require(request, "CUSTOMER").id());
    }

    @PostMapping("/orders/{id}/cancel")
    void cancel(HttpServletRequest request, @PathVariable @Positive long id) {
        long user = auth.require(request, "CUSTOMER").id();
        retryDeadlock(() -> { orders.cancel(id, user); return null; });
    }

    @PostMapping("/orders/{id}/ship")
    void ship(HttpServletRequest request, @PathVariable @Positive long id, @Valid @RequestBody Shipping shipping) {
        orders.ship(id, auth.require(request, "MERCHANT").merchantId(), shipping.trackingNo());
    }

    @PostMapping("/orders/{id}/receive")
    void receive(HttpServletRequest request, @PathVariable @Positive long id) {
        orders.receive(id, auth.require(request, "CUSTOMER").id());
    }

    private void scope(StringBuilder sql, List<Object> args, Actor actor) {
        if (actor.role().equals("CUSTOMER")) {
            sql.append(" AND o.user_id=?");
            args.add(actor.id());
        }
        if (actor.merchantId() != null) {
            sql.append(" AND o.merchant_id=?");
            args.add(actor.merchantId());
        }
    }

    private <T> T retryDeadlock(Supplier<T> transaction) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.get();
            } catch (DataAccessException error) {
                if (!(error.getMostSpecificCause() instanceof SQLException sql) || sql.getErrorCode() != 1213) throw error;
                if (attempt >= 2) throw ApiException.conflict("并发竞争，操作已回滚，请重试");
                try {
                    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(20, 50) * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "操作已中断");
                }
            }
        }
    }
}
