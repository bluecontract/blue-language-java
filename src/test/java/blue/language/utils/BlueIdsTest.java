package blue.language.utils;

import blue.language.identity.Base58;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static blue.language.utils.BlueIds.isPotentialBlueId;
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

    private static boolean[] classify(String[] candidates) {
        boolean[] results = new boolean[candidates.length];
        for (int index = 0; index < candidates.length; index++) {
            results[index] = isPotentialBlueId(candidates[index]);
        }
        return results;
    }
}
