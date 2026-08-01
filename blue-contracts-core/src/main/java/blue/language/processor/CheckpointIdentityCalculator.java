package blue.language.processor;

import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.utils.UncheckedObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Establishes the deterministic identity used for checkpoint newness.
 *
 * <p>Exact BlueId input is preferred. When a
 * {@link LanguageRuntimeAccess} context is
 * available, authored values may fall back to semantic canonicalization and
 * finally to the processor's canonical signature. Each path is timed
 * independently for production diagnostics.</p>
 */
final class CheckpointIdentityCalculator {

    private CheckpointIdentityCalculator() {
    }

    static String identity(Node event) {
        return identity(event, null);
    }

    static String identity(
            Node event,
            LanguageRuntimeAccess languageRuntime) {
        return identity(
                event,
                languageRuntime,
                NoOpProcessingObserver.INSTANCE);
    }

    static String identity(
            Node event,
            LanguageRuntimeAccess languageRuntime,
            ProcessingObserver metrics) {
        if (event == null) {
            return null;
        }
        /*
         * Processor events may be captured from a resolved snapshot, where a
         * nominal type carries both its requested BlueId and materialized
         * definition. Project that trusted view back to valid Source form so
         * checkpoint identity never depends on resolved representation.
         */
        Node sourceProjection = event.clone();
        MaterializationProvenance.clear(sourceProjection);
        ProcessingObserver observer = metrics != null
                ? metrics
                : NoOpProcessingObserver.INSTANCE;
        long directStart = System.nanoTime();
        try {
            String identity = DirectBlueIdCalculator.calculateBlueId(
                    sourceProjection);
            ProcessingObservations.record(observer,
                    ProcessingMetricId.CHECKPOINT_DIRECT_BLUE_ID_NANOS,
                    System.nanoTime() - directStart);
            return identity;
        } catch (RuntimeException directFailure) {
            ProcessingObservations.record(observer,
                    ProcessingMetricId.CHECKPOINT_DIRECT_BLUE_ID_NANOS,
                    System.nanoTime() - directStart);
            if (languageRuntime == null) {
                throw new IllegalStateException(
                        "Checkpoint event identity requires valid BlueId Input or a Blue canonicalization context",
                        directFailure);
            }
            long contentStart = System.nanoTime();
            try {
                String identity = languageRuntime.calculateSourceDocumentBlueId(
                        sourceProjection.clone());
                ProcessingObservations.record(observer,
                        ProcessingMetricId.CHECKPOINT_CONTENT_BLUE_ID_NANOS,
                        System.nanoTime() - contentStart);
                return identity;
            } catch (RuntimeException semanticFailure) {
                ProcessingObservations.record(observer,
                        ProcessingMetricId.CHECKPOINT_CONTENT_BLUE_ID_NANOS,
                        System.nanoTime() - contentStart);
                long fallbackStart = System.nanoTime();
                try {
                    return canonicalSignature(sourceProjection.clone());
                } finally {
                    ProcessingObservations.record(observer,
                            ProcessingMetricId.CHECKPOINT_FALLBACK_NANOS,
                            System.nanoTime() - fallbackStart);
                }
            }
        }
    }

    static String canonicalSignature(Node node) {
        if (node == null) {
            return null;
        }
        Object canonical = NodeWireForm.get(
                normalizeSignatureNode(node.clone()));
        try {
            String json = UncheckedObjectMapper.JSON_MAPPER
                    .writeValueAsString(canonical);
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Failed to canonicalize node for checkpoint comparison",
                    failure);
        }
    }

    private static Node normalizeSignatureNode(Node node) {
        if (node == null) {
            return null;
        }
        node.type(normalizeSignatureReference(node.getType()));
        node.itemType(normalizeSignatureReference(node.getItemType()));
        node.keyType(normalizeSignatureReference(node.getKeyType()));
        node.valueType(normalizeSignatureReference(node.getValueType()));
        if (node.getItems() != null) {
            node.getItems().replaceAll(
                    CheckpointIdentityCalculator::normalizeSignatureNode);
        }
        if (node.getProperties() != null) {
            node.getProperties().replaceAll((key, value) ->
                    isTypeReferenceKey(key)
                            ? normalizeSignatureReference(value)
                            : normalizeSignatureNode(value));
        }
        if (node.getContracts() != null) {
            node.contracts(normalizeSignatureNode(node.getContracts()));
        }
        if (node.getBlue() != null) {
            node.blue(normalizeSignatureNode(node.getBlue()));
        }
        return node;
    }

    private static boolean isTypeReferenceKey(String key) {
        return BlueLanguageConstants.OBJECT_TYPE.equals(key)
                || BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(key)
                || BlueLanguageConstants.OBJECT_KEY_TYPE.equals(key)
                || BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(key);
    }

    private static Node normalizeSignatureReference(Node reference) {
        if (reference == null) {
            return null;
        }
        normalizeSignatureNode(reference);
        if (reference.getBlueId() != null) {
            return new Node().blueId(reference.getBlueId());
        }
        if (reference.getName() != null) {
            return new Node().blueId(
                    DirectBlueIdCalculator.calculateBlueId(reference));
        }
        return reference;
    }
}
