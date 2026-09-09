package blue.language.processor.closure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen rooted adjunct; birth additions are made only after demand authentication. */
final class RootedInvocationBinding {
    final RootedProcessingContext context;
    final String deliveryIdentity;
    final String entryInvocationIdentity;
    final Map<DocumentId, DocumentId> birthParents;

    RootedInvocationBinding(ClosureInvocationInput input, RootedProcessingContext context,
            String deliveryIdentity) {
        this(Objects.requireNonNull(context, "context"), deliveryIdentity,
                input.invocationIdentity(), Collections.<DocumentId, DocumentId>emptyMap());
        context.requireEntrySnapshot(input.snapshot());
        if (!RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY.equals(
                input.environment().contractsSpecificationIdentity())) {
            throw new IllegalArgumentException("Rooted adjunct requires the exact rooted Contracts profile");
        }
    }

    private RootedInvocationBinding(RootedProcessingContext context, String deliveryIdentity,
            String entryInvocationIdentity, Map<DocumentId, DocumentId> birthParents) {
        this.context = context;
        this.deliveryIdentity = ClosureValueSupport.requireSha256Identity(
                deliveryIdentity, "deliveryBasisIdentity");
        this.entryInvocationIdentity = entryInvocationIdentity;
        this.birthParents = Collections.unmodifiableMap(new LinkedHashMap<>(birthParents));
    }

    RootedInvocationBinding withAuthenticatedBirths(List<ManagedDocumentBirth> births) {
        Map<DocumentId, DocumentId> expanded = new LinkedHashMap<>(birthParents);
        for (ManagedDocumentBirth birth : births) {
            if (expanded.putIfAbsent(birth.documentId(), birth.demand().sourceDocumentId()) != null) {
                throw new IllegalArgumentException("A birth lineage cannot be reserved twice");
            }
        }
        return new RootedInvocationBinding(context, deliveryIdentity, entryInvocationIdentity, expanded);
    }
}
