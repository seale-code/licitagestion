package pe.licitagestion.persistence;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Repository
public class Store {
    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, SimpleJdbcInsert> insertCache = new ConcurrentHashMap<>();

    public Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public List<Map<String, Object>> page(String sql, int page, int size, Object... args) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        long offset = (long) safePage * safeSize;
        return list(sql + " LIMIT " + safeSize + " OFFSET " + offset, args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = list(sql, args);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso no disponible");
        }
        return rows.getFirst();
    }

    public Optional<Map<String, Object>> optional(String sql, Object... args) {
        return list(sql, args).stream().findFirst();
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public long insert(String table, Map<String, Object> fields) {
        // PostgreSQL metadata may report timestamptz as TIMESTAMP. Override that
        // hint so OffsetDateTime is bound as JDBC 4.2 TIMESTAMP_WITH_TIMEZONE,
        // preserving its instant instead of asking the driver to parse a string.
        var typed = new LinkedHashMap<String, Object>();
        fields.forEach((key, value) -> typed.put(key, value instanceof java.time.OffsetDateTime
            ? new SqlParameterValue(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, value) : value));

        String cacheKey = table + ":" + String.join(",", fields.keySet().stream().sorted().toList());
        SimpleJdbcInsert insert = insertCache.computeIfAbsent(cacheKey, k ->
            new SimpleJdbcInsert(jdbc)
                .withTableName(table)
                .usingGeneratedKeyColumns("id")
                .usingColumns(fields.keySet().toArray(String[]::new))
        );
        return insert.executeAndReturnKey(typed).longValue();
    }

    public long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    public static Map<String, Object> fields(Object... entries) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}

