package blue.language.identity;

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

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

class Base58Sha256ProviderTest {

    @Test
    void shouldMatchPublishedSha256Vectors() {
        // given
        String emptyExpected =
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String abcExpected =
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        // when
        String emptyActual = hexadecimal(Base58Sha256Provider.sha256(""));
        String abcActual = hexadecimal(Base58Sha256Provider.sha256("abc"));

        // then
        assertEquals(emptyExpected, emptyActual);
        assertEquals(abcExpected, abcActual);
    }

    @Test
    void shouldNotLeakDigestStateAcrossRepeatedAndAlternatingInputs() {
        // given
        String[] inputs = {"", "abc", "Blue", "zażółć gęślą jaźń", "\uD83D\uDE80"};

        // when
        boolean allMatched = true;
        for (int round = 0; round < 1_000; round++) {
            for (String input : inputs) {
                allMatched &= Arrays.equals(
                        independentSha256(input),
                        Base58Sha256Provider.sha256(input));
            }
        }

        // then
        assertTrue(allMatched);
    }

    @Test
    void shouldNotPoisonThreadLocalDigestAfterFailedCall() {
        // given
        byte[] expected = independentSha256("after failure");

        // when
        NullPointerException failure =
                captureFailure(() -> Base58Sha256Provider.sha256(null));
        byte[] actual = Base58Sha256Provider.sha256("after failure");

        // then
        assertTrue(failure instanceof NullPointerException);
        assertArrayEquals(expected, actual);
    }

    @Test
    void shouldIsolateThreadLocalDigestsAcrossConcurrentCallers() throws Exception {
        // given
        int threadCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // when
        int completedTasks = 0;
        boolean terminated;
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
                completedTasks++;
            }
        } finally {
            executor.shutdownNow();
            terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        // then
        assertEquals(threadCount, completedTasks);
        assertTrue(terminated);
    }

    @Test
    void shouldKeepCanonicalHashProviderDeterministicAcrossCalls() {
        // given
        Base58Sha256Provider provider = new Base58Sha256Provider();
        String first = provider.apply(Arrays.asList("alpha", 2, true));

        // when
        provider.apply("unrelated");
        String repeated = provider.apply(Arrays.asList("alpha", 2, true));

        // then
        assertEquals(first, repeated);
    }

    @Test
    void shouldMatchLegacyStringPipelineWithOptimizedWriterForGeneratedIdentityCorpus() {
        // given
        Base58Sha256Provider provider = new Base58Sha256Provider();
        Random random = new Random(0x4A435342595445L);

        // when
        String mismatch = null;
        for (int index = 0; index < 100_000; index++) {
            Object value = identityValue(random, index);
            String expected = legacyStringPipeline(value);
            String actual = provider.applyCanonicalValue(value);
            if (!expected.equals(actual)) {
                mismatch = "Canonical byte pipeline mismatch at deterministic case " + index
                        + " value=" + value + " expected=" + expected + " actual=" + actual;
                break;
            }
        }

        // then
        assertNull(mismatch, mismatch);
    }

    @Test
    void shouldRetainCompatibilityHashPathForUnsupportedJacksonValues() {
        // given
        Base58Sha256Provider provider = new Base58Sha256Provider();
        Map<String, Object> value = new LinkedHashMap<>();
        // when
        value.put("subject", AnnotatedWireValue.SUBJECT);
        String expected = legacyStringPipeline(value);
        String actual = provider.applyCanonicalValue(value);

        // then
        assertEquals(expected, actual);
    }

    @Test
    void shouldUseCompatibleOptimizedPathForPlainCanonicalHelperMaps() {
        // given
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("subject", arrayList("entry", BigDecimal.valueOf(125, 2), true));
        Map<String, Object> folded = new TreeMap<>();
        folded.put("elem", Collections.singletonMap("blueId", "element-id"));
        folded.put("prev", Collections.singletonMap("blueId", "previous-id"));
        // when
        value.put("folded", folded);
        boolean supported = CanonicalJsonValueWriter.supports(value);
        String expected = legacyStringPipeline(value);
        String actual = new Base58Sha256Provider().applyCanonicalValue(value);

        // then
        assertTrue(supported);
        assertEquals(expected, actual);
    }

    @Test
    void shouldRetainCompatibilityPathForJacksonCustomizedContainersAndNumbers() {
        // given
        Base58Sha256Provider provider = new Base58Sha256Provider();

        // when
        boolean allCompatible = true;
        for (Object customized : Arrays.<Object>asList(
                new AnnotatedWireList(),
                new AnnotatedWireMap(),
                new AnnotatedBigDecimal())) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("subject", customized);
            allCompatible &= !CanonicalJsonValueWriter.supports(value);
            allCompatible &= legacyStringPipeline(value)
                    .equals(provider.applyCanonicalValue(value));
        }

        // then
        assertTrue(allCompatible);
    }

    @Test
    void shouldRetainLegacyRejectionForDuplicateSerializedMapKeys() {
        // given
        IdentityHashMap<String, Object> ambiguous = new IdentityHashMap<>();
        ambiguous.put(new String("duplicate"), "first");
        // when
        ambiguous.put(new String("duplicate"), "second");
        boolean supported = CanonicalJsonValueWriter.supports(ambiguous);
        IllegalArgumentException legacyFailure =
                captureFailure(() -> legacyStringPipeline(ambiguous));
        IllegalArgumentException optimizedFailure = captureFailure(
                () -> new Base58Sha256Provider().applyCanonicalValue(ambiguous));

        // then
        assertFalse(supported);
        assertTrue(legacyFailure instanceof IllegalArgumentException);
        assertTrue(optimizedFailure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRetainLegacyRejectionForComparatorDistinctDuplicateTextualKeys() {
        // given
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
        // when
        ambiguous.put(new String("duplicate"), "second");
        int size = ambiguous.size();
        boolean supported = CanonicalJsonValueWriter.supports(ambiguous);
        IllegalArgumentException legacyFailure =
                captureFailure(() -> legacyStringPipeline(ambiguous));
        IllegalArgumentException optimizedFailure = captureFailure(
                () -> new Base58Sha256Provider().applyCanonicalValue(ambiguous));

        // then
        assertEquals(2, size);
        assertFalse(supported);
        assertTrue(legacyFailure instanceof IllegalArgumentException);
        assertTrue(optimizedFailure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRetainLegacyRejectionForTopLevelCharacter() {
        // given
        Character value = Character.valueOf('a');

        // when
        boolean supported = CanonicalJsonValueWriter.supports(value);
        IllegalArgumentException legacyFailure =
                captureFailure(() -> legacyStringPipeline(value));
        IllegalArgumentException optimizedFailure = captureFailure(
                () -> new Base58Sha256Provider().applyCanonicalValue(value));

        // then
        assertFalse(supported);
        assertTrue(legacyFailure instanceof IllegalArgumentException);
        assertTrue(optimizedFailure instanceof IllegalArgumentException);
    }

    @Test
    void shouldExcludeLinkedAndCyclicListsFromOptimizedPath() {
        // given
        List<Object> linked = new LinkedList<>();
        linked.add("entry");
        List<Object> cyclic = new ArrayList<>();
        // when
        cyclic.add(cyclic);
        boolean linkedSupported =
                CanonicalJsonValueWriter.supports(linked);
        String linkedExpected = legacyStringPipeline(linked);
        String linkedActual =
                new Base58Sha256Provider().applyCanonicalValue(linked);
        boolean cyclicSupported =
                CanonicalJsonValueWriter.supports(cyclic);

        // then
        assertFalse(linkedSupported);
        assertEquals(linkedExpected, linkedActual);
        assertFalse(cyclicSupported);
    }

    @Test
    void shouldNotMutateAccessOrderedMapsDuringOptimizedHashing() {
        // given
        Map<String, Object> value = new LinkedHashMap<>(16, 0.75f, true);
        value.put("z", 1);
        value.put("a", 2);
        value.put("m", 3);
        // when
        List<String> before = new ArrayList<>(value.keySet());
        boolean supported = CanonicalJsonValueWriter.supports(value);
        String expected = legacyStringPipeline(value);
        String actual = new Base58Sha256Provider().applyCanonicalValue(value);
        List<String> after = new ArrayList<>(value.keySet());

        // then
        assertTrue(supported);
        assertEquals(expected, actual);
        assertEquals(before, after);
    }

    @Test
    void shouldRetainMapperCustomizationCompatibilityInPublicProvider() throws Exception {
        // given
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java")
                .getAbsolutePath();
        ProcessBuilder processBuilder = new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                Base58Sha256ProviderMapperCustomizationProbe.class.getName())
                .redirectErrorStream(true);

        // when
        Process process = processBuilder.start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exitValue = process.isAlive() ? -1 : process.exitValue();

        // then
        assertTrue(exited, "Mapper customization compatibility probe timed out");
        assertEquals(0, exitValue, output.toString());
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
