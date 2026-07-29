package blue.language.utils;

import blue.language.snapshot.FrozenCanonicalWriter;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

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

    String applyCanonicalValue(Object object) {
        if (FrozenCanonicalWriter.supportsCanonicalValue(object)) {
            return Base58.encode(sha256Bytes(FrozenCanonicalWriter.canonicalValueBytes(object)));
        }
        return compatibilityHash(object);
    }

    private String compatibilityHash(Object object) {
        try {
            byte[] json = JSON_MAPPER.writeValueAsBytes(object);
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
