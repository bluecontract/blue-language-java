package blue.language.identity;

import blue.language.identity.Base58;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static blue.language.identity.BlueIds.isPotentialBlueId;
import static org.junit.jupiter.api.Assertions.*;

class BlueIdsTest {

    private static final int SHA_256_BYTE_COUNT = 32;
    private static final int GENERATED_CORPUS_SIZE = 1_024;
    private static final long GENERATED_CORPUS_SEED = 0xB10E_1D5L;

    @Test
    void shouldRecognizePotentialBlueIds() {
        // given
        String[] validCandidates = {
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#12"
        };
        String[] invalidCandidates = {
                null,
                "",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzr",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7A",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#01",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#-1",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#abc",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#12#34",
                "0Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7O",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7I",
                "4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7l"
        };

        // when
        boolean[] validResults = classify(validCandidates);
        boolean[] invalidResults = classify(invalidCandidates);

        // then
        for (boolean result : validResults) {
            assertTrue(result);
        }
        for (boolean result : invalidResults) {
            assertFalse(result);
        }
    }

    @Test
    void shouldAcceptCanonicalSha256Base58CorpusWithoutIdentityDependency() {
        // given
        Random random = new Random(GENERATED_CORPUS_SEED);
        List<String> candidates = new ArrayList<>(GENERATED_CORPUS_SIZE);
        for (int index = 0; index < GENERATED_CORPUS_SIZE; index++) {
            byte[] digest = new byte[SHA_256_BYTE_COUNT];
            random.nextBytes(digest);
            candidates.add(Base58.encode(digest));
        }

        // when
        List<String> validated = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            validated.add(BlueIds.requirePlainBlueId(
                    candidate, "generated-corpus"));
        }

        // then
        assertEquals(candidates, validated);
    }

    @Test
    void shouldRejectNonSha256AndHistoricallyNonCanonicalBase58Values() {
        // given
        byte[] tooShort = new byte[SHA_256_BYTE_COUNT - 1];
        byte[] tooLong = new byte[SHA_256_BYTE_COUNT + 1];
        Arrays.fill(tooShort, (byte) 1);
        Arrays.fill(tooLong, (byte) 1);
        String[] candidates = {
                Base58.encode(tooShort),
                Base58.encode(tooLong),
                Base58.encode(new byte[SHA_256_BYTE_COUNT])
        };

        // when
        boolean[] results = classify(candidates);

        // then
        for (boolean result : results) {
            assertFalse(result);
        }
    }

    @Test
    void shouldPreserveInputIdentityAndRejectHashCollisionsAfterCacheChurn() {
        // given
        String tail = "qsgVoN3ZL1Sg3b9wMfMK24R6ihV8n4t7zRFiivDbT";
        String valid = "Aa" + tail;
        String collision = "C#" + tail;
        String detached = new String(valid.toCharArray());
        Random random = new Random(0xB10E_CAC4EL);
        List<String> candidates = new ArrayList<>();
        for (int index = 0; index < 8192; index++) {
            byte[] digest = new byte[32];
            random.nextBytes(digest);
            candidates.add(Base58.encode(digest));
        }

        // when
        String initial = BlueIds.requirePlainBlueId(valid, "/warm");
        String repeated = BlueIds.requirePlainBlueId(detached, "/different/path");
        List<String> validated = new ArrayList<>();
        List<Boolean> invalidResults = new ArrayList<>();
        for (String candidate : candidates) {
            validated.add(BlueIds.requirePlainBlueId(candidate, "/churn"));
            invalidResults.add(acceptsPlain(candidate + "#0"));
        }
        String afterChurn = BlueIds.requirePlainBlueId(valid, "/after-churn");

        // then
        assertEquals(valid.hashCode(), collision.hashCode());
        assertEquals(valid, initial);
        assertSame(detached, repeated);
        assertEquals(candidates, validated);
        assertFalse(invalidResults.contains(true));
        assertEquals(valid, afterChurn);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> BlueIds.requirePlainBlueId(collision, "/collision"));
        assertEquals("Expected canonical Base58 SHA-256 BlueId at /collision.", rejected.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> BlueIds.requirePlainBlueId(collision, "/after-churn"));
    }

    @Test
    void shouldRetainCanonicalLengthAndNegativeValidationAcrossConcurrentWarmChecks() throws Exception {
        // given
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Callable<List<String>>> checks = new ArrayList<>();
        for (int worker = 0; worker < 6; worker++) {
            final int seed = worker;
            checks.add(() -> concurrentGrammarViolations(seed));
        }

        // when
        List<java.util.concurrent.Future<List<String>>> results;
        try {
            results = workers.invokeAll(checks);
        } finally {
            workers.shutdownNow();
        }

        // then
        for (java.util.concurrent.Future<List<String>> result : results) {
            assertEquals(java.util.Collections.emptyList(), result.get());
        }
    }

    private static List<String> concurrentGrammarViolations(int seed) {
        List<String> violations = new ArrayList<>();
        Random random = new Random(0xB10E_C011L + seed);
        for (int index = 0; index < 1024; index++) {
            byte[] digest = new byte[32];
            random.nextBytes(digest);
            String valid = Base58.encode(digest);
            if (!valid.equals(BlueIds.requirePlainBlueId(valid, "/concurrent"))) {
                violations.add("cold validation changed " + valid);
            }
            if (!valid.equals(BlueIds.requirePlainBlueId(new String(valid.toCharArray()), "/repeat"))) {
                violations.add("warm validation changed " + valid);
            }
            if (acceptsPlain("0" + valid.substring(1))) violations.add("accepted invalid character");
            if (acceptsPlain(Base58.encode(Arrays.copyOf(digest, 31)))) violations.add("accepted 31 bytes");
        }
        return violations;
    }

    private static boolean acceptsPlain(String candidate) {
        try {
            BlueIds.requirePlainBlueId(candidate, "/negative");
            return true;
        } catch (IllegalArgumentException expected) {
            return false;
        }
    }

    private static boolean[] classify(String[] candidates) {
        boolean[] results = new boolean[candidates.length];
        for (int index = 0; index < candidates.length; index++) {
            results[index] = isPotentialBlueId(candidates[index]);
        }
        return results;
    }
}
