package blue.language.processor;

import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.List;

final class BatchPatchRecord {

    private final JsonPatch patch;
    private final ImmutablePatchPlanner.PatchPlan canonicalPlan;
    private final ImmutablePatchPlanner.PatchPlan resolvedPlan;
    private final FrozenNode beforeAtPatchTime;
    private final FrozenNode afterAtPatchTime;
    private final boolean processorManagedConformanceBypass;

    BatchPatchRecord(JsonPatch patch,
                     ImmutablePatchPlanner.PatchPlan canonicalPlan,
                     ImmutablePatchPlanner.PatchPlan resolvedPlan,
                     boolean processorManagedConformanceBypass) {
        this.patch = patch;
        this.canonicalPlan = canonicalPlan;
        this.resolvedPlan = resolvedPlan;
        this.beforeAtPatchTime = resolvedPlan.before();
        this.afterAtPatchTime = resolvedPlan.after();
        this.processorManagedConformanceBypass = processorManagedConformanceBypass;
    }

    JsonPatch patch() {
        return patch;
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

    boolean processorManagedConformanceBypass() {
        return processorManagedConformanceBypass;
    }
}
