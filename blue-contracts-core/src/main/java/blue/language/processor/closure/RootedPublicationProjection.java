package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Processor-derived authority over a complete calculated rooted result.
 *
 * <p>The unchanged base result authenticates every calculated exact value,
 * receipt and gas charge. This adjunct retains the frozen entry context and
 * the monotone owner set computed at the real finalizer boundaries. It has no
 * public constructor or writable-member setter. A public base-result constructor
 * alone cannot manufacture rooted publication authority.</p>
 */
public final class RootedPublicationProjection {
    private final RootedProcessingContext context;
    private final String deliveryBasisIdentity;
    private final String invocationIdentity;
    private final String companionIdentity;
    private final List<DocumentId> ownedDocumentIds;
    private final List<ResultingDocument> ownedDocuments;
    private final List<ResultingDocument> embeddedViewDocuments;
    private final List<AffectedClosureSnapshot> topologyBoundaries;
    private final AffectedClosureSnapshot inputSnapshot;
    private final AffectedClosureSnapshot resultingSnapshot;
    private final List<ComponentSnapshot> ownedComponents;
    private final List<CheckpointWrite> ownedCheckpointWrites;
    private final List<PublicEventOccurrence> ownedPublicEvents;
    private final List<SubscriptionDelta> ownedSubscriptionDeltas;
    private final java.util.Map<DocumentId, String> checkpointReferenceProofs;

    RootedPublicationProjection(ClosureProcessResult result, RootedOwnershipTracker.Snapshot ownership) {
        if (!result.commits() || result.commitCompanion() == null || ownership.boundaries.isEmpty()) {
            throw new IllegalArgumentException("Rooted publication requires a complete successful execution");
        }
        this.context = ownership.binding.context;
        this.deliveryBasisIdentity = ownership.binding.deliveryIdentity;
        this.invocationIdentity = context.invocationIdentity(
                ownership.binding.entryInvocationIdentity, deliveryBasisIdentity);
        this.companionIdentity = context.commitCompanionIdentity(
                result.commitCompanion().companionIdentity(), invocationIdentity);
        this.ownedDocumentIds = ownership.owners;
        this.topologyBoundaries = ownership.boundaries;
        this.inputSnapshot = ownership.inputSnapshot;
        if (!inputSnapshot.closureIdentity().equals(result.inputClosureIdentity())) {
            throw new IllegalArgumentException("Rooted execution and base result input disagree");
        }
        List<ResultingDocument> owned = new ArrayList<>();
        List<ResultingDocument> embedded = new ArrayList<>();
        for (ResultingDocument document : result.resultingDocuments()) {
            (owns(document.documentId()) ? owned : embedded).add(document);
        }
        if (owned.size() != ownedDocumentIds.size()) {
            throw new IllegalArgumentException("Calculated result omits a derived owner");
        }
        this.ownedDocuments = Collections.unmodifiableList(owned);
        this.embeddedViewDocuments = Collections.unmodifiableList(embedded);
        List<ManagedDocumentSnapshot> calculated = new ArrayList<>();
        List<DocumentId> publicRoots = new ArrayList<>();
        for (ResultingDocument document : result.resultingDocuments()) {
            calculated.add(new ManagedDocumentSnapshot(document.documentId(), document.afterBlueId(),
                    document.document(), document.initialized(), document.terminated(), document.publicRoot(),
                    document.epoch(), document.componentGeneration()));
            if (document.publicRoot()) publicRoots.add(document.documentId());
        }
        this.resultingSnapshot = new AffectedClosureSnapshot(result.outputClosureIdentity(), result.graphGeneration(), calculated,
                result.occurrenceBindings(), result.occurrenceBindingSetIdentity(), result.resultingComponents(), publicRoots,
                ownership.boundaries.get(ownership.boundaries.size() - 1).rootedWitnesses());
        ClosureEvidenceVerifier.verifySnapshot(resultingSnapshot);
        if (!resultingSnapshot.closureIdentity().equals(result.outputClosureIdentity())) {
            throw new IllegalArgumentException("Rooted projection differs from the complete computed result");
        }
        List<ComponentSnapshot> components = new ArrayList<>();
        for (ComponentSnapshot component : result.resultingComponents()) {
            if (!Collections.disjoint(component.orderedMemberDocumentIds(), ownedDocumentIds)) {
                if (!ownedDocumentIds.containsAll(component.orderedMemberDocumentIds())) {
                    throw new IllegalArgumentException("Rooted publication cannot split a resulting live component");
                }
                components.add(component);
            }
        }
        this.ownedComponents = Collections.unmodifiableList(components);
        List<CheckpointWrite> checkpoints = new ArrayList<>();
        for (CheckpointWrite write : result.checkpointWrites()) {
            if (owns(write.targetManagedScopeKey().documentId())) checkpoints.add(write);
        }
        this.ownedCheckpointWrites = Collections.unmodifiableList(checkpoints);
        List<PublicEventOccurrence> events = new ArrayList<>();
        for (PublicEventOccurrence event : result.publicEvents()) {
            if (owns(event.publicRootDocumentId())) events.add(event);
        }
        this.ownedPublicEvents = Collections.unmodifiableList(events);
        List<SubscriptionDelta> subscriptions = new ArrayList<>();
        for (SubscriptionDelta delta : result.subscriptionDeltas()) {
            SubscriptionState representative = delta.afterSubscription() == null
                    ? delta.beforeSubscription() : delta.afterSubscription();
            if (owns(representative.channelOccurrence().managedDocumentId())) subscriptions.add(delta);
        }
        this.ownedSubscriptionDeltas = Collections.unmodifiableList(subscriptions);
        java.util.Map<DocumentId, String> proofs = new java.util.LinkedHashMap<>();
        for (DocumentId document : ownership.checkpointPredecessors.keySet()) {
            AffectedClosureSnapshot previous = ownership.checkpointPredecessors.get(document);
            AffectedClosureSnapshot next = ownership.checkpointSuccessors.get(document);
            ManagedDocumentSnapshot before = inputSnapshot.managedDocument(document);
            ManagedDocumentSnapshot after = resultingSnapshot.managedDocument(document);
            if (next == null || before == null || after == null
                    || !before.blueId().equals(previous.managedDocument(document).blueId())
                    || !after.blueId().equals(next.managedDocument(document).blueId())
                    || before.epoch() != after.epoch() || !before.initialized() || !after.initialized()
                    || before.terminated() || after.terminated() || before.publicRoot() != after.publicRoot()
                    || !result.graphChanges().isEmpty()
                    || result.checkpointWrites().stream().anyMatch(write -> write.targetManagedScopeKey().documentId().equals(document))
                    || result.managedTransitionReceipts().stream().noneMatch(receipt -> receipt.documentId().equals(document)
                            && receipt.emittedRootEvents().isEmpty())) continue;
            java.util.Map<String, Object> proof = new java.util.LinkedHashMap<>();
            proof.put("documentId", document.value());
            proof.put("beforeBlueId", before.blueId()); proof.put("afterBlueId", after.blueId());
            proof.put("checkpointInputClosureIdentity", previous.closureIdentity());
            proof.put("checkpointOutputClosureIdentity", next.closureIdentity());
            proof.put("rootProcessingContextIdentity", context.identity());
            proof.put("rootedInvocationIdentity", invocationIdentity);
            proof.put("rootedCommitCompanionIdentity", companionIdentity);
            proofs.put(document, ClosureIdentityService.INSTANCE.identity(
                    ClosureIdentityService.Constructor.ROOTED_CHECKPOINT_REFERENCE_PROOF, proof));
        }
        this.checkpointReferenceProofs = Collections.unmodifiableMap(proofs);
    }

    /** Returns the original entry context.
     * @return frozen context */
    public RootedProcessingContext context() { return context; }

    /** Returns the exact receiving/cause identity.
     * @return delivery identity */
    public String deliveryBasisIdentity() { return deliveryBasisIdentity; }

    /** Returns the draft.2 invocation wrapper.
     * @return rooted invocation identity */
    public String invocationIdentity() { return invocationIdentity; }

    /** Returns the draft.2 companion wrapper.
     * @return rooted companion identity */
    public String companionIdentity() { return companionIdentity; }

    /** Returns the complete, monotone publication set.
     * @return canonical owner IDs */
    public List<DocumentId> ownedDocumentIds() { return ownedDocumentIds; }

    /** Tests processor-derived publication authority.
     * @param documentId exact lineage
     * @return whether this lineage is owned by the invocation */
    public boolean owns(DocumentId documentId) { return ownedDocumentIds.contains(documentId); }

    /**
     * Returns the processor-produced proof for an entry owner's sole checkpoint-finalizer change.
     * This hash is not external authority; historical use must authenticate the original rooted publication.
     * @param documentId original owned document
     * @return exact proof bound to its real typed boundary and rooted companion, or empty
     */
    public java.util.Optional<String> checkpointReferenceProofIdentity(DocumentId documentId) {
        return java.util.Optional.ofNullable(checkpointReferenceProofs.get(documentId));
    }

    /** Returns exact owner results, including unchanged owners.
     * @return owner results */
    public List<ResultingDocument> ownedDocuments() { return ownedDocuments; }

    /** Returns calculated dependency views that cannot replace source heads.
     * @return complete local dependency results */
    public List<ResultingDocument> embeddedViewDocuments() { return embeddedViewDocuments; }

    /** Returns the actual verified topology-boundary states retained by the processor.
     * @return immutable ordered boundary evidence */
    public List<AffectedClosureSnapshot> topologyBoundaries() { return topologyBoundaries; }

    /** Returns the exact selected input views, including immutable dependency reads.
     * @return complete selected input */
    public AffectedClosureSnapshot inputSnapshot() { return inputSnapshot; }

    /** Returns the complete computed views, without conferring source publication authority.
     * @return complete selected output */
    public AffectedClosureSnapshot resultingSnapshot() { return resultingSnapshot; }

    AffectedClosureSnapshot retainedSnapshot(java.util.Map<DocumentId, Long> epochs) {
        if (epochs == null || !epochs.keySet().equals(new java.util.HashSet<>(ownedDocumentIds)))
            throw new IllegalArgumentException("Retained positions must cover exactly the derived owners");
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ManagedDocumentSnapshot document : resultingSnapshot.managedDocuments()) {
            Long proposed = epochs.get(document.documentId());
            if (owns(document.documentId())) {
                if (proposed == null || proposed < document.epoch() || proposed > document.epoch() + 1L)
                    throw new IllegalArgumentException("Retained position must preserve the epoch or record one host revision");
                documents.add(new ManagedDocumentSnapshot(document.documentId(), document.blueId(), document.document(),
                        document.initialized(), document.terminated(), document.publicRoot(), proposed,
                        document.componentGeneration()));
            } else documents.add(document);
        }
        AffectedClosureSnapshot source = resultingSnapshot;
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(source.closureIdentity(), source.graphGeneration(),
                documents, source.occurrences(), source.occurrenceBindingSetIdentity(), source.components(),
                source.publicRootDocumentIds(), source.rootedWitnesses());
        AffectedClosureSnapshot retained = new AffectedClosureSnapshot(
                ClosureIdentityService.INSTANCE.affectedClosureIdentity(provisional), source.graphGeneration(),
                documents, source.occurrences(), source.occurrenceBindingSetIdentity(), source.components(),
                source.publicRootDocumentIds(), source.rootedWitnesses());
        ClosureEvidenceVerifier.verifySnapshot(retained);
        return retained;
    }

    /** Returns complete owned components, never partial cyclic proofs.
     * @return exact owned component results */
    public List<ComponentSnapshot> ownedComponents() { return ownedComponents; }

    /** Returns owned checkpoint mutations with their original occurrence ordinals.
     * @return exact owned checkpoint evidence */
    public List<CheckpointWrite> ownedCheckpointWrites() { return ownedCheckpointWrites; }

    /** Returns owned public emissions with their original occurrence ordinals.
     * @return exact owned outbox evidence */
    public List<PublicEventOccurrence> ownedPublicEvents() { return ownedPublicEvents; }

    /** Returns owned subscription mutations.
     * @return exact owned subscription evidence */
    public List<SubscriptionDelta> ownedSubscriptionDeltas() { return ownedSubscriptionDeltas; }
}
