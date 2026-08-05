package blue.language.identity;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;

/** Fresh-JVM probe because the shared mapper is intentionally process-global and mutable. */
public final class Base58Sha256ProviderMapperCustomizationProbe {

    private Base58Sha256ProviderMapperCustomizationProbe() {
    }

    public static void main(String[] args) throws Exception {
        SimpleModule module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value,
                                  JsonGenerator generator,
                                  SerializerProvider serializers) throws IOException {
                generator.writeString("decimal:" + value.toPlainString());
            }
        });
        JSON_MAPPER.registerModule(module);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("amount", new BigDecimal("12.50"));
        String expected = legacyHash(value);
        String actual = new Base58Sha256Provider().apply(value);
        if (!expected.equals(actual)) {
            throw new AssertionError("Public provider bypassed configured mapper: expected="
                    + expected + " actual=" + actual);
        }
    }

    private static String legacyHash(Object value) throws IOException {
        byte[] json = JSON_MAPPER.writeValueAsBytes(value);
        byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
        return Base58.encode(sha256(canonical));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
