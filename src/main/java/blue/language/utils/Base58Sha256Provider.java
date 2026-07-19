package blue.language.utils;

import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

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

    @Override
    public String apply(Object object) {
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
