package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RuntimeWorkSessionLifecycleTest {

    @Test
    void shouldRetainPrimaryFailureWhileEveryCleanupRunsInCallerOrder() {
        // given
        RuntimeException primary = new RuntimeException("primary");
        RuntimeException secondClose = new RuntimeException("second");
        Error firstClose = new AssertionError("first");
        List<String> order = new ArrayList<>();

        // when
        Throwable failure = RuntimeWorkSession.closePreserving(
                () -> {
                    order.add("second");
                    throw secondClose;
                },
                primary);
        failure = RuntimeWorkSession.closePreserving(
                () -> {
                    order.add("first");
                    throw firstClose;
                },
                failure);

        // then
        assertSame(primary, failure);
        assertEquals(Arrays.asList("second", "first"), order);
        assertEquals(Arrays.asList(secondClose, firstClose),
                Arrays.asList(primary.getSuppressed()));
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> RuntimeWorkSession.rethrow(primary));
        assertSame(primary, thrown);
    }
}
