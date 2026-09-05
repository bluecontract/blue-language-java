package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared exact conversions used by committing and rollback assemblers. */
final class ClosureResultAssemblySupport {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private static final String PROVISIONAL_IDENTITY =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";

    private ClosureResultAssemblySupport() {
    }

    static AffectedClosureSnapshot admissionSnapshot(
            AffectedClosureSnapshot inputSnapshot,
            ComponentFinalizationResult finalized,
            Set<DocumentId> initialized,
            List<ManagedOccurrenceBinding> currentBindings,
            long graphGeneration) {
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot original
                : inputSnapshot.managedDocuments()) {
            FinalizedDocumentEvidence exact = finalized.document(
                    original.documentId());
            documents.add(new ManagedDocumentSnapshot(
                    original.documentId(),
                    exact.blueId(),
                    exact.document(),
                    initialized.contains(original.documentId()),
                    original.terminated(),
                    original.publicRoot(),
                    original.epoch(),
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            components.add(component.component());
        }
        String bindingIdentity = IDENTITIES.occurrenceBindingSetIdentity(
                currentBindings);
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                PROVISIONAL_IDENTITY,
                graphGeneration,
                documents,
                currentBindings,
                bindingIdentity,
                components,
                inputSnapshot.publicRootDocumentIds());
        return new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisional),
                graphGeneration,
                documents,
                currentBindings,
                bindingIdentity,
                components,
                inputSnapshot.publicRootDocumentIds());
    }

    static List<GasTraceEntry> gasTrace(
            List<blue.language.processor.GasTraceEntry> values) {
        ArrayList<GasTraceEntry> result =
                new ArrayList<GasTraceEntry>();
        for (blue.language.processor.GasTraceEntry value : values) {
            try {
                result.add(new GasTraceEntry(
                        value.sequence(),
                        namespace(value.namespace()),
                        value.counter(),
                        value.quantity(),
                        value.weight(),
                        value.subtotal(),
                        value.documentId() == null
                                ? null : new DocumentId(value.documentId()),
                        value.scopePath(),
                        value.activationGeneration(),
                        value.componentGeneration(),
                        value.contractKey(),
                        value.logicalPath(),
                        value.workOccurrenceId(),
                        value.reason()));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Cannot project admitted gas entry "
                                + value.sequence()
                                + " (namespace=" + value.namespace()
                                + ", counter=" + value.counter()
                                + ", scopePath=" + value.scopePath()
                                + ", activationGeneration="
                                + value.activationGeneration() + ")",
                        invalid);
            }
        }
        return result;
    }

    static long totalGas(List<GasTraceEntry> trace) {
        long result = 0L;
        for (GasTraceEntry entry : trace) {
            result = Math.addExact(result, entry.subtotal());
        }
        return result;
    }

    static String sequenceIdentity(
            ClosureIdentityService.Constructor constructor,
            List<?> records) {
        ArrayList<Object> values = new ArrayList<Object>();
        for (Object record : records) {
            if (record instanceof GraphChange) {
                values.add(((GraphChange) record).identityValue());
            } else if (record instanceof SubscriptionDelta) {
                values.add(((SubscriptionDelta) record).identityValue());
            } else if (record instanceof CheckpointWrite) {
                values.add(((CheckpointWrite) record).identityValue());
            } else if (record instanceof PublicEventOccurrence) {
                values.add(((PublicEventOccurrence) record).identityValue());
            } else if (record instanceof GasTraceEntry) {
                values.add(((GasTraceEntry) record).identityValue());
            } else {
                throw new IllegalArgumentException(
                        "Unsupported closure result sequence record");
            }
        }
        return IDENTITIES.identity(constructor, values);
    }

    static Long memberIndex(String blueId) {
        int separator = blueId.lastIndexOf('#');
        return separator < 0 ? null
                : Long.valueOf(Long.parseLong(
                        blueId.substring(separator + 1)));
    }

    static GasTraceEntry.Namespace namespace(String value) {
        if (GasTraceEntry.Namespace.PROCESSOR.wireValue().equals(value)) {
            return GasTraceEntry.Namespace.PROCESSOR;
        }
        if (GasTraceEntry.Namespace.SEMANTIC.wireValue().equals(value)) {
            return GasTraceEntry.Namespace.SEMANTIC;
        }
        if (value != null && !value.isEmpty()) {
            // GasMeter retains a hosted runtime's unique physical child-ledger
            // namespace so independently owned ledgers cannot alias. Closure
            // evidence exposes the normative semantic namespace; the exact
            // registered runtime generation and counter remain identity-bound
            // by the invocation environment and trace entry respectively.
            return GasTraceEntry.Namespace.RUNTIME;
        }
        throw new IllegalArgumentException(
                "Unknown closure gas namespace: " + value);
    }
}
