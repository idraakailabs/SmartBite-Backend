package com.idraak.smartbite.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A real PostgreSQL for tests, started once per test JVM (zonky embedded-postgres; no Docker needed). Tests share
 * it, so each test uses its own uids or collection names.
 */
public final class TestDatabase {

    private static EmbeddedPostgres postgres;

    private TestDatabase() {
    }

    /** A JDBC URL with user and password, the form {@code DATABASE_URL} also accepts. */
    public static synchronized String url() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("embedded PostgreSQL failed to start", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // the JVM is exiting
                }
            }));
        }
        return postgres.getJdbcUrl("postgres", "postgres");
    }
}
