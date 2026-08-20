package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact context propagation tests for closure-owned shared gas work. */
final class ClosureGasAttributionTest {

    @Test
    void shouldAttachClosureOwnerWithoutReplacingChargeReason() {
        GasMeter meter = new GasMeter();
        GasChargeContext work = GasChargeContext.closure(
                "member-a",
                "/",
                Long.valueOf(0L),
                Long.valueOf(2L),
                null,
                "work/3",
                identity('a'),
                "work-default");

        try (GasMeter.AttributionScope ignored = meter.withAttribution(work)) {
            meter.charge(
                    "processor",
                    "closureWorkOccurrenceDequeued",
                    1L,
                    GasChargeContext.reason("work.3.dequeue"));
        }

        GasTraceEntry entry = meter.trace().get(0);
        assertEquals("member-a", entry.documentId());
        assertEquals("/", entry.scopePath());
        assertEquals(Long.valueOf(0L), entry.activationGeneration());
        assertEquals(Long.valueOf(2L), entry.componentGeneration());
        assertEquals("work/3", entry.logicalPath());
        assertEquals(identity('a'), entry.workOccurrenceId());
        assertEquals("work.3.dequeue", entry.reason());
    }

    @Test
    void shouldRestoreOuterAttributionAndRejectInvalidScopePairs() {
        GasMeter meter = new GasMeter();
        GasChargeContext outer = GasChargeContext.closure(
                "member-a", "/", Long.valueOf(0L), null,
                null, null, null, "outer");
        GasChargeContext inner = GasChargeContext.closure(
                "member-b", "/", Long.valueOf(0L), null,
                null, null, null, "inner");

        try (GasMeter.AttributionScope ignored = meter.withAttribution(outer)) {
            try (GasMeter.AttributionScope nested = meter.withAttribution(inner)) {
                meter.charge("processor", "managedDocumentOpened", 1L);
            }
            meter.charge("processor", "managedDocumentOpened", 1L);
        }

        assertEquals("member-b", meter.trace().get(0).documentId());
        assertEquals("member-a", meter.trace().get(1).documentId());
        assertNull(meter.trace().get(1).workOccurrenceId());
        assertThrows(IllegalArgumentException.class,
                () -> GasChargeContext.closure(
                        "member-a", "/", null, null,
                        null, null, null, "invalid"));
    }

    @Test
    void shouldAttributeMergedRuntimeEntriesToOwningWork() {
        GasMeter meter = new GasMeter();
        GasMeter.ChildGasLedger child = meter.childLedger(
                "runtime.fixture",
                Collections.singletonMap("call", Long.valueOf(7L)));
        child.charge("call", 1L, GasChargeContext.reason("runtime.call"));

        try (GasMeter.AttributionScope ignored = meter.withAttribution(
                GasChargeContext.closure(
                        "member-a", "/", Long.valueOf(0L),
                        Long.valueOf(3L), null, "work/9",
                        identity('b'), "work-default"))) {
            meter.merge(child);
        }

        GasTraceEntry entry = meter.trace().get(0);
        assertEquals("runtime.fixture", entry.namespace());
        assertEquals("member-a", entry.documentId());
        assertEquals(identity('b'), entry.workOccurrenceId());
        assertEquals("runtime.call", entry.reason());
    }

    private static String identity(char digit) {
        StringBuilder value = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            value.append(digit);
        }
        return value.toString();
    }
}
