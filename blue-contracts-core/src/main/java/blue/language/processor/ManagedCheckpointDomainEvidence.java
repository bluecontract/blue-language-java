package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exact cold checkpoint-domain acquisition and structural decoding. */
final class ManagedCheckpointDomainEvidence {

    private ManagedCheckpointDomainEvidence() {
    }

    static ManagedCheckpointDomain restore(
            Node domainNode,
            String assertedBlueId,
            ProcessingSnapshotManager manager) {
        Node exactDomain = materialize(domainNode, manager);
        String type = textProperty(
                exactDomain,
                ProcessorIdentityConstants.Field.EFFECTIVE_TYPE_BLUE_ID,
                true);
        List<String> sources = textListProperty(
                exactDomain,
                ProcessorIdentityConstants.Field
                        .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                true);
        List<String> dependencies = textListProperty(
                exactDomain,
                ProcessorIdentityConstants.Field
                        .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                false);
        String discriminator = textProperty(
                exactDomain,
                ProcessorIdentityConstants.Field.RUNTIME_DISCRIMINATOR,
                false);
        return new ManagedCheckpointDomain(
                type,
                sources,
                dependencies,
                discriminator,
                exactDomain,
                assertedBlueId);
    }

    private static Node materialize(
            Node domainNode,
            ProcessingSnapshotManager manager) {
        if (!domainNode.isReferenceOnly()) {
            return domainNode.clone();
        }
        if (manager == null) {
            throw new ExecutionEvidenceUnavailableException(
                    "Complete checkpoint-domain value is unavailable for "
                            + domainNode.getBlueId(),
                    Collections.singleton(domainNode.getBlueId()));
        }
        FrozenNode materialized = manager.materializeVerifiedExactReference(
                FrozenNode.fromNode(domainNode));
        if (materialized == null || materialized.isReferenceOnly()) {
            throw new ExecutionEvidenceUnavailableException(
                    "Complete checkpoint-domain value is unavailable for "
                            + domainNode.getBlueId(),
                    Collections.singleton(domainNode.getBlueId()));
        }
        return materialized.toNode();
    }

    private static String textProperty(
            Node node,
            String key,
            boolean required) {
        Node value = node != null && node.getProperties() != null
                ? node.getProperties().get(key) : null;
        Object raw = value != null ? value.getValue() : null;
        if (raw instanceof String && !((String) raw).isEmpty()) {
            return (String) raw;
        }
        if (!required && value == null) {
            return null;
        }
        throw new InvalidExecutionEvidenceException(
                "Checkpoint-domain field is invalid: " + key,
                ProcessorErrorCategory.CheckpointPolicyError);
    }

    private static List<String> textListProperty(
            Node node,
            String key,
            boolean required) {
        Node value = node != null && node.getProperties() != null
                ? node.getProperties().get(key) : null;
        if (value == null && !required) {
            return Collections.emptyList();
        }
        if (value == null || value.getItems() == null) {
            throw new InvalidExecutionEvidenceException(
                    "Checkpoint-domain list field is invalid: " + key,
                    ProcessorErrorCategory.CheckpointPolicyError);
        }
        List<String> result = new ArrayList<String>();
        for (Node item : value.getItems()) {
            Object raw = item != null ? item.getValue() : null;
            if (!(raw instanceof String) || ((String) raw).isEmpty()) {
                throw new InvalidExecutionEvidenceException(
                        "Checkpoint-domain list item is invalid: " + key,
                        ProcessorErrorCategory.CheckpointPolicyError);
            }
            result.add((String) raw);
        }
        return result;
    }
}
