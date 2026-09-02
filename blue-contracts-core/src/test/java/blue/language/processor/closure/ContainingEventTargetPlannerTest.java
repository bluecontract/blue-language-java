package blue.language.processor.closure;

import blue.language.processor.GasScheduleConstants;
import blue.language.processor.PortableLimitExceededException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Transitive, occurrence-specific containing-event target projection. */
final class ContainingEventTargetPlannerTest {

    private static final long LARGE_LIMIT = 4096L;

    @Test
    void shouldComposeTransitiveSourcePathsInCanonicalTargetOrder() {
        DocumentId a = document("a");
        DocumentId b = document("b");
        DocumentId c = document("c");
        DocumentId d = document("d");
        ManagedOccurrenceBinding aToB = binding(
                1, a, "/orders/o1", b);
        ManagedOccurrenceBinding bToC = binding(
                2, b, "/payment", c);
        ManagedOccurrenceBinding dToC = binding(
                3, d, "/archive", c);

        List<FrozenContainingEventTarget> targets = freeze(
                c, Arrays.asList(dToC, bToC, aToB));

        assertEquals(
                Arrays.asList(
                        "a:/orders/o1/payment",
                        "b:/payment",
                        "d:/archive"),
                projections(targets));
        assertEquals(
                Arrays.asList(aToB, bToC),
                targets.get(0).lineage());
    }

    @Test
    void shouldStopCyclesButRetainEveryDistinctDiamondOccurrencePath() {
        DocumentId a = document("a");
        DocumentId b = document("b");
        DocumentId c = document("c");
        DocumentId d = document("d");
        ManagedOccurrenceBinding aToB = binding(1, a, "/left", b);
        ManagedOccurrenceBinding aToD = binding(2, a, "/right", d);
        ManagedOccurrenceBinding bToC = binding(3, b, "/child", c);
        ManagedOccurrenceBinding dToC = binding(4, d, "/child", c);

        assertEquals(
                Arrays.asList(
                        "a:/left/child",
                        "a:/right/child",
                        "b:/child",
                        "d:/child"),
                projections(freeze(c, Arrays.asList(
                        dToC, aToD, bToC, aToB))));

        ManagedOccurrenceBinding bToA = binding(5, b, "/a", a);
        ManagedOccurrenceBinding cyclicAToB = binding(6, a, "/b", b);
        assertEquals(
                Collections.singletonList("b:/a"),
                projections(freeze(a, Arrays.asList(
                        cyclicAToB, bToA))));
    }

    @Test
    void shouldBoundExpandedTargetsAndComposedRuntimePointers() {
        DocumentId a = document("a");
        DocumentId b = document("b");
        DocumentId c = document("c");
        DocumentId d = document("d");
        ManagedOccurrenceBinding bToC = binding(1, b, "/inner", c);
        ManagedOccurrenceBinding aToB = binding(2, a, "/outer", b);
        ManagedOccurrenceBinding dToC = binding(3, d, "/other", c);

        PortableLimitExceededException targets = assertThrows(
                PortableLimitExceededException.class,
                () -> ContainingEventTargetPlanner.freeze(
                        c,
                        Arrays.asList(aToB, bToC, dToC),
                        1L,
                        LARGE_LIMIT,
                        LARGE_LIMIT));
        assertEquals(
                GasScheduleConstants.PortableLimit
                        .PARTICIPATING_SCOPES_PER_EVENT,
                targets.limitName());
        assertEquals(2L, targets.observed());

        PortableLimitExceededException segments = assertThrows(
                PortableLimitExceededException.class,
                () -> ContainingEventTargetPlanner.freeze(
                        c,
                        Arrays.asList(aToB, bToC),
                        LARGE_LIMIT,
                        1L,
                        LARGE_LIMIT));
        assertEquals(
                GasScheduleConstants.PortableLimit
                        .RUNTIME_POINTER_SEGMENTS,
                segments.limitName());
        assertEquals(2L, segments.observed());
    }

    private static List<FrozenContainingEventTarget> freeze(
            DocumentId source,
            List<ManagedOccurrenceBinding> bindings) {
        return ContainingEventTargetPlanner.freeze(
                source,
                bindings,
                LARGE_LIMIT,
                LARGE_LIMIT,
                LARGE_LIMIT);
    }

    private static List<String> projections(
            List<FrozenContainingEventTarget> targets) {
        return targets.stream()
                .map(target -> target.receivingDocumentId()
                        + ":" + target.sourcePath())
                .collect(Collectors.toList());
    }

    private static DocumentId document(String value) {
        return new DocumentId(value);
    }

    private static ManagedOccurrenceBinding binding(
            int ordinal,
            DocumentId source,
            String path,
            DocumentId target) {
        return new ManagedOccurrenceBinding(
                identity(ordinal),
                identity(ordinal + 100),
                identity(900),
                source,
                ScopeAddress.embedded(path, ordinal),
                target,
                target.value() + "-blue",
                true,
                null);
    }

    private static String identity(int value) {
        return String.format(
                "sha256:%064x", Integer.valueOf(value));
    }
}
