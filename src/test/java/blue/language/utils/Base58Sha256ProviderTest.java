package blue.language.utils;

import org.junit.jupiter.api.Test;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

class Base58Sha256ProviderTest {

    @Test
    void sha256MatchesPublishedVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hexadecimal(Base58Sha256Provider.sha256("")));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                hexadecimal(Base58Sha256Provider.sha256("abc")));
    }

    @Test
    void repeatedAndAlternatingInputsDoNotLeakDigestState() {
        String[] inputs = {"", "abc", "Blue", "zażółć gęślą jaźń", "\uD83D\uDE80"};
        for (int round = 0; round < 1_000; round++) {
            for (String input : inputs) {
                assertArrayEquals(independentSha256(input), Base58Sha256Provider.sha256(input));
            }
        }
    }

    @Test
    void failedCallDoesNotPoisonTheThreadLocalDigest() {
        byte[] expected = independentSha256("after failure");

        assertThrows(NullPointerException.class, () -> Base58Sha256Provider.sha256(null));

        assertArrayEquals(expected, Base58Sha256Provider.sha256("after failure"));
    }

    @Test
    void threadLocalDigestsAreIsolatedAcrossConcurrentCallers() throws Exception {
        int threadCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int thread = 0; thread < threadCount; thread++) {
                final int worker = thread;
                work.add(() -> {
                    MessageDigest oracle = newSha256();
                    for (int iteration = 0; iteration < 2_000; iteration++) {
                        String input = "worker-" + worker + "-iteration-" + iteration
                                + "-" + (char) ('a' + iteration % 26);
                        byte[] expected = oracle.digest(input.getBytes(StandardCharsets.UTF_8));
                        byte[] actual = Base58Sha256Provider.sha256(input);
                        if (!Arrays.equals(expected, actual)) {
                            throw new AssertionError("Digest mismatch for " + input);
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> results = executor.invokeAll(work);
            for (Future<Void> result : results) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void canonicalHashProviderRemainsDeterministicAcrossCalls() {
        Base58Sha256Provider provider = new Base58Sha256Provider();
        String first = provider.apply(Arrays.asList("alpha", 2, true));

        provider.apply("unrelated");

        assertEquals(first, provider.apply(Arrays.asList("alpha", 2, true)));
    }

    @Test
    void byteJcsPipelineMatchesLegacyStringPipelineForGeneratedIdentityCorpus() {
        Base58Sha256Provider provider = new Base58Sha256Provider();
        Random random = new Random(0x4A435342595445L);
        for (int index = 0; index < 100_000; index++) {
            Object value = identityValue(random, index);
            String expected = legacyStringPipeline(value);
            String actual = provider.apply(value);
            if (!expected.equals(actual)) {
                fail("Canonical byte pipeline mismatch at deterministic case " + index
                        + " value=" + value + " expected=" + expected + " actual=" + actual);
            }
        }
    }

    private static byte[] independentSha256(String input) {
        return newSha256().digest(input.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object identityValue(Random random, int index) {
        switch (index % 8) {
            case 0:
                return "text-" + index + "-" + (char) 0 + "-zażółć-\uD83D\uDE80-"
                        + (char) random.nextInt(0x80);
            case 1:
                return BigInteger.valueOf(random.nextLong() & 0x1FFFFFFFFFFFFFL);
            case 2:
                return BigDecimal.valueOf((random.nextDouble() - 0.5d) * 1_000_000d);
            case 3:
                return (index & 1) == 0;
            case 4:
                return Arrays.asList("a/" + index, index, index % 3 == 0);
            case 5: {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("z", index);
                map.put("a", "value-" + random.nextInt());
                map.put("escaped\nkey", Arrays.asList(index % 7, String.valueOf((char) 0x2028)));
                return map;
            }
            case 6: {
                Map<String, Object> nested = new LinkedHashMap<>();
                nested.put("β", BigDecimal.valueOf(index, index % 5));
                nested.put("alpha", Arrays.asList("x", "y", index));
                return Arrays.asList(nested, "tail");
            }
            default:
                return null;
        }
    }

    private static String legacyStringPipeline(Object object) {
        try {
            String json = JSON_MAPPER.writeValueAsString(object);
            String canonical;
            try {
                canonical = new JsonCanonicalizer(json).getEncodedString();
            } catch (IOException exception) {
                if (object instanceof String || object instanceof Number
                        || object instanceof Boolean || object == null) {
                    String wrapped = new JsonCanonicalizer("[" + json + "]").getEncodedString();
                    canonical = wrapped.substring(1, wrapped.length() - 1);
                } else {
                    throw exception;
                }
            }
            return Base58.encode(newSha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Problem when generating canonized json.");
        }
    }

    private static String hexadecimal(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xFF));
        }
        return result.toString();
    }
}
