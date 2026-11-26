package cn.orderflow;

import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Configuration
class AiReadConfiguration {
    @Bean
    HikariDataSource aiPrimaryReadSource(@Value("${spring.datasource.url}") String url,
                                       @Value("${DB_INSPECT_PASSWORD}") String password) {
        var source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername("of_inspect");
        source.setPassword(password);
        source.setReadOnly(true);
        source.setMaximumPoolSize(2);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(2000);
        source.setPoolName("orderflow-ai-primary-read");
        return source;
    }
}

@Component
class AiSql {
    final JdbcTemplate primary;
    private final JdbcTemplate replica;

    AiSql(@Qualifier("aiPrimaryReadSource") DataSource primary, @Qualifier("replicaDataSource") DataSource replica) {
        this.primary = new JdbcTemplate(primary);
        this.replica = new JdbcTemplate(replica);
        this.primary.setQueryTimeout(5);
        this.replica.setQueryTimeout(5);
    }

    List<Map<String,Object>> read(boolean live, String statement, Object... args) {
        if (live) return primary.queryForList(statement, args);
        var states = replica.queryForList("SHOW REPLICA STATUS");
        if (!states.isEmpty()) {
            var state = states.get(0);
            if ("Yes".equals(state.get("Replica_IO_Running")) && "Yes".equals(state.get("Replica_SQL_Running"))
                    && state.get("Seconds_Behind_Source") instanceof Number lag && lag.longValue() <= 60) {
                return replica.queryForList(statement, args);
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "从库不可用或延迟超过 60 秒");
    }
}
