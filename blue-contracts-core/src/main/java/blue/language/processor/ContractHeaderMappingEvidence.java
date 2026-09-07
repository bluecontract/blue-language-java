package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.ProcessorContractConstants;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Objects;

/**
 * Invocation-local canonical-content evidence for contract-header mapping.
 *
 * <p>The producing snapshot is used only while a structural bundle is built;
 * it is never retained by the bundle cache. Detached and recombined scope
 * lanes use {@link #none(String)}, so mapping an {@code @BlueId} field cannot
 * depend on a previously warm evidence-bearing cache entry.</p>
 */
final class ContractHeaderMappingEvidence {

    private static final String NO_EVIDENCE_CACHE_SIGNATURE =
            "<no-canonical-content-evidence>";
    private static final String UNAVAILABLE_EVIDENCE_CACHE_SIGNATURE =
            "<canonical-content-evidence-unavailable>";

    private final ResolvedSnapshot snapshot;
    private final String scopePath;
    private final String cacheSignature;

    private ContractHeaderMappingEvidence(
            ResolvedSnapshot snapshot,
            String scopePath,
            String cacheSignature) {
        this.snapshot = snapshot;
        this.scopePath = JsonPointer.canonicalize(scopePath);
        this.cacheSignature = Objects.requireNonNull(
                cacheSignature, "cacheSignature");
    }

    static ContractHeaderMappingEvidence none(String scopePath) {
        return new ContractHeaderMappingEvidence(
                null,
                scopePath,
                NO_EVIDENCE_CACHE_SIGNATURE);
    }

    static ContractHeaderMappingEvidence fromSnapshot(
            ResolvedSnapshot snapshot,
            String scopePath) {
        ResolvedSnapshot producingSnapshot = Objects.requireNonNull(
                snapshot, "snapshot");
        String normalizedScope = JsonPointer.canonicalize(scopePath);
        String signature = UNAVAILABLE_EVIDENCE_CACHE_SIGNATURE;
        if (producingSnapshot.hasCanonicalIdentity()) {
            String scopeBlueId = producingSnapshot.canonicalBlueIdAt(
                    normalizedScope);
            signature = scopeBlueId != null
                    ? "canonical-scope:" + scopeBlueId
                    : "<canonical-scope-path-missing>";
        }
        return new ContractHeaderMappingEvidence(
                producingSnapshot,
                normalizedScope,
                signature);
    }

    String cacheSignature() {
        return cacheSignature;
    }

    <T> T convertContract(
            String contractKey,
            Node resolvedProjection,
            Collection<String> omittedProperties,
            Type targetType,
            boolean prioritizeTargetType,
            NodeToObjectConverter converter,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(contractKey, "contractKey");
        Objects.requireNonNull(omittedProperties, "omittedProperties");
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        if (resolvedProjection == null) {
            return null;
        }
        if (snapshot == null) {
            return converter.convertWithType(
                    resolvedProjection,
                    targetType,
                    prioritizeTargetType,
                    typeIdentities);
        }
        String contractPointer = JsonPointer.append(
                JsonPointer.append(
                        scopePath,
                        ProcessorContractConstants.KEY_CONTRACTS),
                contractKey);
        Node snapshotContract = snapshot.resolvedNodeAt(contractPointer);
        T converted;
        if (snapshotContract == null
                || snapshotContract.isReferenceOnly()
                || snapshotContract.getType() == null) {
            /*
             * Contracts may deliberately leave a provider-backed contract
             * contribution cold in the Language snapshot. The contract
             * loader has independently verified and materialized the header,
             * but that detached value must not be paired with the snapshot's
             * canonical-content lane. Type evidence is sufficient for class
             * selection; any @BlueId field therefore continues to fail closed
             * in the detached mapper.
             */
            converted = converter.convertWithType(
                    resolvedProjection,
                    targetType,
                    prioritizeTargetType,
                    typeIdentities);
        } else {
            converted = converter.convertWithTypeOmittingProperties(
                    snapshot,
                    contractPointer,
                    omittedProperties,
                    targetType,
                    prioritizeTargetType);
        }
        if (converted != null) {
            ExactContractNodeFieldRestorer.restore(
                    converted,
                    resolvedProjection);
        }
        return converted;
    }
}
