package blue.language.processor;

import blue.language.conformance.ConformancePlan;
import blue.language.snapshot.FrozenNode;

import java.util.List;

/**
 * Optional immutable conformance-planning hook for isolated harnesses.
 *
 * <p>Production callers leave this absent. An override receives frozen roots
 * and the complete changed-path set and must return a plan without mutating
 * either input.</p>
 */
public interface ConformancePlannerOverride {

    /**
     * Reports whether this override should replace normal planning.
     *
     * @return {@code true} when {@link #plan} may be invoked
     */
    boolean applies();

    /**
     * Builds a deterministic plan from immutable document snapshots.
     *
     * @param canonicalRoot frozen canonical root before planning
     * @param resolvedRoot frozen resolved root before planning
     * @param changedPaths complete, ordered changed-path descriptors
     * @return a non-null conformance plan owned by the caller
     */
    ConformancePlan plan(FrozenNode canonicalRoot,
                         FrozenNode resolvedRoot,
                         List<ConformanceChangedPath> changedPaths);
}
