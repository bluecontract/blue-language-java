package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** Creates the exact processor-owned lifecycle and Document Update values. */
final class LifecycleEventFactory {

    private LifecycleEventFactory() {
    }

    static Node initiated(FrozenNode document) {
        Objects.requireNonNull(document, "document");
        Node event = typed(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED);
        event.properties(
                ProcessorContractConstants.KEY_DOCUMENT,
                ProcessorMarkerFactory.exactReference(document));
        return event;
    }

    static Node terminated(String cause, String reason) {
        return terminationValue(
                RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
                cause,
                reason);
    }

    static Node terminationMarker(String cause, String reason) {
        return terminationValue(
                RuntimeBlueIds.PROCESSING_TERMINATED_MARKER,
                cause,
                reason);
    }

    static Node documentUpdate(
            DocumentUpdateData data,
            String scopePath) {
        String relativePath = PointerUtils.relativizePointer(
                scopePath, data.path());
        String relativeSourceScopePath = PointerUtils.relativizePointer(
                scopePath, data.originScope());
        Node event = typed(RuntimeBlueIds.DOCUMENT_UPDATE);
        event.properties(
                ProcessorContractConstants.KEY_OPERATION,
                new Node().value(data.op().name().toLowerCase()));
        event.properties(
                ProcessorContractConstants.KEY_PATH,
                new Node().value(relativePath));
        event.properties(
                ProcessorContractConstants.KEY_BEFORE_PRESENT,
                new Node().value(data.beforePresent()));
        if (data.beforePresent()) {
            event.properties(
                    ProcessorContractConstants.KEY_BEFORE,
                    data.before());
        }
        event.properties(
                ProcessorContractConstants.KEY_AFTER_PRESENT,
                new Node().value(data.afterPresent()));
        if (data.afterPresent()) {
            event.properties(
                    ProcessorContractConstants.KEY_AFTER,
                    data.after());
        }
        event.properties(
                ProcessorContractConstants.KEY_SOURCE_SCOPE_PATH,
                new Node().value(relativeSourceScopePath));
        return event;
    }

    private static Node terminationValue(
            String typeBlueId,
            String cause,
            String reason) {
        Node value = typed(typeBlueId);
        value.properties(
                ProcessorContractConstants.KEY_CAUSE,
                new Node().value(cause));
        if (reason != null && !reason.isEmpty()) {
            value.properties(
                    ProcessorContractConstants.KEY_REASON,
                    new Node().value(reason));
        }
        return value;
    }

    private static Node typed(String typeBlueId) {
        return new Node().type(new Node().blueId(typeBlueId));
    }
}
