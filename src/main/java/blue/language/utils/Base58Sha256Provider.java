package blue.language.utils;

import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

public class Base58Sha256Provider implements Function<Object, String> {

    @Override
    public String apply(Object object) {

        String canonized = null;
        try {
            String json = JSON_MAPPER.writeValueAsString(object);
            try {
                canonized = new JsonCanonicalizer(json).getEncodedString();
            } catch (IOException e) {
                if (object instanceof String || object instanceof Number || object instanceof Boolean || object == null) {
                    String wrapped = new JsonCanonicalizer("[" + json + "]").getEncodedString();
                    canonized = wrapped.substring(1, wrapped.length() - 1);
                } else {
                    throw e;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Problem when generating canonized json.");
        }
        byte[] hash = sha256(canonized);
        return Base58.encode(hash);
    }

    public static byte[] sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Error calculating SHA-256 hash", e);
        }
    }

}
