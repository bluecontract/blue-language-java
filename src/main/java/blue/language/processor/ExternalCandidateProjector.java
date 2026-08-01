package blue.language.processor;

import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Projects the exact read-only scope view used to classify one external
 * source Channel.
 *
 * <p>Projection is deliberately separated from evaluation so processor-owned
 * initialization mutations cannot silently widen or replace the evidence
 * surface admitted by the feeder.</p>
 */
final class ExternalCandidateProjector {

    private final DocumentProcessor owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;

    ExternalCandidateProjector(
            DocumentProcessor owner,
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    ContractBundle project(
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ExternalChannelDependencySnapshot declaredDependencies) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        runtime.validateProcessEmbeddedTraversalWithoutResolution(
                normalizedScope);
        FrozenNode selected =
                execution.classificationSelectedAt(normalizedScope);
        FrozenNode resolved =
                execution.classificationResolvedAt(normalizedScope);
        FrozenNode recognitionScope = runtime.contractRecognitionScope(
                selected, resolved);
        if (!isParticipatingObject(normalizedScope, selected)
                || !isParticipatingObject(
                normalizedScope, recognitionScope)) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery scope is absent or not an object: "
                            + normalizedScope);
        }
        return owner.contractLoader().loadExternalClassification(
                selected,
                recognitionScope,
                normalizedScope,
                channelKey,
                includeProcessEmbedded,
                declaredDependencies,
                owner.observer(),
                execution.contractRecognitionMeter(),
                includeProcessEmbedded
                        ? "structural-route-header"
                        : "external-channel-header");
    }

    ContractBundle.ChannelBinding requireExternalSource(
            String scopePath,
            String channelKey,
            ContractBundle classificationBundle) {
        ContractBundle.ChannelBinding source =
                classificationBundle != null
                        ? new SameScopeChannelCatalog(classificationBundle)
                        .externalSource(channelKey)
                        : null;
        if (source == null) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery occurrence is not executable at "
                            + ProcessorEngine.normalizeScope(scopePath)
                            + "/" + channelKey);
        }
        return source;
    }

    private boolean isParticipatingObject(
            String scopePath,
            FrozenNode node) {
        if (node == null || node.isReferenceOnly()) {
            return false;
        }
        return blue.language.utils.JsonPointer.ROOT.equals(scopePath)
                || (node.getValue() == null && !node.hasItems());
    }
}
