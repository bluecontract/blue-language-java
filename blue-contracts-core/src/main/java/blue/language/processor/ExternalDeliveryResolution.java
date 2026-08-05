package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
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
        Node selected;
        if (snapshot != null) {
            if (JsonPointer.ROOT.equals(
                    PointerUtils.normalizeScope(scopePath))) {
                selected = snapshot.canonicalRoot();
            } else {
                selected = snapshot.canonicalNodeAt(scopePath);
            }
        } else {
            selected = ExternalEvidenceVerificationSupport.nodeAt(
                    root, scopePath);
        }
        return projectionBuilder.materializeSelectedScope(selected);
    }

    Node effectiveNodeAt(String scopePath) {
        Node effective;
        if (snapshot != null) {
            if (JsonPointer.ROOT.equals(
                    PointerUtils.normalizeScope(scopePath))) {
                effective = snapshot.resolvedRoot();
            } else {
                effective = snapshot.resolvedNodeAt(scopePath);
            }
        } else {
            effective = ExternalEvidenceVerificationSupport.nodeAt(
                    root, scopePath);
            if (effective != null && effective.getType() != null) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Inherited effective scope resolution requires a "
                                + "configured ProcessingSnapshotManager at "
                                + scopePath);
            }
        }
        return projectionBuilder.materializeEffectiveScope(effective);
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

    /** Plans concrete embedded children against this resolution's full scope. */
    EmbeddedScopePlan embeddedScopePlanAt(
            String scopePath,
            ContractBundle bundle) {
        if (bundle == null || !bundle.hasProcessEmbedded()) {
            return null;
        }
        Node effective = effectiveNodeAt(scopePath);
        if (effective == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Scope is absent: " + scopePath);
        }
        EmbeddedScopeDeclaration declaration =
                bundle.embeddedScopeDeclaration();
        return projectionBuilder.embeddedScopePlanner()
                .planForRevisionBoundEvent(
                FrozenNode.fromResolvedNode(effective),
                scopePath,
                declaration.explicitPaths(),
                declaration.collectionPaths(),
                GasSchedule.contracts10());
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
        FrozenNode selectedFrozen;
        if (snapshot != null) {
            FrozenNode canonical = snapshot.canonicalAt(scopePath);
            /*
             * A pure-reference canonical fragment carries only its identity.
             * Contract contribution proof needs the verified exact selected
             * content that selectedNodeAt already materialized.
             */
            selectedFrozen = canonical != null && canonical.isReferenceOnly()
                    ? FrozenNode.fromResolvedNode(selected)
                    : canonical;
        } else {
            selectedFrozen = FrozenNode.fromResolvedNode(selected);
        }
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
