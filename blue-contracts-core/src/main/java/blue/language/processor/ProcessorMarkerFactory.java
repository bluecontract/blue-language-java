package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

/**
 * Processor-owned marker values authored in canonical frozen form before they
 * cross the runtime patch boundary.
 */
final class ProcessorMarkerFactory {

    private ProcessorMarkerFactory() {
    }

    static FrozenNode initialized(FrozenNode document) {
        if (document == null) {
            throw new IllegalArgumentException(
                    "The exact pre-initialization document is required.");
        }
        return FrozenNode.fromNode(new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties(
                        ProcessorContractConstants.KEY_DOCUMENT,
                        exactReference(document)));
    }

    static Node exactReference(FrozenNode document) {
        if (document == null) {
            throw new IllegalArgumentException(
                    "The exact pre-initialization document is required.");
        }
        return new Node().blueId(document.blueId());
    }
}
