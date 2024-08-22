package blue.language.utils;

import org.junit.jupiter.api.Test;

import static blue.language.utils.BlueIds.isPotentialBlueId;
import static org.junit.jupiter.api.Assertions.*;

class BlueIdsTest {

    @Test
    void testIsPotentialBlueId() {
        assertTrue(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7"));
        assertTrue(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#12"));

        assertFalse(isPotentialBlueId(null));
        assertFalse(isPotentialBlueId(""));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzr"));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7A"));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#"));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#-1"));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#abc"));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7#12#34"));
        assertFalse(isPotentialBlueId("0Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7"));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7O"));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7I"));
        assertFalse(isPotentialBlueId("4Yj5XZbpuS1quJHsLbxsAnNHTV1XbhgQar2zQBDzrat7l"));
    }
}