package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.JsonPointer;
import blue.language.processor.util.PointerUtils;

import java.util.Collections;
import java.util.Set;

/** Selected/effective scope view used during external-evidence verification. */
final class ExternalDeliveryResolution implements AutoCloseable {

    private final ContractLoader contractLoader;
    private final ExternalSubscriptionProjectionBuilder projectionBuilder;
    private final Node root;
    private final ResolvedSnapshot snapshot;

    ExternalDeliveryResolution(
            ContractLoader contractLoader,
            ExternalSubscriptionProjectionBuilder projectionBuilder,
            Node root,
            ResolvedSnapshot snapshot) {
        this.contractLoader = contractLoader;
        this.projectionBuilder = projectionBuilder;
        this.root = root;
        this.snapshot = snapshot;
    }

    Node selectedNodeAt(String scopePath) {
        if (snapshot != null) {
            if (JsonPointer.ROOT.equals(
                    PointerUtils.normalizeScope(scopePath))) {
                return snapshot.canonicalRoot();
            }
            Node selected = snapshot.canonicalNodeAt(scopePath);
            return selected != null ? selected : null;
        }
        return ExternalEvidenceVerificationSupport.nodeAt(
                root, scopePath);
    }

    Node effectiveNodeAt(String scopePath) {
        if (snapshot != null) {
            if (JsonPointer.ROOT.equals(
                    PointerUtils.normalizeScope(scopePath))) {
                return snapshot.resolvedRoot();
            }
            return snapshot.resolvedNodeAt(scopePath);
        }
        Node selected = ExternalEvidenceVerificationSupport.nodeAt(
                root, scopePath);
        if (selected != null && selected.getType() != null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Inherited effective scope resolution requires a "
                            + "configured ProcessingSnapshotManager at "
                            + scopePath);
        }
        return selected;
    }

    ContractBundle bundleAt(String scopePath) {
        if (snapshot != null) {
            return contractLoader.load(snapshot, scopePath);
        }
        Node selected = effectiveNodeAt(scopePath);
        if (selected == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Scope is absent: " + scopePath);
        }
        return contractLoader.load(
                FrozenNode.fromResolvedNode(selected),
                scopePath);
    }

    ContractBundle subscriptionBundleAt(String scopePath) {
        return subscriptionBundleAt(
                scopePath, (Set<String>) null, true);
    }

    ContractBundle subscriptionBundleAt(
            String scopePath,
            String retainedChannelKey,
            boolean includeProcessEmbedded) {
        return subscriptionBundleAt(
                scopePath,
                retainedChannelKey != null
                        ? Collections.singleton(retainedChannelKey)
                        : Collections.emptySet(),
                includeProcessEmbedded);
    }

    ContractBundle subscriptionBundleAt(
            String scopePath,
            Set<String> retainedChannelKeys,
            boolean includeProcessEmbedded) {
        Node selected = selectedNodeAt(scopePath);
        Node effective = effectiveNodeAt(scopePath);
        if (selected == null || effective == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Scope is absent: " + scopePath);
        }
        FrozenNode selectedFrozen = snapshot != null
                ? snapshot.canonicalAt(scopePath)
                : FrozenNode.fromResolvedNode(selected);
        return contractLoader.load(
                selectedFrozen,
                projectionBuilder.subscriptionProjection(
                        effective,
                        retainedChannelKeys,
                        includeProcessEmbedded),
                scopePath);
    }

    @Override
    public void close() {
        // The configured manager is processor-owned and remains reusable.
    }
}
