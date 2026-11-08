package com.jobbeacon.recommendation.cdc;

/** Splits a {@code jdbc:postgresql://host:port/db?params} URL into the parts Debezium takes separately. */
final class JdbcUrls {

    private JdbcUrls() {
    }

    static String databaseName(String jdbcUrl) {
        String withoutQuery = jdbcUrl.split("\\?")[0];
        return withoutQuery.substring(withoutQuery.lastIndexOf('/') + 1);
    }

    static String host(String jdbcUrl) {
        return hostPort(jdbcUrl).split(":")[0];
    }

    static String port(String jdbcUrl) {
        String[] parts = hostPort(jdbcUrl).split(":");
        return parts.length > 1 ? parts[1] : "5432";
    }

    private static String hostPort(String jdbcUrl) {
        String body = jdbcUrl.substring(jdbcUrl.indexOf("://") + 3);
        return body.substring(0, body.indexOf('/'));
    }
}
