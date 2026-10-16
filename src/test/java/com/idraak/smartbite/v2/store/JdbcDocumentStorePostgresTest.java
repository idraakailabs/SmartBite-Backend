package com.idraak.smartbite.v2.store;

import com.idraak.smartbite.testsupport.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** {@link JdbcDocumentStore} on a real PostgreSQL, against the same contract as Firestore and the in-memory store. */
class JdbcDocumentStorePostgresTest extends DocumentStoreContract {

    private static HikariDataSource dataSource;
    private static DocumentStore store;

    private static synchronized DocumentStore shared() {
        if (store == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(TestDatabase.url());
            config.setMaximumPoolSize(10);
            config.setConnectionInitSql("SET statement_timeout = 10000");
            dataSource = new HikariDataSource(config);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            store = new JdbcDocumentStore(new JdbcTemplate(dataSource),
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)), JsonMapper.builder().build(), "test_");
        }
        return store;
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    @Override
    DocumentStore store() {
        return shared();
    }
}
