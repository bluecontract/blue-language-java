package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.BlueIdReferenceValidator;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads, validates, and normalizes processor-owned lifecycle markers.
 *
 * <p>This service is deliberately stateless. Marker recognition depends only
 * on the exact selected representation, so callers cannot accidentally mix
 * lifecycle state with resolved application-contract views.</p>
 */
final class ProcessorMarkerStore {

    private ProcessorMarkerStore() {
    }

    static boolean isInitialized(Node document) {
        Objects.requireNonNull(document, "document");
        return hasInitializationMarker(document, JsonPointer.ROOT);
    }

    static boolean isInitialized(ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String pointer = markerPointer(
                JsonPointer.ROOT,
                ProcessorPointerConstants.RELATIVE_INITIALIZED);
        FrozenNode selected = snapshot.sourceAt(pointer);
        Node marker = selected != null ? selected.toNode() : null;
        if (marker == null) {
            return false;
        }
        validateInitializationMarker(marker, pointer);
        return true;
    }

    static boolean hasInitializationMarker(Node root, String scopePath) {
        String pointer = markerPointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_INITIALIZED);
        Node marker;
        try {
            marker = nodeAt(root, pointer);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (marker == null) {
            return false;
        }
        validateInitializationMarker(marker, pointer);
        return true;
    }

    static TerminationMarker terminationMarker(Node root, String scopePath) {
        String pointer = markerPointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_TERMINATED);
        Node marker;
        try {
            marker = nodeAt(root, pointer);
        } catch (RuntimeException ignored) {
            return null;
        }
        return marker != null
                ? validateTerminationMarker(marker, pointer)
                : null;
    }

    static boolean hasDirectRootTerminationEntry(Node root) {
        Node contracts = root != null ? root.getContracts() : null;
        return contracts != null
                && contracts.getProperties() != null
                && contracts.getProperties().containsKey(
                        ProcessorContractConstants.KEY_TERMINATED);
    }

    static void validateInitializationMarker(Node marker, String pointer) {
        if (marker == null) {
            return;
        }
        Node type = marker.getType();
        if (type == null
                || !RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER.equals(
                        runtimeTypeBlueId(type))) {
            throw new IllegalStateException(
                    "Reserved key 'initialized' must contain a Processing "
                            + "Initialized Marker at " + pointer);
        }
        Node document = marker.getProperties() != null
                ? marker.getProperties().get(
                        ProcessorContractConstants.KEY_DOCUMENT)
                : null;
        if (document == null
                || marker.getProperties().containsKey(
                        ProcessorContractConstants.LEGACY_KEY_DOCUMENT_ID)) {
            throw new IllegalStateException(
                    "Processing Initialized Marker must contain the exact "
                            + "pre-initialization document at " + pointer);
        }
        try {
            BlueIdReferenceValidator.validate(document);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Processing Initialized Marker contains an invalid exact "
                            + "document at " + pointer,
                    invalid);
        }
    }

    static TerminationMarker validateTerminationMarker(
            Node marker,
            String pointer) {
        if (marker == null) {
            return null;
        }
        Node type = marker.getType();
        if (type == null
                || !RuntimeBlueIds.PROCESSING_TERMINATED_MARKER.equals(
                        runtimeTypeBlueId(type))) {
            throw new IllegalStateException(
                    "Reserved key 'terminated' must contain a Processing "
                            + "Terminated Marker at " + pointer);
        }
        String cause = stringProperty(
                marker,
                ProcessorContractConstants.KEY_CAUSE);
        if (cause == null || cause.isEmpty()) {
            throw new IllegalStateException(
                    "Processing Terminated Marker cause must be non-empty "
                            + "Text at " + pointer);
        }
        return new TerminationMarker(
                cause,
                stringProperty(marker, ProcessorContractConstants.KEY_REASON));
    }

    /**
     * Collapses exact initialization documents before Language resolution.
     * The marker document is already exact and must not be treated as an
     * overlay merely because it is stored inline.
     */
    static void collapseInitializationDocuments(
            Node root,
            ProcessingSnapshotManager snapshotManager) {
        collapseInitializationDocuments(
                root,
                JsonPointer.ROOT,
                snapshotManager,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));
    }

    static Node nodeAt(Node root, String pointer) {
        if (JsonPointer.ROOT.equals(pointer)) {
            return root;
        }
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (ProcessorContractConstants.KEY_CONTRACTS.equals(segment)) {
                current = current != null ? current.getContracts() : null;
            } else {
                Map<String, Node> properties =
                        current != null ? current.getProperties() : null;
                current = properties != null ? properties.get(segment) : null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static void collapseInitializationDocuments(
            Node node,
            String path,
            ProcessingSnapshotManager snapshotManager,
            Set<Node> visited) {
        if (node == null
                || node.isReferenceOnly()
                || !visited.add(node)) {
            return;
        }
        Node contracts = node.getContracts();
        Node marker = contracts != null
                && contracts.getProperties() != null
                ? contracts.getProperties().get(
                        ProcessorContractConstants.KEY_INITIALIZED)
                : null;
        if (marker != null) {
            String markerPath = markerPointer(
                    path,
                    ProcessorPointerConstants.RELATIVE_INITIALIZED);
            try {
                validateInitializationMarker(marker, markerPath);
            } catch (IllegalStateException ignored) {
                // Participating-closure recognition owns invalid-marker failure.
                marker = null;
            }
        }
        if (marker != null) {
            Node exactDocument = marker.getProperties().get(
                    ProcessorContractConstants.KEY_DOCUMENT);
            if (!exactDocument.isReferenceOnly()) {
                marker.getProperties().put(
                        ProcessorContractConstants.KEY_DOCUMENT,
                        new Node().blueId(
                                CanonicalIdentityEvidence.sourceBlueId(
                                        exactDocument,
                                        snapshotManager,
                                        "Initialization marker exact document")));
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                collapseInitializationDocuments(
                        node.getItems().get(index),
                        JsonPointer.append(path, String.valueOf(index)),
                        snapshotManager,
                        visited);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                collapseInitializationDocuments(
                        entry.getValue(),
                        JsonPointer.append(path, entry.getKey()),
                        snapshotManager,
                        visited);
            }
        }
    }

    private static String markerPointer(
            String scopePath,
            String relativePointer) {
        return PointerUtils.resolvePointer(scopePath, relativePointer);
    }

    private static String runtimeTypeBlueId(Node type) {
        if (type == null) {
            return null;
        }
        return type.isReferenceOnly() ? type.getBlueId() : null;
    }

    private static String stringProperty(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        Node value = node.getProperties().get(key);
        Object raw = value != null ? value.getValue() : null;
        return raw instanceof String ? (String) raw : null;
    }

    /** Immutable validated projection of a termination marker. */
    static final class TerminationMarker {
        final String cause;
        final String reason;

        private TerminationMarker(String cause, String reason) {
            this.cause = cause;
            this.reason = reason;
        }
    }
}
