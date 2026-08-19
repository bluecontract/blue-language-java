package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PersistentMutationPortableLimitTest {

    @Test
    void shouldVerifyEveryRebuiltAncestorMustSatisfyDirectObjectLimit() {
        // given
        Node wide = new Node();
        for (int index = 0; index < 16_385; index++) {
            wide.properties("k" + index, new Node().value(0));
        }
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node().properties("wide", wide));

        // when
        PortableLimitExceededException failure =
                FailureCapture.captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.replace(
                                "/wide/k0",
                                new Node().value(1))));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.DirectNodeLimitExceeded,
                failure.diagnostic().category());
        assertEquals(
                "directObjectEntriesMaterializedOrRebuilt",
                failure.limitName());
        assertEquals(16_385L, failure.observed());
        assertEquals(16_384L, failure.limit());
        assertEquals(
                "0",
                String.valueOf(runtime.nodeAt("/wide/k0").getValue()));
    }

    @Test
    void shouldAcceptListAppendThatLandsExactlyOnThePortableLimit() {
        // given
        GasSchedule schedule = GasScheduleTestFixtures.withPortableLimit(
                GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS,
                3L);
        Node document = new Node().properties(
                "values",
                new Node().items(Arrays.asList(
                        new Node().value(1),
                        new Node().value(2))));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document, null, null, null, null,
                new GasMeter(schedule));

        // when
        runtime.applyPatch(
                "/",
                JsonPatch.add("/values/-", new Node().value(3)));

        // then
        assertEquals(3, document.getAsNode("/values").getItems().size());
        assertEquals(3, document.getAsInteger("/values/2"));
    }

    @Test
    void shouldRejectNonTailRemovalWhoseResultStillExceedsThePortableLimit() {
        // given
        GasSchedule schedule = GasScheduleTestFixtures.withPortableLimit(
                GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS,
                2L);
        Node document = new Node().properties(
                "values",
                new Node().items(Arrays.asList(
                        new Node().value(1),
                        new Node().value(2),
                        new Node().value(3),
                        new Node().value(4))));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document, null, null, null, null,
                new GasMeter(schedule));

        // when
        PortableLimitExceededException failure = FailureCapture.captureFailure(
                () -> runtime.applyPatch(
                        "/", JsonPatch.remove("/values/1")));

        // then
        assertNotNull(failure);
        assertEquals(
                GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS,
                failure.limitName());
        assertEquals(3L, failure.observed());
        assertEquals(2L, failure.limit());
        assertEquals(4, document.getAsNode("/values").getItems().size());
        assertEquals(2, document.getAsInteger("/values/1"));
    }

    @Test
    void shouldChargeEachBatchListFoldFromItsImmediatePriorProjection() {
        // given
        GasMeter meter = new GasMeter();
        Node document = new Node().properties(
                "values",
                new Node().items(Arrays.asList(
                        new Node().value(1))));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document, null, null, null, null, meter);

        // when
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.add("/values/-", new Node().value(2)),
                JsonPatch.remove("/values/0")));
        List<Long> foldQuantities = meter.trace().stream()
                .filter(entry -> GasScheduleConstants.SemanticCounter
                        .LIST_FOLD_STEP_RECOMPUTED.equals(entry.counter()))
                .map(GasTraceEntry::quantity)
                .collect(Collectors.toList());

        // then
        assertEquals(Arrays.asList(1L, 1L), foldQuantities);
        assertEquals(1, document.getAsNode("/values").getItems().size());
        assertEquals(2, document.getAsInteger("/values/0"));
    }

}
