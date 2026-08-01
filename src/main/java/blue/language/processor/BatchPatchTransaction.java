package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.processor.model.JsonPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Atomic multi-patch transaction.
 *
 * <p>The reusable planning mechanics live in {@link PatchPlanningEngine}; this
 * wrapper deliberately retains the existing atomic batch boundary used by
 * {@link DocumentProcessingRuntime#applyPatches(String, List)}.</p>
 */
final class BatchPatchTransaction {

    private final List<PatchInput> patches;
    private final PatchPlanningEngine planningEngine;
    private final boolean buildUpdates;

    BatchPatchTransaction(String originScopePath,
                          List<JsonPatch> patches,
                          DocumentProcessingRuntime.PlanningContext planning,
                          ConformanceEngine conformanceEngine,
                          ConformancePlannerOverride conformancePlannerOverride,
                          DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics) {
        this(originScopePath, patches, planning, conformanceEngine, conformancePlannerOverride,
                materializationMetrics, true);
    }

    BatchPatchTransaction(String originScopePath,
                          List<JsonPatch> patches,
                          DocumentProcessingRuntime.PlanningContext planning,
                          ConformanceEngine conformanceEngine,
                          ConformancePlannerOverride conformancePlannerOverride,
                          DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics,
                          boolean buildUpdates) {
        this(originScopePath,
                patches,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                buildUpdates,
                NoOpProcessingObserver.INSTANCE);
    }

    BatchPatchTransaction(String originScopePath,
                          List<JsonPatch> patches,
                          DocumentProcessingRuntime.PlanningContext planning,
                          ConformanceEngine conformanceEngine,
                          ConformancePlannerOverride conformancePlannerOverride,
                          DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics,
                          boolean buildUpdates,
                          ProcessingObserver metrics) {
        this.patches = PatchInput.mutableList(patches);
        this.planningEngine = new PatchPlanningEngine(originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                metrics);
        this.buildUpdates = buildUpdates;
    }

    private BatchPatchTransaction(List<PatchInput> patches,
                                  String originScopePath,
                                  DocumentProcessingRuntime.PlanningContext planning,
                                  ConformanceEngine conformanceEngine,
                                  ConformancePlannerOverride conformancePlannerOverride,
                                  DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics,
                                  boolean buildUpdates,
                                  ProcessingObserver metrics) {
        this.patches = Collections.unmodifiableList(new ArrayList<>(patches));
        this.planningEngine = new PatchPlanningEngine(originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                metrics);
        this.buildUpdates = buildUpdates;
    }

    static BatchPatchTransaction fromInputs(String originScopePath,
                                            List<PatchInput> patches,
                                            DocumentProcessingRuntime.PlanningContext planning,
                                            ConformanceEngine conformanceEngine,
                                            ConformancePlannerOverride conformancePlannerOverride,
                                            DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics,
                                            boolean buildUpdates,
                                            ProcessingObserver metrics) {
        return new BatchPatchTransaction(patches,
                originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                buildUpdates,
                metrics);
    }

    BatchPatchResult apply() {
        return planningEngine.planAtomicInputs(patches, buildUpdates);
    }
}
