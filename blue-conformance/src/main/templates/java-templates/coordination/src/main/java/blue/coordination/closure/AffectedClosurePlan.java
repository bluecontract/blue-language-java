package blue.coordination.closure;

import blue.contracts.closure.AffectedClosureSnapshot;
import blue.contracts.closure.DocumentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Frozen Coordination plan for one connected required closure. */
public final class AffectedClosurePlan {
    private final String planIdentity;
    private final String entryBlueId;
    private final Object canonicalExternalOrder;
    private final List<DocumentId> directTargets;
    private final List<DocumentId> closureDocuments;
    private final AffectedClosureSnapshot contractsSnapshot;

    public AffectedClosurePlan(
            String planIdentity,
            String entryBlueId,
            Object canonicalExternalOrder,
            List<DocumentId> directTargets,
            List<DocumentId> closureDocuments,
            AffectedClosureSnapshot contractsSnapshot) {
        this.planIdentity = Objects.requireNonNull(planIdentity, "planIdentity");
        this.entryBlueId = Objects.requireNonNull(entryBlueId, "entryBlueId");
        this.canonicalExternalOrder = Objects.requireNonNull(canonicalExternalOrder, "canonicalExternalOrder");
        this.directTargets = Collections.unmodifiableList(new ArrayList<DocumentId>(directTargets));
        this.closureDocuments = Collections.unmodifiableList(new ArrayList<DocumentId>(closureDocuments));
        this.contractsSnapshot = Objects.requireNonNull(contractsSnapshot, "contractsSnapshot");
    }

    public String planIdentity() {
        return planIdentity;
    }

    public String entryBlueId() {
        return entryBlueId;
    }

    public Object canonicalExternalOrder() {
        return canonicalExternalOrder;
    }

    public List<DocumentId> directTargets() {
        return directTargets;
    }

    public List<DocumentId> closureDocuments() {
        return closureDocuments;
    }

    public AffectedClosureSnapshot contractsSnapshot() {
        return contractsSnapshot;
    }
}
