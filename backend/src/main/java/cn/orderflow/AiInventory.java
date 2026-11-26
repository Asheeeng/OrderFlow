package cn.orderflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class AiInventory {
    private final AiSql sql;
    private final AiStore store;
    private final AiProvider model;

    private static final String METRICS = """
        WITH sold AS (
          SELECT oi.product_id,
                 SUM(CASE WHEN pay.paid_at>=? THEN oi.quantity ELSE 0 END) sales_7,
                 SUM(CASE WHEN pay.paid_at>=? AND pay.paid_at<? THEN oi.quantity ELSE 0 END) previous_7,
                 SUM(oi.quantity) sales_30
          FROM orders o JOIN payments pay ON pay.order_id=o.id JOIN order_items oi ON oi.order_id=o.id
          WHERE o.merchant_id=? AND o.status<>'CANCELLED' AND pay.status='SUCCESS' AND pay.paid_at>=? AND pay.paid_at<?
          GROUP BY oi.product_id
        ), metrics AS (
          SELECT p.id product_id,p.sku,p.name label,i.available,COALESCE(s.sales_7,0) sales_7,
                 COALESCE(s.previous_7,0) previous_7,COALESCE(s.sales_30,0) sales_30,
                 ROUND(COALESCE(s.sales_30,0)/30,2) daily_sales,
                 ROUND(i.available*30/NULLIF(s.sales_30,0),1) days_left
          FROM products p JOIN inventory i ON i.product_id=p.id LEFT JOIN sold s ON s.product_id=p.id
          WHERE p.merchant_id=? AND p.active=1 AND (? IS NULL OR p.id=?)
        )
        SELECT metrics.*,COUNT(*) OVER() products, SUM(available) OVER() total_units,
               SUM(available=0) OVER() out_of_stock,SUM(available<=10) OVER() low_stock,
               SUM(sales_30>=10 AND (available<=10 OR days_left<=7)) OVER() high_demand_low_stock,
               SUM(sales_30<=2 AND available>0) OVER() slow_stock
        FROM metrics ORDER BY (available=0) DESC,(sales_30>=10 AND (available<=10 OR days_left<=7)) DESC,
        (available<=10) DESC,sales_30 DESC,product_id LIMIT 20
        """;
    private static final String TREND = """
        SELECT DATE_FORMAT(day,'%Y-%m-%d') label,SUM(sold) sold,SUM(delta) delta FROM (
          SELECT DATE(pay.paid_at) day,SUM(oi.quantity) sold,0 delta
          FROM orders o JOIN payments pay ON pay.order_id=o.id JOIN order_items oi ON oi.order_id=o.id
          JOIN products p ON p.id=oi.product_id
          WHERE o.merchant_id=? AND p.merchant_id=? AND p.active=1 AND (? IS NULL OR p.id=?)
            AND o.status<>'CANCELLED' AND pay.status='SUCCESS' AND pay.paid_at>=? AND pay.paid_at<?
          GROUP BY DATE(pay.paid_at)
          UNION ALL
          SELECT DATE(r.created_at),0,SUM(r.delta) FROM inventory_records r JOIN products p ON p.id=r.product_id
          WHERE p.merchant_id=? AND p.active=1 AND (? IS NULL OR p.id=?) AND r.created_at>=? AND r.created_at<?
          GROUP BY DATE(r.created_at)
        ) changes GROUP BY day ORDER BY day
        """;

    AiInventory(AiSql sql, AiStore store, AiProvider model) {
        this.sql = sql;
        this.store = store;
        this.model = model;
    }

    Map<String,Object> report(Actor actor, LocalDate asOf, Long product) {
        if (!model.chatReady()) throw AiProvider.disabled();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (asOf == null) asOf = today;
        if (asOf.isAfter(today) || asOf.isBefore(LocalDate.of(2000,1,1))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"统计日期无效");
        }
        long merchant = actor.merchantId();
        var end = asOf.plusDays(1).atStartOfDay();
        var week = end.minusDays(7);
        var month = end.minusDays(30);
        var rows = sql.read(true, METRICS,week,end.minusDays(14),week,merchant,month,end,merchant,product,product);
        if (product != null && rows.isEmpty()) throw ApiException.missing("在售商品");
        var totals = new LinkedHashMap<String,Object>();
        for (String key : List.of("products","total_units","out_of_stock","low_stock","high_demand_low_stock","slow_stock")) {
            totals.put(key,rows.isEmpty() ? 0 : rows.get(0).get(key));
            for (var row : rows) row.remove(key);
        }
        for (var row : rows) row.put("risk", risk(row));
        var trend = sql.read(true,TREND,merchant,merchant,product,product,month,end,merchant,product,product,month,end);
        var snapshot = Map.<String,Object>of("asOf",asOf.toString(),"source","MySQL 主库只读",
                "definition","当前库存为实时值，销量按 UTC 成功支付时间排除取消单。趋势库存列为流水净变化。",
                "totals",totals,"products",rows,"trend",trend);
        var advice = rows.isEmpty() ? List.<Map<String,Object>>of() : suggest(actor.id(),rows);
        long id = Db.insert(store.db, "INSERT INTO ai_inventory_reports(user_id,merchant_id,snapshot,advice) VALUES(?,?,?,?)",
                actor.id(),merchant,store.encode(snapshot),store.encode(advice));
        return Map.of("id",id,"snapshot",snapshot,"advice",advice);
    }

    private String risk(Map<String,Object> row) {
        long stock = ((Number)row.get("available")).longValue();
        long sold = ((Number)row.get("sales_30")).longValue();
        Number days = (Number)row.get("days_left");
        if (stock==0) return "OUT_OF_STOCK";
        if (sold>=10 && (stock<=10 || (days!=null && days.doubleValue()<=7))) return "HIGH_DEMAND_LOW_STOCK";
        if (stock<=10) return "LOW_STOCK";
        if (sold==0) return "NO_SALES";
        if (sold<=2) return "SLOW_STOCK";
        return "NORMAL";
    }

    private Set<String> options(String risk) {
        return switch (risk) {
            case "HIGH_DEMAND_LOW_STOCK", "OUT_OF_STOCK" -> Set.of("REPLENISH","MONITOR");
            case "LOW_STOCK" -> Set.of("MONITOR","REPLENISH");
            case "SLOW_STOCK", "NO_SALES" -> Set.of("CLEARANCE","HOLD");
            default -> Set.of("HOLD","MONITOR");
        };
    }

    private List<Map<String,Object>> suggest(long actor, List<Map<String,Object>> rows) {
        var candidates = new LinkedHashMap<Long,Map<String,Object>>();
        rows.stream().limit(8).forEach(row -> candidates.put(((Number)row.get("product_id")).longValue(),row));
        var proposals = new ArrayList<Map<String,Object>>();
        for (var entry : candidates.entrySet()) {
            var row = entry.getValue();
            proposals.add(Map.of("productId",entry.getKey(),"risk",row.get("risk"),
                    "available",row.get("available"),"sales7",row.get("sales_7"),"sales30",row.get("sales_30"),
                    "allowed",options(row.get("risk").toString())));
        }
        var response = model.chat(actor,"INVENTORY",List.of(
                Map.of("role","system","content","""
                    【商家库存建议】按已计算库存和销量选择建议，库存为零但销量为零时优先观察。
                    仅输出 JSON {"actions":[{"productId":数字,"action":"候选枚举"}]}。
                    从候选列表内每个商品允许的动作选择，不输出理由、额外数字或无关商品。
                    """),Map.of("role","user","content",store.encode(proposals))),List.of());
        try {
            var actions = store.parse(response.path("content").asText()).path("actions");
            if (!actions.isArray() || actions.isEmpty() || actions.size()>candidates.size()) throw new IllegalArgumentException();
            var result = new ArrayList<Map<String,Object>>();
            var seen = new HashSet<Long>();
            for (var action : actions) {
                var id = action.path("productId");
                if (!id.isIntegralNumber() || !id.canConvertToLong() || !seen.add(id.longValue())) throw new IllegalArgumentException();
                var data = candidates.get(id.longValue());
                var value = action.path("action").asText();
                if (data==null || !options(data.get("risk").toString()).contains(value)
                        || (value.equals("REPLENISH") && new BigDecimal(data.get("sales_30").toString()).signum()==0)) {
                    throw new IllegalArgumentException();
                }
                String label = switch (value) {
                    case "REPLENISH" -> "优先核对到货周期和补货量";
                    case "MONITOR" -> "持续观察销量、库存和补货周期";
                    case "CLEARANCE" -> "评估小范围促销或减少后续进货";
                    default -> "维持现有补货计划并定期复查";
                };
                result.add(Map.of("productId",id.longValue(),"label",data.get("label"),"risk",data.get("risk"),
                        "action",value,"advice",label,
                        "basis",Map.of("available",data.get("available"),"sales30",data.get("sales_30"))));
            }
            return result;
        } catch (IllegalArgumentException unsupported) { throw AiProvider.invalidReply(); }
    }
}
