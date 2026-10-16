package com.idraak.smartbite.v2.db;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns {@code DATABASE_URL} into JDBC settings. Accepts Render's connection string as-is
 * ({@code postgresql://user:password@host:port/database?params}, also {@code postgres://}) or a JDBC URL
 * ({@code jdbc:postgresql://…}) with the user and password given separately. Values from the URL win over the
 * separate ones only when those are blank.
 */
public record JdbcSettings(String jdbcUrl, String username, String password) {

    public static JdbcSettings from(String url, String username, String password) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("DATABASE_URL is empty");
        String u = url.trim();
        if (u.startsWith("jdbc:")) return new JdbcSettings(u, username, password);
        if (!u.startsWith("postgresql://") && !u.startsWith("postgres://")) {
            throw new IllegalArgumentException("DATABASE_URL must start with postgresql://, postgres:// or jdbc:");
        }
        URI uri = URI.create(u.replaceFirst("^postgres(ql)?://", "http://"));
        if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
            throw new IllegalArgumentException("DATABASE_URL needs a host and a database name");
        }
        String user = username;
        String pass = password;
        if (uri.getRawUserInfo() != null) {
            String[] parts = uri.getRawUserInfo().split(":", 2);
            if (blank(user)) user = decode(parts[0]);
            if (blank(pass) && parts.length == 2) pass = decode(parts[1]);
        }
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return new JdbcSettings("jdbc:postgresql://" + uri.getHost() + port + uri.getRawPath() + query, user, pass);
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "JdbcSettings[" + jdbcUrl + ", user=" + username + "]";
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
