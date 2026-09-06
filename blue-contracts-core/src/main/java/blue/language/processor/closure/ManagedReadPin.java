package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import java.util.Objects;
import java.util.Optional;

/** Exact immutable dependency evidence, not another mutable lineage cell. */
public final class ManagedReadPin implements Comparable<ManagedReadPin> {
    private final DocumentId documentId;
    private final String blueId;
    private final FrozenNode document;
    private final CyclicSetProof cyclicProof;

    private ManagedReadPin(DocumentId documentId, String blueId, Node document, CyclicSetProof cyclicProof) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.blueId = ClosureValueSupport.requireBlueId(blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        Node body = Objects.requireNonNull(document, "document").clone();
        if (body.isReferenceOnly()) throw new IllegalArgumentException("Read pin requires the exact body, not an unresolved reference");
        if (cyclicProof == null) {
            if (!blueId.equals(DirectBlueIdCalculator.calculateBlueId(body))) {
                throw new IllegalArgumentException("Read pin body does not establish its exact BlueId");
            }
            this.cyclicProof = null;
        } else {
            this.cyclicProof = CyclicSetProof.fromDeclaredPlaceholderSet(cyclicProof.declaredPlaceholderSet());
            ManagedRevisionCyclicEvidenceVerifier.verify(blueId, body, this.cyclicProof);
        }
        this.document = FrozenNode.fromResolvedNode(body);
    }

    /** Verifies exact Language identity; this does not assert a processed receipt or epoch. */
    public static ManagedReadPin fromExactEvidence(DocumentId documentId, String blueId,
                                                   Node document, CyclicSetProof cyclicProof) {
        return new ManagedReadPin(documentId, blueId, document, cyclicProof);
    }

    public DocumentId documentId() { return documentId; }
    public String blueId() { return blueId; }
    public Node document() { return document.toNode(); }
    public FrozenNode frozenDocument() { return document; }
    public Optional<CyclicSetProof> cyclicProof() {
        return cyclicProof == null ? Optional.empty() : Optional.of(
                CyclicSetProof.fromDeclaredPlaceholderSet(cyclicProof.declaredPlaceholderSet()));
    }
    @Override public int compareTo(ManagedReadPin other) {
        int byDocument = documentId.compareTo(other.documentId);
        return byDocument != 0 ? byDocument : ClosureValueSupport.comparePortableText(blueId, other.blueId);
    }
}
