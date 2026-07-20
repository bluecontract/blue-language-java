package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;

/**
 * Processor-owned marker values authored in canonical frozen form before they
 * cross the runtime patch boundary.
 */
final class ProcessorMarkerFactory {

    private ProcessorMarkerFactory() {
    }

    static FrozenNode initialized(String documentId) {
        return FrozenNode.fromNode(new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties("documentId", new Node().value(documentId)));
    }
}
