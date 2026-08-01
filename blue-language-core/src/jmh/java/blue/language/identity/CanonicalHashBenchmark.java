package blue.language.identity;

import org.erdtman.jcs.JsonCanonicalizer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

/** Compares equivalent RFC 8785 hash pipelines over a nested identity helper map. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CanonicalHashBenchmark {

    private static final ThreadLocal<MessageDigest> LEGACY_SHA_256 = new ThreadLocal<MessageDigest>() {
        @Override
        protected MessageDigest initialValue() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }
    };

    private final Base58Sha256Provider provider = new Base58Sha256Provider();
    private Object identityValue;
    private String expected;

    @Setup(Level.Trial)
    public void setUp() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (int index = 0; index < 32; index++) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("blueId", "field-" + index + "-123456789ABCDEFGHJKLMNPQRSTUVWXYZ");
            field.put("metadata", new ArrayList<Object>(Arrays.<Object>asList("value-" + index,
                    BigDecimal.valueOf(index, index % 4), index % 2 == 0)));
            root.put("field-" + index, field);
        }
        identityValue = root;
        expected = legacyJacksonJcsHash();
        if (!expected.equals(provider.applyCanonicalValue(identityValue))) {
            throw new IllegalStateException("Canonical hash implementations disagree");
        }
    }

    @Benchmark
    public String optimizedCanonicalHash() {
        return provider.applyCanonicalValue(identityValue);
    }

    @Benchmark
    public String legacyJacksonJcsHash() {
        try {
            byte[] json = JSON_MAPPER.writeValueAsBytes(identityValue);
            byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
            return Base58.encode(sha256(canonical));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] sha256(byte[] input) {
        MessageDigest digest = LEGACY_SHA_256.get();
        digest.reset();
        try {
            return digest.digest(input);
        } finally {
            digest.reset();
        }
    }
}
