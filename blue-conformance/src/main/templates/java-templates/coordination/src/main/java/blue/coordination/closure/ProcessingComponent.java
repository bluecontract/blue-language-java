package blue.coordination.closure;

import blue.contracts.closure.CanonicalOrders;
import blue.contracts.closure.ComponentKind;
import blue.contracts.closure.DocumentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Deterministic SCC/acyclic component projection used by Coordination planning. */
public final class ProcessingComponent {
    private final ComponentId id;
    private final long generation;
    private final ComponentKind kind;
    private final List<DocumentId> orderedMembers;
    private final String masterBlueId;

    public ProcessingComponent(
            ComponentId id,
            long generation,
            ComponentKind kind,
            List<DocumentId> orderedMembers,
            String masterBlueId) {
        this.id = Objects.requireNonNull(id, "id");
        this.generation = CanonicalOrders.requireSafeInteger(
                generation, "generation");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (orderedMembers.isEmpty()) {
            throw new IllegalArgumentException("orderedMembers");
        }
        List<DocumentId> copy = new ArrayList<DocumentId>(orderedMembers);
        for (DocumentId value : copy) {
            Objects.requireNonNull(value, "orderedMembers item");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "members must be in strict canonical DocumentId order");
            }
        }
        if (kind == ComponentKind.ACYCLIC && copy.size() != 1) {
            throw new IllegalArgumentException("acyclic component must be a singleton");
        }
        this.orderedMembers = Collections.unmodifiableList(copy);
        if (kind == ComponentKind.CYCLIC && masterBlueId == null) {
            throw new IllegalArgumentException("cyclic component requires masterBlueId");
        }
        if (kind == ComponentKind.ACYCLIC && masterBlueId != null) {
            throw new IllegalArgumentException("acyclic component must not have masterBlueId");
        }
        this.masterBlueId = masterBlueId;
    }

    public ComponentId id() {
        return id;
    }

    public long generation() {
        return generation;
    }

    public ComponentKind kind() {
        return kind;
    }

    public List<DocumentId> orderedMembers() {
        return orderedMembers;
    }

    public String masterBlueId() {
        return masterBlueId;
    }
}
