package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

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
}
