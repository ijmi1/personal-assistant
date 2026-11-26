package assistant.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class Store {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public Store(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public static String id() { return UUID.randomUUID().toString(); }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存业务数据", e);
        }
    }

    public <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取已保存的数据", e);
        }
    }

    public <T> T tx(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    public void atomic(Runnable action) {
        transactions.executeWithoutResult(status -> action.run());
    }

    public void lock(String scope) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?, 0))").param(scope).query().listOfRows();
    }

    public int update(String sql, Object... values) {
        return jdbc.sql(sql).params(Arrays.asList(values)).update();
    }

    public <T> List<T> list(Class<T> type, String sql, Object... values) {
        return jdbc.sql(sql).params(Arrays.asList(values)).query(type).list();
    }

    public <T> Optional<T> one(Class<T> type, String sql, Object... values) {
        return jdbc.sql(sql).params(Arrays.asList(values)).query(type).optional();
    }

    public <T> T keyed(String owner, String operation, String key, Object input, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw Problem.invalid("需要有效的 Idempotency-Key");
        }
        String fingerprint = hash(write(input).getBytes(StandardCharsets.UTF_8));
        return tx(() -> {
            lock(write(List.of(owner, operation, key)));
            var existing = one(KeyRecord.class, """
                    select payload_hash, response from request_keys where owner=? and operation=? and key=?
                    """, owner, operation, key);
            if (existing.isPresent()) {
                if (!fingerprint.equals(existing.get().payloadHash())) {
                    throw Problem.conflict("请求键已用于不同的输入");
                }
                return read(existing.get().response(), type);
            }
            T response = action.get();
            update("insert into request_keys values (?,?,?,?,?)", owner, operation, key, fingerprint, write(response));
            return response;
        });
    }

    public record KeyRecord(String payloadHash, String response) {}
}
