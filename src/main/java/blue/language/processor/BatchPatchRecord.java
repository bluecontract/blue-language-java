package blue.language.processor;

import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.ParsedJsonPointer;

import java.util.List;

final class BatchPatchRecord {

    private final ParsedJsonPointer parsedPath;
    private final ImmutablePatchPlanner.PatchPlan canonicalPlan;
    private final ImmutablePatchPlanner.PatchPlan resolvedPlan;
    private final FrozenNode beforeAtPatchTime;
    private final FrozenNode afterAtPatchTime;
    private final PatchImpact impact;
    private final boolean processorManagedConformanceBypass;

    BatchPatchRecord(ImmutableJsonPatch patch,
                     ImmutablePatchPlanner.PatchPlan canonicalPlan,
                     ImmutablePatchPlanner.PatchPlan resolvedPlan,
                     PatchImpact impact,
                     boolean processorManagedConformanceBypass) {
        this.parsedPath = patch.path();
        this.canonicalPlan = canonicalPlan;
        this.resolvedPlan = resolvedPlan;
        this.beforeAtPatchTime = resolvedPlan.before();
        this.afterAtPatchTime = resolvedPlan.after();
        this.impact = impact;
        this.processorManagedConformanceBypass = processorManagedConformanceBypass;
    }

    ParsedJsonPointer parsedPath() {
        return parsedPath;
    }

    ImmutablePatchPlanner.PatchPlan canonicalPlan() {
        return canonicalPlan;
    }

    ImmutablePatchPlanner.PatchPlan resolvedPlan() {
        return resolvedPlan;
    }

    String path() {
        return canonicalPlan.path();
    }

    JsonPatch.Op op() {
        return canonicalPlan.op();
    }

    String originScope() {
        return canonicalPlan.originScope();
    }

    List<String> cascadeScopes() {
        return canonicalPlan.cascadeScopes();
    }

    FrozenNode beforeAtPatchTime() {
        return beforeAtPatchTime;
    }

    FrozenNode afterAtPatchTime() {
        return afterAtPatchTime;
    }

    PatchImpact impact() {
        return impact;
    }

    boolean processorManagedConformanceBypass() {
        return processorManagedConformanceBypass;
    }
}
