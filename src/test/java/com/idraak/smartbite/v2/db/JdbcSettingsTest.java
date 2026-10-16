package com.idraak.smartbite.v2.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSettingsTest {

    @Test
    void rendersConnectionStringIsAcceptedAsIs() {
        JdbcSettings s = JdbcSettings.from("postgresql://smartbite:p%40ss:word@dpg-abc123-a/smartbite", "", "");
        assertThat(s.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc123-a/smartbite");
        assertThat(s.username()).isEqualTo("smartbite");
        assertThat(s.password()).isEqualTo("p@ss:word");
    }

    @Test
    void portAndQueryAreKept() {
        JdbcSettings s = JdbcSettings.from("postgres://u:p@db.example.com:6543/app?sslmode=require", null, null);
        assertThat(s.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:6543/app?sslmode=require");
    }

    @Test
    void aJdbcUrlUsesTheSeparateCredentials() {
        JdbcSettings s = JdbcSettings.from("jdbc:postgresql://localhost:5432/smartbite", "user", "secret");
        assertThat(s.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/smartbite");
        assertThat(s.username()).isEqualTo("user");
        assertThat(s.password()).isEqualTo("secret");
    }

    @Test
    void theStringFormNeverShowsThePassword() {
        assertThat(JdbcSettings.from("postgresql://u:topsecret@h/db", "", "").toString()).doesNotContain("topsecret");
    }

    @Test
    void otherSchemesAndMissingPartsAreRejected() {
        assertThatThrownBy(() -> JdbcSettings.from("mysql://u:p@h/db", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcSettings.from("postgresql://u:p@h", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcSettings.from(" ", "", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
