package blue.language.utils;

import org.junit.jupiter.api.Test;

import static blue.language.utils.BlueIds.isPotentialBlueId;
import static org.junit.jupiter.api.Assertions.*;

class BlueIdsTest {

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

    private static boolean[] classify(String[] candidates) {
        boolean[] results = new boolean[candidates.length];
        for (int index = 0; index < candidates.length; index++) {
            results[index] = isPotentialBlueId(candidates[index]);
        }
        return results;
    }
}
