package cn.orderflow;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

final class Db {
    private Db() {}

    static Map<String, Object> one(JdbcTemplate db, String sql, String resource, Object... args) {
        return db.queryForList(sql, args).stream().findFirst().orElseThrow(() -> ApiException.missing(resource));
    }

    static long insert(JdbcTemplate db, String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    record Page(List<Map<String, Object>> rows, Long next) {}

    static Page page(List<Map<String, Object>> rows, int size) {
        Long next = rows.size() > size ? ((Number) rows.get(size - 1).get("id")).longValue() : null;
        return new Page(rows.subList(0, Math.min(size, rows.size())), next);
    }
}
