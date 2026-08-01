package blue.language.processor;

import java.util.Objects;

/** Immutable declaration of one deterministic PROCESS phase boundary. */
final class ProcessingPhaseContract {

    enum GasBehavior {
        NONE,
        CHARGE_BEFORE_WORK,
        CARRY_ADMITTED_PREFIX
    }

    enum ProviderDemand {
        NONE,
        EXACT_BOUND_INPUTS,
        PARTICIPATING_CLOSURE_ONLY
    }

    private final ProcessingPhaseState.Stage stage;
    private final GasBehavior gasBehavior;
    private final ProviderDemand providerDemand;
    private final ProcessorErrorCategory failureCategory;
    private final boolean recordsTrace;

    ProcessingPhaseContract(
            ProcessingPhaseState.Stage stage,
            GasBehavior gasBehavior,
            ProviderDemand providerDemand,
            ProcessorErrorCategory failureCategory,
            boolean recordsTrace) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.gasBehavior = Objects.requireNonNull(
                gasBehavior, "gasBehavior");
        this.providerDemand = Objects.requireNonNull(
                providerDemand, "providerDemand");
        this.failureCategory = Objects.requireNonNull(
                failureCategory, "failureCategory");
        this.recordsTrace = recordsTrace;
    }

    ProcessingPhaseState.Stage stage() {
        return stage;
    }

    GasBehavior gasBehavior() {
        return gasBehavior;
    }

    ProviderDemand providerDemand() {
        return providerDemand;
    }

    ProcessorErrorCategory failureCategory() {
        return failureCategory;
    }

    boolean recordsTrace() {
        return recordsTrace;
    }
}
