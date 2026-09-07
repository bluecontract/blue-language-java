package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import blue.language.processor.NoncommittingExecutionException;

/** A pure canonical-origin evaluation: independent atomic operations or noncommitting exact needs. */
public final class SameOriginProcessAttempt {
    private final List<SameOriginOperationResult> operations;
    private final ClosureAttemptResult needs;
    private final Set<DocumentId> sourceAdmissions;

    private SameOriginProcessAttempt(List<SameOriginOperationResult> operations, ClosureAttemptResult needs,
            Set<DocumentId> sourceAdmissions) {
        this.operations = Collections.unmodifiableList(new ArrayList<>(operations)); this.needs = needs;
        this.sourceAdmissions = Collections.unmodifiableSet(new TreeSet<>(sourceAdmissions));
    }

    static SameOriginProcessAttempt complete(List<SameOriginOperationResult> operations) {
        return new SameOriginProcessAttempt(Objects.requireNonNull(operations), null, Collections.emptySet());
    }

    static SameOriginProcessAttempt needs(ClosureAttemptResult needs) {
        if (Objects.requireNonNull(needs).kind() == ClosureAttemptResult.Kind.COMPLETE)
            throw new IllegalArgumentException("A same-origin need cannot contain an atomic closure result");
        return new SameOriginProcessAttempt(Collections.emptyList(), needs, Collections.emptySet());
    }

    static SameOriginProcessAttempt needsSourceAdmissions(Set<DocumentId> sources) {
        if (Objects.requireNonNull(sources).isEmpty()) throw new IllegalArgumentException("Missing source owners required");
        return new SameOriginProcessAttempt(Collections.emptyList(), null, sources);
    }

    public boolean complete() { return needs == null && sourceAdmissions.isEmpty(); }
    /** Dependency-first deterministic order. Each entry has its own transaction/receipt identity. */
    public List<SameOriginOperationResult> operations() { return operations; }
    public List<ClosureResourceDemand> resourceDemands() { return needs == null ? Collections.emptyList() : needs.resourceDemands(); }
    public List<String> requiredExactBlueIds() { return needs == null ? Collections.emptyList() : needs.requiredExactBlueIds(); }

    /** Original producer input authority is missing; these are lineage IDs, not exact node acquisition keys. */
    public Set<DocumentId> requiredSourceAdmissions() { return sourceAdmissions; }

    /** Crosses every processor continuation without becoming a deterministic business failure. */
    static final class SourceAdmissionNeed extends NoncommittingExecutionException {
        private static final long serialVersionUID = 1L;
        private final Set<DocumentId> sources;
        SourceAdmissionNeed(Set<DocumentId> sources) {
            super("Fresh source execution requires its original input admission");
            this.sources = needsSourceAdmissions(sources).requiredSourceAdmissions();
        }
        Set<DocumentId> sources() { return sources; }
    }
}
