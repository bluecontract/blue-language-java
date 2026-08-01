package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.util.Objects;

/**
 * Translates processor operations into the named Contracts 1.0 gas counters.
 *
 * <p>The live ledger remains responsible for validation, budget admission, and
 * trace ordering. This adapter owns only the stable mapping from a processor
 * operation to its counter, quantity, and deterministic attribution context.</p>
 */
final class ProcessorGasCharges {

    private final GasMeter meter;

    ProcessorGasCharges(GasMeter meter) {
        this.meter = Objects.requireNonNull(meter, "meter");
    }

    void processInvocation() {
        charge(GasScheduleConstants.ProcessorCounter.PROCESS_INVOCATION, 1L,
                GasChargeContext.of(JsonPointer.ROOT, null, null,
                        GasScheduleConstants.ChargeReason.INVOCATION));
    }

    void deliverySnapshotEntry(String scopePath, String contractKey) {
        charge(GasScheduleConstants.ProcessorCounter.DELIVERY_SNAPSHOT_ENTRY, 1L,
                GasChargeContext.of(scopePath, contractKey, null,
                        GasScheduleConstants.ChargeReason.REVALIDATE_DELIVERY));
    }

    void scopeEntry(String scopePath) {
        charge(GasScheduleConstants.ProcessorCounter.SCOPE_OPENED, 1L,
                GasChargeContext.of(scopePath, null, null,
                        GasScheduleConstants.ChargeReason.PARTICIPATING_SCOPE));
    }

    void participatingClosure(long quantity) {
        String reason = quantity == 1L
                ? GasScheduleConstants.ChargeReason.PARTICIPATING_SCOPE
                : GasScheduleConstants.ChargeReason.PARTICIPATING_CLOSURE;
        charge(GasScheduleConstants.ProcessorCounter.SCOPE_OPENED, quantity,
                GasChargeContext.of(JsonPointer.ROOT, null, null, reason));
    }

    void contractHeaderRecognized(String scopePath,
                                  String contractKey,
                                  String reason) {
        charge(GasScheduleConstants.ProcessorCounter.CONTRACT_HEADER_RECOGNIZED, 1L,
                GasChargeContext.of(scopePath, contractKey, null, reason));
    }

    void contractHeadersRecognized(long quantity, String reason) {
        charge(GasScheduleConstants.ProcessorCounter.CONTRACT_HEADER_RECOGNIZED,
                quantity,
                GasChargeContext.of(JsonPointer.ROOT, null, null, reason));
    }

    void embeddedPathEntryRead(String scopePath, String logicalPath) {
        charge(GasScheduleConstants.ProcessorCounter.EMBEDDED_PATH_ENTRY_READ, 1L,
                GasChargeContext.of(scopePath, null, logicalPath,
                        GasScheduleConstants.ChargeReason.ROUTE));
    }

    void embeddedPathSegmentsValidated(String scopePath,
                                       String logicalPath,
                                       long quantity) {
        charge(GasScheduleConstants.ProcessorCounter.EMBEDDED_PATH_SEGMENT_VALIDATED,
                quantity,
                GasChargeContext.of(scopePath, null, logicalPath,
                        GasScheduleConstants.ChargeReason.ROUTE));
    }

    void scopeEntry(int embeddedDepth) {
        if (embeddedDepth < 0) {
            throw new IllegalArgumentException(
                    "Scope embedded depth must be non-negative");
        }
        scopeEntry(JsonPointer.ROOT);
    }

    void initialization(String scopePath) {
        charge(GasScheduleConstants.ProcessorCounter.SCOPE_INITIALIZATION, 1L,
                GasChargeContext.of(scopePath, null, null,
                        GasScheduleConstants.ChargeReason.SCOPE_INITIALIZATION));
    }

    void channelMatchAttempt(String scopePath, String contractKey) {
        charge(GasScheduleConstants.ProcessorCounter.CHANNEL_CANDIDATE_TESTED, 1L,
                GasChargeContext.of(scopePath, contractKey, null,
                        GasScheduleConstants.ChargeReason.ACCEPTANCE));
    }

    void channelAccepted(String scopePath, String contractKey) {
        charge(GasScheduleConstants.ProcessorCounter.CHANNEL_ACCEPTED, 1L,
                GasChargeContext.of(scopePath, contractKey, null,
                        GasScheduleConstants.ChargeReason.ACCEPTANCE));
    }

    void handlerCandidateTested(String scopePath, String contractKey) {
        charge(GasScheduleConstants.ProcessorCounter.HANDLER_CANDIDATE_TESTED, 1L,
                GasChargeContext.of(scopePath, contractKey, null,
                        GasScheduleConstants.ChargeReason.MATCHING));
    }

    void handlerOverhead(String scopePath, String contractKey) {
        charge(GasScheduleConstants.ProcessorCounter.HANDLER_CALL, 1L,
                GasChargeContext.of(scopePath, contractKey, null,
                        GasScheduleConstants.ChargeReason.HANDLER_CALL));
    }

    void boundaryCheck() {
        charge(GasScheduleConstants.ProcessorCounter.PATCH_BOUNDARY_CHECKED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.PATCH_BOUNDARY));
    }

    void pointerSegments(long quantity, String logicalPath) {
        charge(GasScheduleConstants.ProcessorCounter.POINTER_SEGMENT_TRAVERSED,
                quantity,
                GasChargeContext.of(null, null, logicalPath,
                        GasScheduleConstants.ChargeReason.RUNTIME_POINTER));
    }

    void patchAddOrReplace(Node ignoredValue) {
        patchAddOrReplace();
    }

    void frozenPatchAddOrReplace(FrozenNode ignoredValue) {
        patchAddOrReplace();
    }

    void frozenPatchAddOrReplace(long authoredCanonicalSizeBytes) {
        if (authoredCanonicalSizeBytes < 0L) {
            throw new IllegalArgumentException(
                    "Authored canonical size must be non-negative");
        }
        patchAddOrReplace();
    }

    private void patchAddOrReplace() {
        charge(GasScheduleConstants.ProcessorCounter.PATCH_ADD_OR_REPLACE, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.APPLICATION_PATCH));
    }

    void patchRemove() {
        charge(GasScheduleConstants.ProcessorCounter.PATCH_REMOVE, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.APPLICATION_PATCH));
    }

    void cascadeRouting(int matchingDeliveryCount) {
        if (matchingDeliveryCount > 0) {
            charge(GasScheduleConstants.ProcessorCounter.DOCUMENT_UPDATE_DELIVERED,
                    matchingDeliveryCount,
                    GasChargeContext.reason(
                            GasScheduleConstants.ChargeReason.DOCUMENT_UPDATE));
        }
    }

    void emitEvent(Node ignoredEvent) {
        charge(GasScheduleConstants.ProcessorCounter.INTERNAL_EVENT_ENQUEUED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.EVENT_EMISSION));
    }

    void rootEventRecorded() {
        charge(GasScheduleConstants.ProcessorCounter.ROOT_EVENT_RECORDED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.ROOT_EMISSION));
    }

    void bridge(Node ignoredEvent) {
        charge(GasScheduleConstants.ProcessorCounter.EMBEDDED_EVENT_DELIVERED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.EMBEDDED_EVENT));
    }

    void triggeredDelivery() {
        charge(GasScheduleConstants.ProcessorCounter.TRIGGERED_EVENT_DELIVERED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.TRIGGERED_EVENT));
    }

    void drainEvent() {
        charge(GasScheduleConstants.ProcessorCounter.INTERNAL_EVENT_DEQUEUED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.EVENT_DRAIN));
    }

    void checkpointCompared() {
        charge(GasScheduleConstants.ProcessorCounter.CHECKPOINT_COMPARED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.CHECKPOINT_COMPARE));
    }

    void checkpointUpdate() {
        charge(GasScheduleConstants.ProcessorCounter.CHECKPOINT_WRITTEN, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.CHECKPOINT_WRITE));
    }

    void processorMarkerWritten(String reason) {
        charge(GasScheduleConstants.ProcessorCounter.PROCESSOR_MARKER_WRITTEN, 1L,
                GasChargeContext.reason(reason));
    }

    void terminationRequest() {
        charge(GasScheduleConstants.ProcessorCounter.TERMINATION_REQUESTED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.TERMINATION_REQUEST));
    }

    void terminationMarker() {
        processorMarkerWritten(
                GasScheduleConstants.ChargeReason.TERMINATION_MARKER);
    }

    void lifecycleDelivery() {
        charge(GasScheduleConstants.ProcessorCounter.LIFECYCLE_DELIVERED, 1L,
                GasChargeContext.reason(
                        GasScheduleConstants.ChargeReason.LIFECYCLE));
    }

    private void charge(String counter,
                        long quantity,
                        GasChargeContext context) {
        meter.charge(GasScheduleConstants.Namespace.PROCESSOR,
                counter,
                quantity,
                context);
    }
}
