package cn.orderflow;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration
class DatabaseConfig {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties primaryProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource primaryDataSource(@Qualifier("primaryProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    HikariDataSource replicaDataSource(
            @Value("${orderflow.replica.url}") String url,
            @Value("${orderflow.replica.username}") String username,
            @Value("${orderflow.replica.password}") String password) {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        source.setReadOnly(true);
        source.setPoolName("orderflow-replica");
        source.setMaximumPoolSize(5);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(2500);
        return source;
    }

    @Bean
    @Primary
    JdbcTemplate primaryJdbc(@Qualifier("primaryDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    JdbcTemplate replicaJdbc(@Qualifier("replicaDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    JdbcTransactionManager transactionManager(@Qualifier("primaryDataSource") DataSource source) {
        return new JdbcTransactionManager(source);
    }
}
