package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

/**
 * Closed authority for an unchanged, previously verified exact component.
 *
 * <p>This is not a digest attached to asserted host metadata. Only the owning
 * snapshot verifier can mint it, after checking actual bodies, markers,
 * occurrence values and complete Language finalization. It retains headers and
 * exact outgoing rows, but neither document bodies nor a cyclic proof payload.
 * A new partition, changed outgoing reference, or changed member state cannot
 * reuse this authority.</p>
 */
public final class ReusableComponentAuthority {
    private final ComponentSnapshot component;
    private final Map<DocumentId, Header> headers;
    private final List<ManagedOccurrenceBinding> outgoingBindings;
    private final Map<DocumentId, Integer> canonicalMemberIndexes;
    private final Map<DocumentId, String> preliminaryBlueIds;
    private final ReusableComponentAuthority canonical;

    private ReusableComponentAuthority(ComponentSnapshot component, Map<DocumentId, Header> headers,
            List<ManagedOccurrenceBinding> bindings, Map<DocumentId, Integer> indexes,
            Map<DocumentId, String> preliminary, ReusableComponentAuthority canonical) {
        this.component = ComponentSnapshot.verifiedHeader(component);
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<DocumentId, Header>(headers));
        this.outgoingBindings = Collections.unmodifiableList(new ArrayList<ManagedOccurrenceBinding>(bindings));
        this.canonicalMemberIndexes = Collections.unmodifiableMap(new LinkedHashMap<DocumentId, Integer>(indexes));
        this.preliminaryBlueIds = Collections.unmodifiableMap(new LinkedHashMap<DocumentId, String>(preliminary));
        this.canonical = canonical == null ? this : canonical;
    }

    /** Called only after the complete selected snapshot has passed owning verification. */
    static ReusableComponentAuthority captureVerified(AffectedClosureSnapshot snapshot,
            FinalizedComponentEvidence finalized, Map<DocumentId, RootChannelMetadata> metadata) {
        ComponentSnapshot component = finalized.component();
        List<ManagedOccurrenceBinding> outgoing = new ArrayList<ManagedOccurrenceBinding>();
        Map<DocumentId, Header> original = new TreeMap<DocumentId, Header>();
        Map<DocumentId, Header> normalized = new TreeMap<DocumentId, Header>();
        for (DocumentId member : component.orderedMemberDocumentIds()) {
            ManagedDocumentSnapshot document = Objects.requireNonNull(snapshot.managedDocument(member), "verified member");
            RootChannelMetadata root = metadata.get(member);
            if (root == null) root = document.rootMetadata().orElse(null);
            if (root != null) root.verifyState(document);
            String exactBodyIdentity = DirectBlueIdCalculator.calculateBlueId(document.document());
            original.put(member, new Header(document, root, document.publicRoot(), document.componentGeneration(), exactBodyIdentity));
            normalized.put(member, new Header(document, root, true, 0L, exactBodyIdentity));
        }
        for (DocumentId member : original.keySet()) outgoing.addAll(snapshot.occurrencesFrom(member));
        Collections.sort(outgoing);
        ComponentSnapshot canonicalComponent;
        if (component.componentGeneration() == 0L) {
            canonicalComponent = component;
        } else if (component.kind() == ComponentKind.ACYCLIC) {
            ManagedDocumentSnapshot member = snapshot.managedDocument(component.orderedMemberDocumentIds().get(0));
            canonicalComponent = ClosureEvidenceFactory.acyclicComponent(
                    new ManagedDocumentSnapshot(member.documentId(), member.blueId(), member.document(),
                            member.initialized(), member.terminated(), true, member.epoch(), 0L));
        } else {
            canonicalComponent = ClosureEvidenceFactory.cyclicComponent(0L, component.orderedMemberDocumentIds(),
                    component.orderedMemberBlueIds(), component.masterBlueId(), component.completeCyclicProof());
        }
        ReusableComponentAuthority canonical = new ReusableComponentAuthority(canonicalComponent, normalized, outgoing,
                finalized.canonicalMemberIndexes(), finalized.preliminaryBlueIds(), null);
        return new ReusableComponentAuthority(component, original, outgoing,
                finalized.canonicalMemberIndexes(), finalized.preliminaryBlueIds(), canonical);
    }

    public ComponentSnapshot component() { return component; }
    public List<ManagedDocumentSnapshot> memberHeaders() {
        List<ManagedDocumentSnapshot> result = new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId member : headers.keySet()) result.add(memberHeader(member));
        return Collections.unmodifiableList(result);
    }
    public ManagedDocumentSnapshot memberHeader(DocumentId member) {
        Header header = Objects.requireNonNull(headers.get(member), "Component does not own member");
        return header.snapshot(this, null);
    }
    public List<ManagedOccurrenceBinding> outgoingBindings() { return outgoingBindings; }
    public Map<DocumentId, Integer> canonicalMemberIndexes() { return canonicalMemberIndexes; }
    public Map<DocumentId, String> preliminaryBlueIds() { return preliminaryBlueIds; }
    /** Exact local-body content identity, distinct from a cyclic member's MASTER#index identity. */
    public String exactBodyIdentity(DocumentId member) {
        return Objects.requireNonNull(headers.get(member), "Component does not own member").exactBodyIdentity;
    }
    Map<DocumentId, Header> transportHeaders() { return headers; }

    /** Package-owned transport reconstruction: its complete root must already be authenticated. */
    static ReusableComponentAuthority restoreAuthenticated(ComponentSnapshot component, Map<DocumentId, Header> headers,
            List<ManagedOccurrenceBinding> rows, Map<DocumentId, Integer> indexes, Map<DocumentId, String> preliminary,
            ComponentSnapshot canonicalComponent) {
        if (!component.orderedMemberDocumentIds().equals(new ArrayList<DocumentId>(headers.keySet())))
            throw new IllegalArgumentException("Authenticated member headers do not cover their component in canonical order");
        List<ManagedOccurrenceBinding> sorted = new ArrayList<ManagedOccurrenceBinding>(rows);
        Collections.sort(sorted);
        java.util.Set<String> occurrences = new java.util.HashSet<String>();
        for (ManagedOccurrenceBinding row : sorted) {
            if (!headers.containsKey(row.sourceDocumentId()) || !occurrences.add(row.occurrenceIdentity()))
                throw new IllegalArgumentException("Authenticated outgoing rows have foreign or duplicate ownership");
        }
        for (int i = 0; i < component.orderedMemberDocumentIds().size(); i++) {
            DocumentId member = component.orderedMemberDocumentIds().get(i);
            Header header = headers.get(member);
            if (!header.blueId.equals(component.orderedMemberBlueIds().get(i)) || header.generation != component.componentGeneration())
                throw new IllegalArgumentException("Authenticated member header disagrees with its component");
            if (component.kind() == ComponentKind.CYCLIC) {
                Integer index = indexes.get(member);
                if (index == null || !header.blueId.equals(component.masterBlueId() + "#" + index)
                        || preliminary.get(member) == null)
                    throw new IllegalArgumentException("Authenticated cyclic member mapping is incomplete");
                ClosureValueSupport.requireBlueId(preliminary.get(member), "preliminaryBlueId");
            }
        }
        if (component.kind() == ComponentKind.ACYCLIC ? !indexes.isEmpty() || !preliminary.isEmpty()
                : !indexes.keySet().equals(headers.keySet()) || !preliminary.keySet().equals(headers.keySet()))
            throw new IllegalArgumentException("Authenticated canonical member maps have invalid coverage");
        if (canonicalComponent.componentGeneration() != 0L || component.kind() != canonicalComponent.kind()
                || !component.orderedMemberDocumentIds().equals(canonicalComponent.orderedMemberDocumentIds())
                || !component.orderedMemberBlueIds().equals(canonicalComponent.orderedMemberBlueIds())
                || !Objects.equals(component.masterBlueId(), canonicalComponent.masterBlueId()))
            throw new IllegalArgumentException("Authenticated canonical metadata projection changes semantic members");
        Map<DocumentId, Header> normalized = new LinkedHashMap<DocumentId, Header>();
        for (Header header : headers.values()) normalized.put(header.documentId, new Header(header.documentId, header.blueId,
                header.initialized, header.terminated, true, header.epoch, 0L, header.exactBodyIdentity, header.metadata));
        ReusableComponentAuthority canonical = new ReusableComponentAuthority(canonicalComponent, normalized, sorted, indexes, preliminary, null);
        ReusableComponentAuthority result = new ReusableComponentAuthority(component, headers, sorted, indexes, preliminary, canonical);
        for (Header header : headers.values()) if (header.metadata != null) header.metadata.verifyState(result.memberHeader(header.documentId));
        return result;
    }
    /** Captured exact local-body identity also covers cyclic members independently of their MASTER suffix. */
    public boolean matchesBody(DocumentId member, Node body) {
        Header header = headers.get(member);
        return header != null && body != null
                && header.exactBodyIdentity.equals(DirectBlueIdCalculator.calculateBlueId(body));
    }

    /** Complete rows, not only active edges: activation and pending-lane changes invalidate reuse. */
    public boolean matchesOutgoingBindings(Collection<ManagedOccurrenceBinding> rows) {
        List<ManagedOccurrenceBinding> selected = new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding row : rows) if (headers.containsKey(row.sourceDocumentId())) selected.add(row);
        Collections.sort(selected);
        if (selected.size() != outgoingBindings.size()) return false;
        for (int index = 0; index < selected.size(); index++) {
            ManagedOccurrenceBinding left = selected.get(index), right = outgoingBindings.get(index);
            if (!left.occurrenceIdentity().equals(right.occurrenceIdentity())
                    || !left.bindingIdentity().equals(right.bindingIdentity())
                    || left.active() != right.active()
                    || !Objects.equals(left.pendingHistoricalEpoch(), right.pendingHistoricalEpoch())) return false;
        }
        return true;
    }

    /** Checks exact state and topology, independent of which bodies currently reside in memory. */
    public void verifyUnchanged(AffectedClosureSnapshot snapshot) {
        List<ManagedOccurrenceBinding> outgoing = new ArrayList<ManagedOccurrenceBinding>();
        for (DocumentId member : headers.keySet()) outgoing.addAll(snapshot.occurrencesFrom(member));
        if (!matchesOutgoingBindings(outgoing))
            throw new IllegalArgumentException("Unchanged component authority belongs to different outgoing occurrence state");
        for (DocumentId member : headers.keySet()) {
            if (!matchesHeader(snapshot.managedDocument(member)))
                throw new IllegalArgumentException("Unchanged component authority belongs to different member state");
        }
        ComponentSnapshot current = snapshot.component(component.orderedMemberDocumentIds().get(0));
        boolean found = current != null && component.componentIdentity().equals(current.componentIdentity())
                && component.componentStateIdentity().equals(current.componentStateIdentity())
                && component.orderedMemberDocumentIds().equals(current.orderedMemberDocumentIds())
                && component.orderedMemberBlueIds().equals(current.orderedMemberBlueIds());
        if (!found) throw new IllegalArgumentException("Unchanged component authority does not establish the current partition");
    }

    boolean matchesHeader(ManagedDocumentSnapshot document) {
        Header header = document == null ? null : headers.get(document.documentId());
        return header != null && header.blueId.equals(document.blueId())
                && header.initialized == document.initialized() && header.terminated == document.terminated()
                && header.publicRoot == document.publicRoot() && header.epoch == document.epoch()
                && header.generation == document.componentGeneration();
    }
    ReusableComponentAuthority canonicalSemanticAuthority() { return canonical; }
    ReusableComponentAuthority withMetadata(Map<DocumentId, RootChannelMetadata> metadata) {
        Map<DocumentId, Header> updated = new LinkedHashMap<DocumentId, Header>();
        Map<DocumentId, Header> normalized = new LinkedHashMap<DocumentId, Header>();
        for (DocumentId member : headers.keySet()) {
            Header previous = headers.get(member);
            RootChannelMetadata root = metadata.containsKey(member) ? metadata.get(member) : previous.metadata;
            if (root != null) root.verifyState(memberHeader(member));
            updated.put(member, new Header(memberHeader(member), root, previous.publicRoot, previous.generation, previous.exactBodyIdentity));
            normalized.put(member, new Header(canonical.memberHeader(member), root, true, 0L, previous.exactBodyIdentity));
        }
        ReusableComponentAuthority normalizedAuthority = new ReusableComponentAuthority(canonical.component, normalized,
                outgoingBindings, canonicalMemberIndexes, preliminaryBlueIds, null);
        return new ReusableComponentAuthority(component, updated, outgoingBindings,
                canonicalMemberIndexes, preliminaryBlueIds, normalizedAuthority);
    }
    ManagedDocumentSnapshot retainingCanonicalBody(ManagedDocumentSnapshot original) {
        ReusableComponentAuthority previous = original.reusableAuthority().orElse(null);
        if (previous == null || previous.canonical != this || !previous.matchesHeader(original))
            throw new IllegalArgumentException("Canonical metadata projection requires original owning authority");
        return headers.get(original.documentId()).snapshot(this, original.hasResidentBody() ? original.document() : null);
    }
    ManagedDocumentSnapshot retainingBody(ManagedDocumentSnapshot original, boolean resident) {
        if (!matchesHeader(original)) throw new IllegalArgumentException("Body residency cannot change semantic state");
        return headers.get(original.documentId()).snapshot(this, resident ? original.document() : null);
    }

    static final class Header {
        final DocumentId documentId;
        final String blueId;
        final boolean initialized, terminated, publicRoot;
        final long epoch, generation;
        final RootChannelMetadata metadata;
        final String exactBodyIdentity;
        Header(ManagedDocumentSnapshot value, RootChannelMetadata metadata, boolean publicRoot, long generation, String exactBodyIdentity) {
            this(value.documentId(), value.blueId(), value.initialized(), value.terminated(), publicRoot,
                    value.epoch(), generation, exactBodyIdentity, metadata);
        }
        Header(DocumentId documentId, String blueId, boolean initialized, boolean terminated, boolean publicRoot,
                long epoch, long generation, String exactBodyIdentity, RootChannelMetadata metadata) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.blueId = ClosureValueSupport.requireBlueId(blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
            this.initialized = initialized; this.terminated = terminated; this.publicRoot = publicRoot;
            this.epoch = ClosureValueSupport.requireSafeInteger(epoch, "epoch");
            this.generation = ClosureValueSupport.requireSafeInteger(generation, "componentGeneration");
            this.metadata = metadata;
            this.exactBodyIdentity = blue.language.identity.BlueIds.requirePlainBlueId(exactBodyIdentity, "exactBodyIdentity");
        }
        ManagedDocumentSnapshot snapshot(ReusableComponentAuthority authority, blue.language.model.Node body) {
            return ManagedDocumentSnapshot.fromVerifiedHeader(documentId, blueId, body, initialized, terminated,
                    publicRoot, epoch, generation, authority, metadata);
        }
    }
}
