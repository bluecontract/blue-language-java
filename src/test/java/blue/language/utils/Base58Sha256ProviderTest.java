package blue.language.utils;

import blue.language.snapshot.FrozenCanonicalWriter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void optimizedCanonicalWriterMatchesLegacyStringPipelineForGeneratedIdentityCorpus() {
        Base58Sha256Provider provider = new Base58Sha256Provider();
        Random random = new Random(0x4A435342595445L);
        for (int index = 0; index < 100_000; index++) {
            Object value = identityValue(random, index);
            String expected = legacyStringPipeline(value);
            String actual = provider.applyCanonicalValue(value);
            if (!expected.equals(actual)) {
                fail("Canonical byte pipeline mismatch at deterministic case " + index
                        + " value=" + value + " expected=" + expected + " actual=" + actual);
            }
        }
    }

    @Test
    void unsupportedJacksonValuesRetainTheCompatibilityHashPath() {
        Base58Sha256Provider provider = new Base58Sha256Provider();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("subject", AnnotatedWireValue.SUBJECT);

        assertEquals(legacyStringPipeline(value), provider.applyCanonicalValue(value));
    }

    @Test
    void plainCanonicalHelperMapsUseTheCompatibleOptimizedPath() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("subject", arrayList("entry", BigDecimal.valueOf(125, 2), true));
        Map<String, Object> folded = new TreeMap<>();
        folded.put("elem", Collections.singletonMap("blueId", "element-id"));
        folded.put("prev", Collections.singletonMap("blueId", "previous-id"));
        value.put("folded", folded);

        assertTrue(FrozenCanonicalWriter.supportsCanonicalValue(value));
        assertEquals(legacyStringPipeline(value), new Base58Sha256Provider().applyCanonicalValue(value));
    }

    @Test
    void jacksonCustomizedContainerAndNumberSubclassesRetainCompatibilityPath() {
        Base58Sha256Provider provider = new Base58Sha256Provider();
        for (Object customized : Arrays.<Object>asList(
                new AnnotatedWireList(),
                new AnnotatedWireMap(),
                new AnnotatedBigDecimal())) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("subject", customized);

            assertFalse(FrozenCanonicalWriter.supportsCanonicalValue(value));
            assertEquals(legacyStringPipeline(value), provider.applyCanonicalValue(value));
        }
    }

    @Test
    void duplicateSerializedMapKeysRetainLegacyRejection() {
        IdentityHashMap<String, Object> ambiguous = new IdentityHashMap<>();
        ambiguous.put(new String("duplicate"), "first");
        ambiguous.put(new String("duplicate"), "second");

        assertFalse(FrozenCanonicalWriter.supportsCanonicalValue(ambiguous));
        assertThrows(IllegalArgumentException.class, () -> legacyStringPipeline(ambiguous));
        assertThrows(IllegalArgumentException.class,
                () -> new Base58Sha256Provider().applyCanonicalValue(ambiguous));
    }

    @Test
    void comparatorDistinctDuplicateTextualKeysRetainLegacyRejection() {
        Comparator<String> identityOrder = new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                if (left == right) return 0;
                int compared = Integer.compare(System.identityHashCode(left), System.identityHashCode(right));
                return compared != 0 ? compared : 1;
            }
        };
        Map<String, Object> ambiguous = new TreeMap<>(identityOrder);
        ambiguous.put(new String("duplicate"), "first");
        ambiguous.put(new String("duplicate"), "second");

        assertEquals(2, ambiguous.size());
        assertFalse(FrozenCanonicalWriter.supportsCanonicalValue(ambiguous));
        assertThrows(IllegalArgumentException.class, () -> legacyStringPipeline(ambiguous));
        assertThrows(IllegalArgumentException.class,
                () -> new Base58Sha256Provider().applyCanonicalValue(ambiguous));
    }

    @Test
    void topLevelCharacterRetainsLegacyRejection() {
        Character value = Character.valueOf('a');

        assertFalse(FrozenCanonicalWriter.supportsCanonicalValue(value));
        assertThrows(IllegalArgumentException.class, () -> legacyStringPipeline(value));
        assertThrows(IllegalArgumentException.class,
                () -> new Base58Sha256Provider().applyCanonicalValue(value));
    }

    @Test
    void linkedAndCyclicListsAreExcludedFromTheOptimizedPath() {
        List<Object> linked = new LinkedList<>();
        linked.add("entry");
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        assertFalse(FrozenCanonicalWriter.supportsCanonicalValue(linked));
        assertEquals(legacyStringPipeline(linked),
                new Base58Sha256Provider().applyCanonicalValue(linked));
        assertFalse(FrozenCanonicalWriter.supportsCanonicalValue(cyclic));
    }

    @Test
    void optimizedHashingDoesNotMutateAccessOrderedMaps() {
        Map<String, Object> value = new LinkedHashMap<>(16, 0.75f, true);
        value.put("z", 1);
        value.put("a", 2);
        value.put("m", 3);
        List<String> before = new ArrayList<>(value.keySet());

        assertTrue(FrozenCanonicalWriter.supportsCanonicalValue(value));
        assertEquals(legacyStringPipeline(value),
                new Base58Sha256Provider().applyCanonicalValue(value));
        assertEquals(before, new ArrayList<>(value.keySet()));
    }

    @Test
    void publicProviderRetainsMapperCustomizationCompatibility() throws Exception {
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java")
                .getAbsolutePath();
        Process process = new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                Base58Sha256ProviderMapperCustomizationProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("Mapper customization compatibility probe timed out");
        }
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        assertEquals(0, process.exitValue(), output.toString());
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
                return arrayList("a/" + index, index, index % 3 == 0);
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
                nested.put("alpha", arrayList("x", "y", index));
                return arrayList(nested, "tail");
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

    private static List<Object> arrayList(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private enum AnnotatedWireValue {
        @JsonProperty("wire-subject")
        SUBJECT
    }

    private static final class AnnotatedWireList extends ArrayList<Object> {
        private AnnotatedWireList() {
            add("entry");
        }

        @JsonValue
        String wireValue() {
            return "wire-list";
        }
    }

    private static final class AnnotatedWireMap extends LinkedHashMap<String, Object> {
        private AnnotatedWireMap() {
            put("entry", true);
        }

        @JsonValue
        String wireValue() {
            return "wire-map";
        }
    }

    private static final class AnnotatedBigDecimal extends BigDecimal {
        private AnnotatedBigDecimal() {
            super("1.25");
        }

        @JsonValue
        String wireValue() {
            return "wire-decimal";
        }
    }
}
