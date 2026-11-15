package com.admin.compat;

import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.TokenRevocationStore;
import com.admin.entity.User;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real on-disk SQLite: start with an old user table, apply the actual schema twice,
 * revoke old/new token keys, reopen the same database, and verify persistence.
 * Artifacts remain under the backend's target/revocation-sqlite-check; no cleanup.
 * Set -Daura.test.backend=/absolute/path/to/springboot-backend when the runner uses an external cwd.
 */
class TokenRevocationSqliteMigrationTest {
    @Test
    void oldDatabaseSchemaAndRevocationsSurviveReopen() throws Exception {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String configuredBackend = System.getProperty("aura.test.backend");
        Path backend = configuredBackend != null && !configuredBackend.isBlank()
                ? Path.of(configuredBackend).toAbsolutePath().normalize()
                : Files.isRegularFile(cwd.resolve("src/main/resources/schema.sql"))
                    ? cwd : cwd.resolve("springboot-backend");
        assertTrue(Files.isDirectory(backend), "aura.test.backend must point to an existing backend directory");
        Path schema = backend.resolve("src/main/resources/schema.sql");
        assertTrue(Files.isRegularFile(schema), "set -Daura.test.backend to the backend directory or run from the project root/backend");
        assertFalse(backend.toRealPath().startsWith(Path.of("/tmp")), "test database must stay outside /tmp");
        assertFalse(backend.toRealPath().startsWith(Path.of("/private/tmp")), "test database must stay outside /private/tmp");
        Path root = backend.resolve("target/revocation-sqlite-check");
        Files.createDirectories(root);
        assertTrue(root.toRealPath().startsWith(backend.toRealPath()), "scratch must remain within the backend checkout");
        Path run = Files.createTempDirectory(root, "run-");
        Path database = run.resolve("legacy.sqlite");
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        DriverManagerDataSource first = source(url);
        JdbcTemplate oldDb = new JdbcTemplate(first);
        oldDb.execute("CREATE TABLE user (id INTEGER PRIMARY KEY, user TEXT NOT NULL, token_version INTEGER NOT NULL, status INTEGER NOT NULL)");
        oldDb.update("INSERT INTO user (id, user, token_version, status) VALUES (?, ?, ?, ?)", 7, "fixture", 4, 1);
        assertEquals(Integer.valueOf(0), oldDb.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='revoked_token'", Integer.class));

        ResourceDatabasePopulator initializer = new ResourceDatabasePopulator(new FileSystemResource(schema));
        initializer.execute(first);
        initializer.execute(first); // Same IF NOT EXISTS schema used by startup; idempotent.
        assertEquals("fixture", oldDb.queryForObject("SELECT user FROM user WHERE id=7", String.class));
        assertEquals(Integer.valueOf(4), oldDb.queryForObject("SELECT token_version FROM user WHERE id=7", Integer.class));

        Object originalSecret = ReflectionTestUtils.getField(JwtUtil.class, "SECRET_KEY");
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", "synthetic-test-key");
        try {
            User user = new User(); user.setId(7L); user.setUser("fixture"); user.setRoleId(1); user.setStatus(1); user.setTokenVersion(4);
            String modern = JwtUtil.generateToken(user);
            String legacy = legacyToken();
            assertTrue(JwtUtil.validateToken(modern));
            assertTrue(JwtUtil.validateToken(legacy));
            assertNull(JwtUtil.getSessionId(legacy));
            String sid = JwtUtil.getSessionId(modern);
            assertEquals(sid, JwtUtil.getRevocationKey(modern), "already-persisted sid format must remain readable");
            String legacyKey = JwtUtil.getRevocationKey(legacy);
            assertTrue(legacyKey.startsWith("L"));
            assertNotEquals(sid, legacyKey);
            TokenRevocationStore firstStore = store(first);
            assertTrue(firstStore.revoke(sid, JwtUtil.getExpireAtMillis(modern)));
            assertTrue(firstStore.revoke(legacyKey, JwtUtil.getExpireAtMillis(legacy)));
            assertTrue(firstStore.revoke(legacyKey, JwtUtil.getExpireAtMillis(legacy)), "replay must be idempotent");
            assertEquals(Integer.valueOf(2), oldDb.queryForObject("SELECT COUNT(*) FROM revoked_token", Integer.class));
            assertEquals(Integer.valueOf(4), oldDb.queryForObject("SELECT token_version FROM user WHERE id=7", Integer.class));

            // A fresh data source obtains fresh connections to the same on-disk DB.
            DriverManagerDataSource reopened = source(url);
            TokenRevocationStore reloaded = store(reopened);
            assertTrue(reloaded.isRevoked(sid));
            assertTrue(reloaded.isRevoked(legacyKey));
            assertFalse(reloaded.isRevoked("unseen-session"));
            initializer.execute(reopened);
            assertTrue(reloaded.isRevoked(legacyKey), "reapplying startup schema must retain revocations");
            assertEquals(Integer.valueOf(4), new JdbcTemplate(reopened).queryForObject("SELECT token_version FROM user WHERE id=7", Integer.class));
        } finally {
            ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", originalSecret);
        }
    }

    private static DriverManagerDataSource source(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.sqlite.JDBC");
        source.setUrl(url);
        return source;
    }

    private static TokenRevocationStore store(DriverManagerDataSource source) {
        TokenRevocationStore result = new TokenRevocationStore();
        ReflectionTestUtils.setField(result, "jdbcTemplate", new JdbcTemplate(source));
        return result;
    }

    private static String legacyToken() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", "7"); payload.put("role_id", 1); payload.put("ver", 4);
        payload.put("iat", System.currentTimeMillis() / 1000);
        payload.put("exp", System.currentTimeMillis() / 1000 + 3600);
        String header = encode("{\"alg\":\"HmacSHA256\",\"typ\":\"JWT\"}");
        String body = encode(JSON.toJSONString(payload));
        String input = header + "." + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("synthetic-test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
