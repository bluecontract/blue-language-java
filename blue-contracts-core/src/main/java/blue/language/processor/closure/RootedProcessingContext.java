package blue.language.processor.closure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Frozen entry owner of a rooted checkpoint operation.
 *
 * <p>The owner is derived from the exact live graph, never from a supplied
 * writable-member list. History identities supplied here must already have
 * been established from retained initial content, runtime binding and admission
 * evidence. This value verifies topology and envelope construction; it does
 * not authenticate an arbitrary history hash.</p>
 */
public final class RootedProcessingContext {
    /** Exact reviewed Contracts specification for this draft.2 profile. */
    public static final String CONTRACTS_SPECIFICATION_IDENTITY =
            "sha256:08755625c221dc9b64c6ea61707326581bfa91167644e08061fd23373cb7f172";

    private final List<DocumentId> entryOwners;
    private final Map<String, Object> ownerDescriptor;
    private final Map<String, Object> descriptor;
    private final String identity;
    private final String entryClosureIdentity;

    private RootedProcessingContext(List<DocumentId> owners,
            Map<String, Object> ownerDescriptor, String entryClosureIdentity) {
        this.entryOwners = Collections.unmodifiableList(new ArrayList<DocumentId>(owners));
        this.ownerDescriptor = RootedIdentity.owner(ownerDescriptor);
        this.descriptor = RootedIdentity.context(this.ownerDescriptor);
        this.identity = RootedIdentity.contextIdentity(this.ownerDescriptor);
        this.entryClosureIdentity = entryClosureIdentity;
    }

    /**
     * Derives the entry SCC from complete exact snapshot evidence.
     *
     * @param snapshot selected forward view with independently verified exact content
     * @param requestedRoot requested document view, not an alternative cause
     * @param historyBasisIdentities established history identity for every entry owner
     *
     * @return immutable canonical entry context
     */
    public static RootedProcessingContext derive(AffectedClosureSnapshot snapshot,
            DocumentId requestedRoot, Map<DocumentId, String> historyBasisIdentities) {
        AffectedClosureSnapshot selected = Objects.requireNonNull(snapshot, "snapshot");
        ClosureEvidenceVerifier.verifySnapshot(selected);
        List<DocumentId> all = new ArrayList<DocumentId>();
        for (ManagedDocumentSnapshot document : selected.managedDocuments()) {
            all.add(document.documentId());
        }
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(all, selected.occurrences());
        if (!graph.contains(Objects.requireNonNull(requestedRoot, "requestedRoot"))) {
            throw new IllegalArgumentException("Requested root is absent from the exact view");
        }
        requireForwardClosure(graph, requestedRoot);
        List<DocumentId> owners = null;
        for (List<DocumentId> component : new SccPartitioner().partition(graph)) {
            if (component.contains(requestedRoot)) {
                owners = component;
                break;
            }
        }
        if (owners == null) {
            throw new IllegalStateException("Verified graph has no component for its root");
        }
        Map<DocumentId, String> histories = Objects.requireNonNull(
                historyBasisIdentities, "historyBasisIdentities");
        if (!histories.keySet().equals(new LinkedHashSet<DocumentId>(owners))) {
            throw new IllegalArgumentException("History evidence must name exactly the entry owners");
        }
        List<Map<String, Object>> members = new ArrayList<Map<String, Object>>();
        for (DocumentId owner : owners) {
            members.add(object("documentId", owner.value(),
                    "historyBasisIdentity", histories.get(owner)));
        }
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        for (ManagedOccurrenceBinding edge : graph.activeBindings()) {
            if (owners.contains(edge.sourceDocumentId()) && owners.contains(edge.targetDocumentId())) {
                edges.add(object("occurrenceIdentity", edge.occurrenceIdentity(),
                        "parentDocumentId", edge.sourceDocumentId().value(),
                        "sourcePath", edge.sourcePath(), "childDocumentId", edge.targetDocumentId().value(),
                        "activationGeneration", Long.toString(edge.activationGeneration())));
            }
        }
        return new RootedProcessingContext(owners, object("members", members, "internalEdges", edges),
                selected.closureIdentity());
    }

    void requireEntrySnapshot(AffectedClosureSnapshot snapshot) {
        if (!entryClosureIdentity.equals(snapshot.closureIdentity())) {
            throw new IllegalArgumentException("Root context belongs to another exact entry snapshot");
        }
    }

    /**
     * Constructs the closed history envelope after admission authentication.
     *
     * @param evidence exact draft.2 history record
     *
     * @return canonical history identity; hashing alone is not admission proof
     */
    public static String historyBasisIdentity(Map<String, Object> evidence) {
        return RootedIdentity.history(evidence);
    }

    /** Returns the canonical first entry owner.
     * @return canonical root */
    public DocumentId canonicalRootDocumentId() { return entryOwners.get(0); }

    /** Returns the fixed entry SCC.
     * @return canonical immutable owner list */
    public List<DocumentId> entryOwners() { return entryOwners; }

    /** Returns the exact owner descriptor.
     * @return closed immutable descriptor */
    public Map<String, Object> ownerDescriptor() { return ownerDescriptor; }

    /** Returns the closed processing context.
     * @return closed immutable descriptor */
    public Map<String, Object> descriptor() { return descriptor; }

    /** Returns the canonical context identity.
     * @return draft.2 context identity */
    public String identity() { return identity; }

    /** Returns the owner identity.
     * @return draft.2 owner identity */
    public String operationOwnerIdentity() {
        return (String) descriptor.get("operationOwnerIdentity");
    }

    /**
     * Binds an already authenticated receiving set and retained position.
     * @param causeIdentity exact original cause identity
     * @param kind ADMISSION, LIVE, MANAGED_REVISION or MANAGED_REPRESENTATION
     * @param bindings actual checkpoint-owning channel occurrences
     * @param sourcePositionIdentity null for LIVE/admission, exact retained position otherwise
     *
     * @return canonical draft.2 delivery identity
     */
    public String deliveryBasisIdentity(String causeIdentity, String kind,
            List<ChannelOccurrence> bindings, String sourcePositionIdentity) {
        List<Map<String, Object>> receiving = new ArrayList<Map<String, Object>>();
        for (ChannelOccurrence binding : Objects.requireNonNull(bindings, "bindings")) {
            receiving.add(object("documentId", binding.managedDocumentId().value(),
                    "scopePath", binding.scopePath(),
                    "activationGeneration", Long.toString(binding.scopeActivationGeneration()),
                    "channelKey", binding.rawChannelKey(),
                    "occurrenceIdentity", binding.channelOccurrenceIdentity()));
        }
        Map<String, Object> position = sourcePositionIdentity == null
                ? object("kind", "NONE") : object("kind", "POSITION", "identity", sourcePositionIdentity);
        return RootedIdentity.deliveryIdentity(object("operationOwnerIdentity", operationOwnerIdentity(),
                "causeIdentity", causeIdentity, "kind", kind, "receivingBindings", receiving,
                "sourcePositionIdentity", position));
    }

    /**
     * Binds one authenticated retained application to its exact occurrence position.
     * The synthetic cause address uses the existing processor cause-family wire tag,
     * not an authored Channel. Its path, generation and occurrence are the selected
     * application binding; sourcePositionIdentity must be authenticated by the host.
     *
     * @param cause exact revision or representation cause
     * @param target exact pre-input historical occurrence
     * @param sourcePositionIdentity verified predecessor/ordinal position
     * @return closed draft.2 retained delivery identity
     */
    public String retainedDeliveryBasisIdentity(ProcessingCause cause, ManagedOccurrenceBinding target,
            String sourcePositionIdentity) {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(target, "target");
        String occurrence;
        DocumentId child;
        String before;
        String kind;
        long epoch;
        if (cause instanceof ManagedRevisionCause) {
            ManagedRevisionCause revision = (ManagedRevisionCause) cause;
            occurrence = revision.targetOccurrenceIdentity(); child = revision.childDocumentId();
            before = revision.beforeBlueId(); kind = "MANAGED_REVISION"; epoch = revision.fromEpoch();
        } else if (cause instanceof ManagedRepresentationCause) {
            ManagedRepresentationCause representation = (ManagedRepresentationCause) cause;
            occurrence = representation.targetOccurrenceIdentity(); child = representation.childDocumentId();
            before = representation.beforeBlueId(); kind = "MANAGED_REPRESENTATION"; epoch = representation.fromEpoch();
        } else {
            throw new IllegalArgumentException("Retained delivery requires a processor-managed historical cause");
        }
        if (!target.occurrenceIdentity().equals(occurrence) || !target.targetDocumentId().equals(child)
                || !target.expectedTargetBlueId().equals(before) || target.active()
                || target.pendingHistoricalEpoch() == null || target.pendingHistoricalEpoch().longValue() != epoch) {
            throw new IllegalArgumentException("Retained delivery does not own the exact historical occurrence");
        }
        Map<String, Object> receiving = object("documentId", target.sourceDocumentId().value(),
                "scopePath", target.sourcePath(), "activationGeneration", Long.toString(target.activationGeneration()),
                "channelKey", cause.kind().wireValue(), "occurrenceIdentity", target.occurrenceIdentity());
        return RootedIdentity.deliveryIdentity(object("operationOwnerIdentity", operationOwnerIdentity(),
                "causeIdentity", cause.causeIdentity(), "kind", kind,
                "receivingBindings", Collections.singletonList(receiving),
                "sourcePositionIdentity", object("kind", "POSITION", "identity", sourcePositionIdentity)));
    }

    /**
     * Wraps the unchanged base invocation identity.
     * @param baseInvocationIdentity existing base constructor result
     * @param deliveryBasisIdentity authenticated frozen delivery identity
     *
     * @return draft.2 rooted invocation identity
     */
    public String invocationIdentity(String baseInvocationIdentity, String deliveryBasisIdentity) {
        return RootedIdentity.wrapper("rootedInvocationIdentity", object("baseInvocationIdentity", baseInvocationIdentity,
                "rootProcessingContextIdentity", identity, "deliveryBasisIdentity", deliveryBasisIdentity));
    }

    /**
     * Wraps a complete actual base commit companion.
     * @param baseCommitCompanionIdentity base companion over actual result records
     * @param rootedInvocationIdentity original rooted invocation identity
     *
     * @return draft.2 rooted commit identity
     */
    public String commitCompanionIdentity(String baseCommitCompanionIdentity, String rootedInvocationIdentity) {
        return RootedIdentity.wrapper("rootedCommitCompanionIdentity", object("baseCommitCompanionIdentity", baseCommitCompanionIdentity,
                "rootedInvocationIdentity", rootedInvocationIdentity, "rootProcessingContextIdentity", identity));
    }

    /**
     * Constructs the terminal key without physical scheduling state.
     * @param deliveryBasisIdentity authenticated frozen delivery identity
     *
     * @return draft.2 terminal identity
     */
    public String terminalKey(String deliveryBasisIdentity) {
        return RootedIdentity.wrapper("rootedTerminalKey", object("operationOwnerIdentity", operationOwnerIdentity(),
                "deliveryBasisIdentity", deliveryBasisIdentity));
    }

    private static void requireForwardClosure(ManagedDocumentGraph graph, DocumentId root) {
        Set<DocumentId> reached = new LinkedHashSet<DocumentId>();
        Deque<DocumentId> pending = new ArrayDeque<DocumentId>();
        reached.add(root);
        pending.add(root);
        while (!pending.isEmpty()) {
            DocumentId source = pending.removeFirst();
            for (ManagedOccurrenceBinding row : graph.bindings()) {
                if (row.sourceDocumentId().equals(source) && reached.add(row.targetDocumentId())) {
                    pending.addLast(row.targetDocumentId());
                }
            }
        }
        if (!reached.equals(new LinkedHashSet<DocumentId>(graph.documentIds()))) {
            throw new IllegalArgumentException("Rooted view contains an unrelated or reverse-only document");
        }
    }

    private static Map<String, Object> object(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
}
