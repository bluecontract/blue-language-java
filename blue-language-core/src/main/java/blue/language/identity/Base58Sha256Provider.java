package blue.language.identity;

import com.fasterxml.jackson.databind.JsonNode;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;

/**
 * Calculates a Base58-encoded SHA-256 digest of JSON Canonicalization Scheme
 * output.
 *
 * <p>The implementation preserves the historic scalar-wrapping behavior used
 * by BlueId calculation. Digest instances are thread-local and reset between
 * invocations.</p>
 */
public class Base58Sha256Provider implements Function<Object, String> {

    private static final ThreadLocal<MessageDigest> SHA_256 = new ThreadLocal<MessageDigest>() {
        @Override
        protected MessageDigest initialValue() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError("Error calculating SHA-256 hash", e);
            }
        }
    };

    /** Creates a stateless canonical-JSON digest function. */
    public Base58Sha256Provider() {
    }

    /**
     * Returns the compatibility canonical-JSON digest for an object.
     *
     * @param object value to canonicalize and digest
     * @return Base58-encoded SHA-256 digest
     * @throws IllegalArgumentException when the value cannot be serialized
     */
    @Override
    public String apply(Object object) {
        return compatibilityHash(object);
    }

    /**
     * Returns the normative hash for a JSON-compatible canonical value.
     *
     * <p>This entry point lets the focused identity service use the streaming
     * canonical writer while {@link #apply(Object)} retains the wider legacy
     * Jackson-serialization compatibility surface.</p>
     *
     * @param object canonical JSON-compatible value
     * @return Base58-encoded SHA-256 digest
     */
    public String applyCanonicalValue(Object object) {
        if (CanonicalJsonValueWriter.supports(object)) {
            return Base58.encode(sha256Bytes(
                    CanonicalJsonValueWriter.write(object)));
        }
        return compatibilityHash(object);
    }

    private String compatibilityHash(Object object) {
        try {
            byte[] json = JSON_MAPPER.writeValueAsBytes(object);
            requireWellFormedJsonStrings(json);
            byte[] canonical;
            if (object instanceof String || object instanceof Number || object instanceof Boolean || object == null) {
                byte[] wrapped = new byte[json.length + 2];
                wrapped[0] = '[';
                System.arraycopy(json, 0, wrapped, 1, json.length);
                wrapped[wrapped.length - 1] = ']';
                byte[] canonicalWrapped = new JsonCanonicalizer(wrapped).getEncodedUTF8();
                canonical = new byte[canonicalWrapped.length - 2];
                System.arraycopy(canonicalWrapped, 1, canonical, 0, canonical.length);
            } else {
                canonical = new JsonCanonicalizer(json).getEncodedUTF8();
            }
            return Base58.encode(sha256Bytes(canonical));
        } catch (IOException e) {
            throw new IllegalArgumentException("Problem when generating canonized json.");
        }
    }

    static void requireWellFormedJsonStrings(byte[] json) throws IOException {
        requireWellFormedJsonStrings(JSON_MAPPER.readTree(json));
    }

    private static void requireWellFormedJsonStrings(JsonNode value) {
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            requireWellFormedUnicode(value.textValue());
            return;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                requireWellFormedJsonStrings(element);
            }
            return;
        }
        if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                requireWellFormedUnicode(field.getKey());
                requireWellFormedJsonStrings(field.getValue());
            }
        }
    }

    private static void requireWellFormedUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(current)) {
                throw new IllegalArgumentException(
                        "RFC 8785 strings must not contain unpaired UTF-16 surrogates.");
            }
        }
    }

    /**
     * Returns the raw SHA-256 digest of a UTF-8 string.
     *
     * @param input text to digest
     * @return 32-byte SHA-256 digest
     */
    public static byte[] sha256(String input) {
        return sha256Bytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256Bytes(byte[] input) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        try {
            return digest.digest(input);
        } finally {
            digest.reset();
        }
    }

}
