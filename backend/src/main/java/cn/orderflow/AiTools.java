package cn.orderflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class AiTools {
    private static final List<String> DATE = List.of("start", "endExclusive");
    private static final List<String> RANK = List.of("start", "endExclusive", "limit");
    private static final String PAID = """
        FROM payments pay JOIN orders o ON o.id=pay.order_id
        WHERE pay.status='SUCCESS' AND o.status<>'CANCELLED' AND pay.paid_at>=? AND pay.paid_at<?
        """;
    private static final String ITEMS = """
        FROM payments pay JOIN orders o ON o.id=pay.order_id JOIN order_items oi ON oi.order_id=o.id
        JOIN products p ON p.id=oi.product_id
        """;
    private static final String PAID_RANGE = """
        WHERE pay.status='SUCCESS' AND o.status<>'CANCELLED' AND pay.paid_at>=? AND pay.paid_at<?
        """;
    private static final String STOCK = " FROM products p JOIN inventory i ON i.product_id=p.id ";
    private static final String DATE_NOTE = "UTC 起日包含，止日不包含，最多 92 天。";
    private static final String MONEY_NOTE = "销售额按模拟成功支付时间统计，排除取消订单。";
    private static final Map<String,Definition> DEFINITIONS = definitions();
    private final AiSql sql;

    record Definition(boolean live, List<String> keys, String description, String statement,
                      String chart, String labelField, String valueField) {}

    record Result(String name, Map<String,Object> arguments, String source, String definition,
                  String sqlTemplate, long elapsedMs, List<Map<String,Object>> rows,
                  String chart, String labelField, String valueField) {}

    AiTools(AiSql sql) { this.sql = sql; }

    private static Map<String,Definition> definitions() {
        var tools = new LinkedHashMap<String,Definition>();
        tools.put("query_sales", new Definition(false, DATE, MONEY_NOTE + DATE_NOTE,
                "SELECT COALESCE(SUM(pay.amount),0) sales, COUNT(*) paid_orders " + PAID, "", "", ""));
        tools.put("query_top_products", new Definition(false, RANK, "按成功支付明细件数降序。" + DATE_NOTE, """
                SELECT p.id product_id,p.sku,p.name label,SUM(oi.quantity) units,
                       SUM(oi.quantity*oi.unit_price) sales
                """ + ITEMS + PAID_RANGE + " GROUP BY p.id ORDER BY units DESC,p.id LIMIT ?", "bar", "label", "units"));
        tools.put("query_low_stock", new Definition(true, List.of("threshold", "limit"),
                "在售商品当前可用库存小于或等于阈值。", "SELECT p.id product_id,p.sku,p.name label,i.available " +
                STOCK + " WHERE p.active=1 AND i.available<=? ORDER BY i.available,p.id LIMIT ?", "bar", "label", "available"));
        tools.put("query_order_status", new Definition(false, DATE, "按下单时间汇总当前订单状态。" + DATE_NOTE,
                "SELECT status label,COUNT(*) orders FROM orders WHERE created_at>=? AND created_at<? GROUP BY status ORDER BY status",
                "pie", "label", "orders"));
        tools.put("query_sales_trend", new Definition(false, DATE, "按 UTC 日期汇总。" + MONEY_NOTE + DATE_NOTE,
                "SELECT DATE_FORMAT(pay.paid_at,'%Y-%m-%d') label,SUM(pay.amount) sales " + PAID +
                " GROUP BY label ORDER BY label", "line", "label", "sales"));
        tools.put("query_product_stock", new Definition(true, List.of("productId"), "按商品 ID 查询主库实时库存。",
                "SELECT p.id product_id,p.sku,p.name label,p.active,i.available " + STOCK + " WHERE p.id=?", "", "", ""));
        tools.put("query_category_sales", new Definition(false, RANK,
                "按当前分类归属汇总成功支付订单明细金额。" + DATE_NOTE,
                "SELECT c.id category_id,c.name label,SUM(oi.quantity*oi.unit_price) sales " + ITEMS +
                " JOIN categories c ON c.id=p.category_id " + PAID_RANGE + " GROUP BY c.id ORDER BY sales DESC,c.id LIMIT ?",
                "bar", "label", "sales"));
        tools.put("query_inventory_summary", new Definition(true, List.of("threshold"),
                "在售商品的 SKU 数、可用总量、缺货和低库存 SKU 数。",
                """
                SELECT COUNT(*) active_products,COALESCE(SUM(i.available),0) available,
                       COALESCE(SUM(i.available=0),0) out_of_stock,COALESCE(SUM(i.available<=?),0) low_stock
                """ + STOCK + " WHERE p.active=1", "", "", ""));
        return Collections.unmodifiableMap(tools);
    }

    List<Map<String,Object>> descriptions() {
        return DEFINITIONS.entrySet().stream().map(entry -> {
            var def = entry.getValue();
            var properties = new LinkedHashMap<String,Object>();
            for (String key : def.keys()) {
                properties.put(key, DATE.contains(key) ? Map.of("type","string","format","date",
                        "description", key.equals("start") ? "UTC inclusive start date" : "UTC exclusive end date")
                        : Map.of("type","integer","minimum",min(key),"maximum",max(key)));
            }
            var params = Map.of("type","object","properties",properties,"required",def.keys(),"additionalProperties",false);
            return Map.<String,Object>of("type","function","function",
                    Map.of("name",entry.getKey(),"description",def.description(),"parameters",params));
        }).toList();
    }

    Result run(String name, JsonNode input) {
        var def = DEFINITIONS.get(name);
        if (def == null || !input.isObject() || input.size() != def.keys().size()) throw new IllegalArgumentException();
        var args = new LinkedHashMap<String,Object>();
        var bound = new ArrayList<>();
        for (String key : def.keys()) {
            var node = input.path(key);
            if (DATE.contains(key)) {
                if (!node.isTextual() || !node.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
                var date = LocalDate.parse(node.asText());
                if (date.getYear() < 2000 || date.getYear() > 2100) throw new IllegalArgumentException();
                args.put(key, date.toString());
                bound.add(date.atStartOfDay());
            } else {
                if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException();
                long number = node.longValue();
                if (number < min(key) || number > max(key)) throw new IllegalArgumentException();
                args.put(key, number);
                bound.add(number);
            }
        }
        if (args.containsKey("start")) {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(args.get("start").toString()),
                    LocalDate.parse(args.get("endExclusive").toString()));
            if (days <= 0 || days > 92) throw new IllegalArgumentException();
        }
        long start = System.nanoTime();
        var rows = sql.read(def.live(), def.statement(), bound.toArray());
        return new Result(name, args, def.live() ? "MySQL 主库只读" : "MySQL GTID 从库只读",
                def.description(), def.statement(), AiStore.millis(start), rows, def.chart(), def.labelField(), def.valueField());
    }

    private static long min(String key) { return key.equals("threshold") ? 0 : 1; }

    private static long max(String key) {
        return switch (key) {
            case "threshold" -> 100000;
            case "limit" -> 20;
            default -> 2_000_000_000L;
        };
    }
}
