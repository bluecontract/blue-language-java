package blue.lang.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;

public class Base58Sha256Provider implements Function<Object, String> {

    @Override
    public String apply(Object object) {
        String canonized = JsonCanonicalizer.canonicalize(object);
        byte[] hash = sha256(canonized);
        return Base58.encode(hash);
    }

    public byte[] sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Error calculating SHA-256 hash", e);
        }
    }

}