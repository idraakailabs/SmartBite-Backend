package com.idraak.smartbite.v2.db;

import com.idraak.smartbite.v2.config.V2Properties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Our own PostgreSQL (D-024), only when {@code DATABASE_URL} is set. Schema changes are Flyway migrations in
 * {@code src/main/resources/db/migration}, applied at startup before the service accepts traffic. If the database
 * can't be reached or a migration fails, startup fails, and Render keeps the previous deploy serving.
 */
@Configuration
@ConditionalOnExpression("!'${smartbite.v2.database.url:}'.isBlank()")
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean(destroyMethod = "close")
    @DependsOn("deployedConfigGuard")
    public HikariDataSource dataSource(V2Properties props) {
        V2Properties.Database db = props.database();
        JdbcSettings jdbc = JdbcSettings.from(db.url(), db.username(), db.password());
        HikariConfig config = new HikariConfig();
        config.setPoolName("smartbite-db");
        config.setJdbcUrl(jdbc.jdbcUrl());
        config.setUsername(jdbc.username());
        config.setPassword(jdbc.password());
        config.setMaximumPoolSize(Math.max(1, db.maxPoolSize()));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(db.connectTimeout().toMillis());
        // A runaway query can't hold a connection for long.
        config.setConnectionInitSql("SET statement_timeout = " + db.statementTimeout().toMillis());
        HikariDataSource ds = new HikariDataSource(config);
        Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        var result = flyway.migrate();
        // targetSchemaVersion is null when nothing was applied (every restart), so read the version from the database.
        var current = flyway.info().current();
        log.info("database ready; migrations applied={} schema_version={}", result.migrationsExecuted,
                current == null ? "none" : current.getVersion());
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
