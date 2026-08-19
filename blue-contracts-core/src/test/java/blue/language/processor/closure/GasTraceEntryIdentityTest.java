package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Identity-projection tests for observable closure gas entries. */
final class GasTraceEntryIdentityTest {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    @Test
    void diagnosticReasonDoesNotChangeGasTraceIdentity() {
        GasTraceEntry first = entry("/contracts/alpha", "first wording");
        GasTraceEntry second = entry("/contracts/alpha", "rewritten wording");

        assertNotEquals(first.reason(), second.reason());
        assertFalse(first.identityValue().containsKey("reason"));
        assertEquals(identity(first), identity(second));
    }

    @Test
    void semanticAttributionChangesGasTraceIdentity() {
        GasTraceEntry first = entry("/contracts/alpha", "same diagnostic");
        GasTraceEntry second = entry("/contracts/beta", "same diagnostic");

        assertEquals("/contracts/alpha",
                first.identityValue().get("logicalPath"));
        assertNotEquals(identity(first), identity(second));
    }

    private static GasTraceEntry entry(String logicalPath, String reason) {
        return new GasTraceEntry(
                0L,
                GasTraceEntry.Namespace.PROCESSOR,
                "closureInvocation",
                1L,
                100L,
                100L,
                new DocumentId("root"),
                "/",
                Long.valueOf(0L),
                Long.valueOf(3L),
                "alpha",
                logicalPath,
                hash('a'),
                reason);
    }

    private static String identity(GasTraceEntry entry) {
        Map<String, Object> projected = entry.identityValue();
        return IDENTITIES.identity(
                ClosureIdentityService.Constructor.GAS_TRACE,
                Collections.<Object>singletonList(projected));
    }

    private static String hash(char value) {
        return "sha256:" + String.join("", Collections.nCopies(64,
                String.valueOf(value)));
    }
}
