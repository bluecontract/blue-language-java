package blue.language.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SnapshotExamplesTest {

    @Test
    void shouldKeepSnapshotFrozenWhenMutableViewChanges() {
        // given
        String expectedFrozenValue = "stable";

        // when
        ImmutableSnapshotExample.Result result =
                ImmutableSnapshotExample.run();

        // then
        assertEquals(expectedFrozenValue,
                result.getFrozenMessage().getValue());
        assertEquals("caller mutation",
                result.getMutatedDetachedView().getProperties()
                        .get("message").getValue());
        assertEquals(expectedFrozenValue,
                result.getFreshDetachedView().getProperties()
                        .get("message").getValue());
    }

    @Test
    void shouldPatchPersistentlyAndShareUnchangedBranch() {
        // given
        String expectedBefore = "before";
        String expectedAfter = "after";

        // when
        PersistentPatchingExample.Result result =
                PersistentPatchingExample.run();

        // then
        assertEquals(expectedBefore, result.getBeforeRightValue());
        assertEquals(expectedAfter, result.getAfterRightValue());
        assertNotEquals(result.getBeforeBlueId(), result.getAfterBlueId());
        assertTrue(result.isLeftBranchShared());
    }
}
