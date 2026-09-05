package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.util.ProcessorContractConstants;

import java.util.Objects;

/**
 * Immutable exact content and a host-reserved fresh lineage for one birth demand.
 *
 * <p>This is untrusted input evidence, not an initialization receipt. The host
 * must reserve a previously unused durable DocumentId under its lineage policy.
 * Contracts verifies the content and replays the unchanged logical cause with
 * an inactive prospective occurrence before performing lifecycle work. Equal
 * content at different creation occurrences must use distinct reservations.</p>
 */
public final class ManagedDocumentBirth {
    private final ManagedOccurrenceEvidenceDemand demand;
    private final DocumentId documentId;
    private final Node document;

    /**
     * Binds a fresh lineage to the exact authored value observed at a demand.
     *
     * @param demand demand from a noncommitting attempt
     * @param documentId freshly reserved durable lineage
     * @param document complete canonical authored content, without lifecycle markers
     * @throws IllegalArgumentException if content differs or carries direct markers
     */
    public ManagedDocumentBirth(ManagedOccurrenceEvidenceDemand demand,
                               DocumentId documentId, Node document) {
        this.demand = Objects.requireNonNull(demand, "demand");
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.document = Objects.requireNonNull(document, "document").clone();
        if (this.document.getBlueId() != null
                || !demand.suppliedValueBlueId().equals(
                        DirectBlueIdCalculator.calculateBlueId(this.document))) {
            throw new IllegalArgumentException("Birth content does not match the exact demand");
        }
        Node contracts = this.document.getContracts();
        if (contracts != null && contracts.getProperties() != null
                && (contracts.getProperties().containsKey(ProcessorContractConstants.KEY_INITIALIZED)
                    || contracts.getProperties().containsKey(ProcessorContractConstants.KEY_TERMINATED)
                    || contracts.getProperties().containsKey(ProcessorContractConstants.KEY_CHECKPOINT))) {
            throw new IllegalArgumentException("Birth content must not claim prior processing");
        }
    }

    /** @return immutable exact demand */
    public ManagedOccurrenceEvidenceDemand demand() { return demand; }

    /** @return freshly reserved lineage, whose durable uniqueness is host-owned */
    public DocumentId documentId() { return documentId; }

    /** @return defensive copy of the exact authored content */
    public Node document() { return document.clone(); }
}
