package cn.orderflow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderService {
    private record Item(long id, long merchant, String name, BigDecimal price, int quantity) {}

    private final JdbcTemplate db;

    OrderService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<Long> checkout(long user, long address) {
        lockCart(user);
        String destination = db.query("""
                SELECT CONCAT(recipient,' / ',destination) FROM addresses WHERE id=? AND user_id=?
                """, rs -> rs.next() ? rs.getString(1) : null, address, user);
        if (destination == null) throw ApiException.missing("收货地址");
        List<Map<String, Object>> cart = db.queryForList(
                "SELECT product_id,quantity FROM cart_items WHERE user_id=? ORDER BY product_id", user);
        if (cart.isEmpty()) throw ApiException.conflict("购物车为空");

        Map<Long, List<Item>> groups = new TreeMap<>();
        for (Map<String, Object> row : cart) {
            long product = ((Number) row.get("product_id")).longValue();
            int quantity = ((Number) row.get("quantity")).intValue();
            Item item = db.query("""
                SELECT p.id,p.merchant_id,p.name,p.price FROM products p WHERE p.id=? AND p.active=1 FOR SHARE
                """, rs -> rs.next() ? new Item(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getBigDecimal(4), quantity) : null, product);
            if (item == null || db.query("""
                    SELECT active FROM merchants WHERE id=? FOR SHARE
                    """, rs -> !rs.next() || !rs.getBoolean(1), item.merchant())) {
                throw ApiException.conflict("商品已下架或商家已停用：" + product);
            }
            if (db.update("UPDATE inventory SET available=available-? WHERE product_id=? AND available>=?",
                    quantity, product, quantity) != 1) {
                throw ApiException.conflict("商品库存不足：" + product);
            }
            groups.computeIfAbsent(item.merchant(), ignored -> new ArrayList<>()).add(item);
        }

        List<Long> orders = new ArrayList<>();
        for (var group : groups.entrySet()) {
            BigDecimal total = group.getValue().stream().map(i -> i.price().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long order = Db.insert(db, "INSERT INTO orders(user_id,merchant_id,status,total,ship_to) VALUES(?,?,'PENDING',?,?)",
                    user, group.getKey(), total, destination);
            for (Item item : group.getValue()) {
                db.update("INSERT INTO order_items(order_id,product_id,product_name,unit_price,quantity) VALUES(?,?,?,?,?)",
                        order, item.id(), item.name(), item.price(), item.quantity());
                db.update("INSERT INTO inventory_records(product_id,order_id,reason,delta) VALUES(?,?,'RESERVE',?)",
                        item.id(), order, -item.quantity());
            }
            orders.add(order);
        }
        db.update("DELETE FROM cart_items WHERE user_id=?", user);
        return orders;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void cart(long user, long product, int quantity) {
        lockCart(user);
        if (quantity == 0) {
            db.update("DELETE FROM cart_items WHERE user_id=? AND product_id=?", user, product);
            return;
        }
        Db.one(db, "SELECT id FROM products WHERE id=? AND active=1", "商品", product);
        db.update("""
            INSERT INTO cart_items(user_id,product_id,quantity) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE quantity=?
            """, user, product, quantity, quantity);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void pay(long id, long user) {
        Map<String, Object> order = locked(id, "user_id", user);
        expect(order, "PENDING");
        db.update("UPDATE orders SET status='PAID' WHERE id=?", id);
        db.update("INSERT INTO payments(order_id,amount) VALUES(?,?)", id, order.get("total"));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void cancel(long id, long user) {
        Map<String, Object> order = locked(id, "user_id", user);
        expect(order, "PENDING");
        List<Map<String, Object>> items = db.queryForList(
                "SELECT product_id,quantity FROM order_items WHERE order_id=? ORDER BY product_id", id);
        for (var item : items) {
            db.update("UPDATE inventory SET available=available+? WHERE product_id=?",
                    item.get("quantity"), item.get("product_id"));
            db.update("INSERT INTO inventory_records(product_id,order_id,reason,delta) VALUES(?,?,'RELEASE',?)",
                    item.get("product_id"), id, item.get("quantity"));
        }
        db.update("UPDATE orders SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void ship(long id, long merchant, String tracking) {
        Map<String, Object> order = locked(id, "merchant_id", merchant);
        expect(order, "PAID");
        db.update("UPDATE orders SET status='SHIPPED' WHERE id=?", id);
        db.update("INSERT INTO shipments(order_id,tracking_no,status) VALUES(?,?,'SHIPPED')", id, tracking);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void receive(long id, long user) {
        Map<String, Object> order = locked(id, "user_id", user);
        expect(order, "SHIPPED");
        db.update("UPDATE orders SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE id=?", id);
        db.update("""
                UPDATE shipments s JOIN orders o ON s.order_id=o.id
                SET s.status='DELIVERED',s.delivered_at=o.completed_at WHERE s.order_id=?
                """, id);
    }

    private void lockCart(long user) {
        Db.one(db, "SELECT id FROM users WHERE id=? AND active=1 FOR UPDATE", "用户", user);
    }

    private Map<String, Object> locked(long id, String owner, long ownerId) {
        return Db.one(db, "SELECT status,total FROM orders WHERE id=? AND " + owner + "=? FOR UPDATE", "订单", id, ownerId);
    }

    private void expect(Map<String, Object> order, String expected) {
        if (!expected.equals(order.get("status"))) {
            throw ApiException.conflict("当前订单状态不允许重复或越级操作");
        }
    }
}
