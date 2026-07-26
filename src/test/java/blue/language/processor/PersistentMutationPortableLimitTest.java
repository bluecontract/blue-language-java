package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PersistentMutationPortableLimitTest {

    @Test
    void everyRebuiltAncestorMustSatisfyDirectObjectLimit() {
        Node wide = new Node();
        for (int index = 0; index < 16_385; index++) {
            wide.properties("k" + index, new Node().value(0));
        }
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node().properties("wide", wide));

        PortableLimitExceededException failure = assertThrows(
                PortableLimitExceededException.class,
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.replace(
                                "/wide/k0",
                                new Node().value(1))));

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
