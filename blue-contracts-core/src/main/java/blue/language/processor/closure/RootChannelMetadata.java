package blue.language.processor.closure;

import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.snapshot.FrozenNode;
import java.util.*;

/**
 * Complete processor-derived Root routing metadata bound to one verified state.
 * The retained routing document is NOT a managed application document and must
 * never be passed to business execution. It contains no application properties
 * or executable body payloads. Cold reconstruction requires an authenticated
 * enclosing state/receipt association, not a caller-asserted BlueId.
 */
public final class RootChannelMetadata {
    private final DocumentId documentId;
    private final String blueId, registryIdentity;
    private final long epoch;
    private final boolean initialized, terminated;
    private final FrozenNode routingDocument;
    private final ManagedRootSubscriptionSurface surface;

    private RootChannelMetadata(DocumentId documentId, String blueId, long epoch,
            boolean initialized, boolean terminated, String registryIdentity,
            FrozenNode routingDocument, ManagedRootSubscriptionSurface surface) {
        this.documentId = Objects.requireNonNull(documentId);
        this.blueId = ClosureValueSupport.requireBlueId(blueId, "rootMetadata.blueId");
        this.epoch = ClosureValueSupport.requireSafeInteger(epoch, "rootMetadata.epoch");
        this.initialized = initialized; this.terminated = terminated;
        this.registryIdentity = ClosureValueSupport.requireSha256Identity(registryIdentity, "rootMetadata.registry");
        this.routingDocument = Objects.requireNonNull(routingDocument);
        this.surface = Objects.requireNonNull(surface);
    }

    /** Called only after complete owning snapshot verification. */
    static RootChannelMetadata fromVerifiedRoot(ManagedDocumentSnapshot exactRoot, DocumentProcessor processor) {
        try (BlueClosureContracts contracts = new BlueClosureContracts(processor)) {
            ManagedRootSubscriptionSurface original = contracts.projectRootSubscriptionSurface(exactRoot.document());
            Node routing = routing(original);
            return new RootChannelMetadata(exactRoot.documentId(), exactRoot.blueId(), exactRoot.epoch(),
                    exactRoot.initialized(), exactRoot.terminated(), processor.runtimeRegistryIdentity(),
                    FrozenNode.fromResolvedNode(routing), original);
        }
    }

    /** Package-owned codec entry: the caller has authenticated the enclosing authority. */
    static RootChannelMetadata restore(DocumentId documentId, String blueId, long epoch, boolean initialized,
            boolean terminated, String registry, FrozenNode routing, ManagedRootSubscriptionSurface surface, DocumentProcessor processor) {
        if (!Objects.equals(registry, processor.runtimeRegistryIdentity())) throw invalid("Root metadata registry differs from the captured processor");
        Node header = routing.toNode();
        if (header.getProperties() != null && !header.getProperties().isEmpty()
                || header.getItems() != null || header.getValue() != null || header.getSchema() != null)
            throw invalid("Root routing metadata contains an application payload");
        if (!routing.blueId().equals(FrozenNode.fromResolvedNode(routing(surface)).blueId()))
            throw invalid("Retained routing headers differ from the complete effective surface");
        return new RootChannelMetadata(documentId, blueId, epoch, initialized, terminated, registry, routing, surface);
    }

    public DocumentId documentId() { return documentId; }
    public String blueId() { return blueId; }
    public long epoch() { return epoch; }
    public boolean initialized() { return initialized; }
    public boolean terminated() { return terminated; }
    public String registryIdentity() { return registryIdentity; }
    public ManagedRootSubscriptionSurface surface() { return surface; }
    public Node routingDocument() { return routingDocument.toNode(); }
    FrozenNode frozenRoutingDocument() { return routingDocument; }
    public void verifyState(ManagedDocumentSnapshot state) {
        if (!documentId.equals(state.documentId()) || !blueId.equals(state.blueId()) || epoch != state.epoch()
                || initialized != state.initialized() || terminated != state.terminated())
            throw invalid("Root Channel metadata belongs to another exact selected state");
    }
    public void verifyRegistry(String registry) {
        if (!registryIdentity.equals(registry)) throw invalid("Root Channel metadata belongs to another processor registry");
    }

    private static Node routing(ManagedRootSubscriptionSurface surface) {
        Node contracts = new Node();
        for (EffectiveContractSnapshot snapshot : surface.effectiveRootContracts()) {
            Node header = new Node().type(new Node().blueId(snapshot.effectiveTypeBlueId()));
            for (Map.Entry<String, FrozenNode> field : snapshot.headerFields().entrySet())
                header.properties(field.getKey(), NodeToBlueIdInput.stripResolvedBlueIdMetadata(field.getValue().toNode()));
            contracts.properties(snapshot.key(), header);
        }
        return new Node().contracts(contracts);
    }

    private static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
}
