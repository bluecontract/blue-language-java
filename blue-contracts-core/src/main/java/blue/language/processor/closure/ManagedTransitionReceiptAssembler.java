package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal canonical assembler for complete managed-transition receipts. */
final class ManagedTransitionReceiptAssembler {

    private ManagedTransitionReceiptAssembler() {
    }

    static List<ManagedDocumentTransitionReceipt> assemble(
            ClosureInvocationInput input,
            List<ResultingDocument> documents,
            List<ManagedRootEventOccurrence> rootEvents,
            List<GasTraceEntry> gasTrace) {
        ClosureInvocationInput invocation = Objects.requireNonNull(
                input, "input");
        Map<DocumentId, List<ManagedRootEventOccurrence>> eventsByDocument =
                groupEvents(rootEvents);
        String originalCauseIdentity = originalCauseIdentity(
                invocation.cause());
        ArrayList<ResultingDocument> transitionedDocuments =
                new ArrayList<ResultingDocument>();
        for (ResultingDocument document : Objects.requireNonNull(
                documents, "documents")) {
            List<ManagedRootEventOccurrence> events = eventsByDocument.get(
                    document.documentId());
            if (!document.beforeBlueId().equals(document.afterBlueId())
                    || (events != null && !events.isEmpty())) {
                transitionedDocuments.add(document);
            }
        }
        Map<DocumentId, Long> gasByDocument = groupGas(
                gasTrace, transitionedDocuments);
        ArrayList<ManagedDocumentTransitionReceipt> receipts =
                new ArrayList<ManagedDocumentTransitionReceipt>();
        for (ResultingDocument document : transitionedDocuments) {
            List<ManagedRootEventOccurrence> events = eventsByDocument.get(
                    document.documentId());
            if (events == null) {
                events = Collections.emptyList();
            }
            Long admittedGas = gasByDocument.get(document.documentId());
            receipts.add(ManagedDocumentTransitionReceipt.identified(
                    invocation.invocationIdentity(),
                    receipts.size(),
                    document.documentId(),
                    originalCauseIdentity,
                    document.beforeBlueId(),
                    document.afterBlueId(),
                    events,
                    admittedGas == null ? 0L : admittedGas.longValue()));
        }
        if (countEvents(receipts) != rootEvents.size()) {
            throw new IllegalStateException(
                    "Managed Root events contain a document outside the result");
        }
        return Collections.unmodifiableList(receipts);
    }

    private static String originalCauseIdentity(ProcessingCause cause) {
        ProcessingCause selected = Objects.requireNonNull(cause, "cause");
        if (selected instanceof ManagedHistoryStep) {
            return ((ManagedHistoryStep) selected)
                    .originalSourceCauseIdentity();
        }
        return selected.causeIdentity();
    }

    private static Map<DocumentId, List<ManagedRootEventOccurrence>>
            groupEvents(List<ManagedRootEventOccurrence> values) {
        LinkedHashMap<DocumentId, List<ManagedRootEventOccurrence>> result =
                new LinkedHashMap<DocumentId,
                        List<ManagedRootEventOccurrence>>();
        for (ManagedRootEventOccurrence value : Objects.requireNonNull(
                values, "rootEvents")) {
            ManagedRootEventOccurrence event = Objects.requireNonNull(
                    value, "managed Root event");
            List<ManagedRootEventOccurrence> events = result.get(
                    event.sourceDocumentId());
            if (events == null) {
                events = new ArrayList<ManagedRootEventOccurrence>();
                result.put(event.sourceDocumentId(), events);
            }
            if (event.ordinal() != events.size()) {
                throw new IllegalStateException(
                        "Managed Root event receipt ordinals are not contiguous");
            }
            events.add(event);
        }
        return result;
    }

    private static Map<DocumentId, Long> groupGas(
            List<GasTraceEntry> values,
            List<ResultingDocument> transitionedDocuments) {
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        for (ResultingDocument document : transitionedDocuments) {
            result.put(document.documentId(), Long.valueOf(0L));
        }
        if (result.isEmpty()) {
            return result;
        }
        DocumentId residualOwner = transitionedDocuments.get(0)
                .documentId();
        for (GasTraceEntry entry : Objects.requireNonNull(
                values, "gasTrace")) {
            DocumentId owner = entry.documentId();
            if (owner == null || !result.containsKey(owner)) {
                owner = residualOwner;
            }
            Long current = result.get(owner);
            long updated;
            try {
                updated = Math.addExact(
                        current == null ? 0L : current.longValue(),
                        entry.subtotal());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                        "Managed-transition admitted gas overflow", overflow);
            }
            ClosureValueSupport.requireSafeInteger(
                    updated, "managed-transition admitted gas");
            result.put(owner, Long.valueOf(updated));
        }
        return result;
    }

    private static int countEvents(
            List<ManagedDocumentTransitionReceipt> receipts) {
        int count = 0;
        for (ManagedDocumentTransitionReceipt receipt : receipts) {
            count = Math.addExact(
                    count, receipt.emittedRootEvents().size());
        }
        return count;
    }
}
