package blue.language.processor;

import blue.language.conformance.ConformancePlan;
import blue.language.snapshot.FrozenNode;

import java.util.List;

/**
 * Optional conformance planner hook for isolated conformance harnesses.
 */
public interface ConformancePlannerOverride {

    boolean applies();

    ConformancePlan plan(FrozenNode canonicalRoot,
                         FrozenNode resolvedRoot,
                         List<ConformanceChangedPath> changedPaths);
}
