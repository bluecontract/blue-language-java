package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Named PROCESS-operation charging surface over the invocation gas ledger.
 *
 * <p>Semantic formulas remain owned by {@link SemanticGasMeter}; this class
 * exposes only protocol operation counters. Every method delegates to the one
 * live-bounded {@link GasMeter}, which remains the sole trace writer.</p>
 */
final class ProcessGasMeter {

    private final GasMeter ledger;

    ProcessGasMeter(GasMeter ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    void invocation() {
        ledger.chargeProcessInvocation();
    }

    void deliverySnapshotEntry(String scopePath, String contractKey) {
        ledger.chargeDeliverySnapshotEntry(scopePath, contractKey);
    }

    void scopeEntry(String scopePath) {
        ledger.chargeScopeEntry(scopePath);
    }

    void participatingClosure(long quantity) {
        ledger.chargeParticipatingClosure(quantity);
    }

    void contractHeader(
            String scopePath,
            String contractKey,
            String reason) {
        ledger.chargeContractHeaderRecognized(
                scopePath, contractKey, reason);
    }

    void contractHeaders(long quantity, String reason) {
        ledger.chargeContractHeadersRecognized(quantity, reason);
    }

    void embeddedPathEntry(String scopePath, String logicalPath) {
        ledger.chargeEmbeddedPathEntryRead(scopePath, logicalPath);
    }

    void embeddedPathSegments(
            String scopePath,
            String logicalPath,
            long quantity) {
        ledger.chargeEmbeddedPathSegmentsValidated(
                scopePath, logicalPath, quantity);
    }

    void initialization(String scopePath) {
        ledger.chargeInitialization(scopePath);
    }

    void channelMatch(String scopePath, String contractKey) {
        ledger.chargeChannelMatchAttempt(scopePath, contractKey);
    }

    void channelAccepted(String scopePath, String contractKey) {
        ledger.chargeChannelAccepted(scopePath, contractKey);
    }

    void handlerCandidate(String scopePath, String contractKey) {
        ledger.chargeHandlerCandidateTested(scopePath, contractKey);
    }

    void handlerOverhead(String scopePath, String contractKey) {
        ledger.chargeHandlerOverhead(scopePath, contractKey);
    }

    void boundaryCheck() {
        ledger.chargeBoundaryCheck();
    }

    void patchAddOrReplace(Node value) {
        ledger.chargePatchAddOrReplace(value);
    }

    void frozenPatchAddOrReplace(FrozenNode value) {
        ledger.chargeFrozenPatchAddOrReplace(value);
    }

    void frozenPatchAddOrReplace(long canonicalSizeBytes) {
        ledger.chargeFrozenPatchAddOrReplace(canonicalSizeBytes);
    }

    void patchRemove() {
        ledger.chargePatchRemove();
    }

    void cascadeRouting(int scopeCount) {
        ledger.chargeCascadeRouting(scopeCount);
    }

    void emitEvent(Node event) {
        ledger.chargeEmitEvent(event);
    }

    void rootEventRecorded() {
        ledger.chargeRootEventRecorded();
    }

    void bridge(Node event) {
        ledger.chargeBridge(event);
    }

    void triggeredDelivery() {
        ledger.chargeTriggeredDelivery();
    }

    void drainEvent() {
        ledger.chargeDrainEvent();
    }

    void checkpointUpdate() {
        ledger.chargeCheckpointUpdate();
    }

    void checkpointUpdate(GasChargeContext context) {
        ledger.chargeCheckpointUpdate(context);
    }

    void checkpointCompared() {
        ledger.chargeCheckpointCompared();
    }

    void checkpointCompared(GasChargeContext context) {
        ledger.chargeCheckpointCompared(context);
    }

    void processorMarker(String reason) {
        ledger.chargeProcessorMarkerWritten(reason);
    }

    void terminationRequest() {
        ledger.chargeTerminationRequest();
    }

    void terminationMarker() {
        ledger.chargeTerminationMarker();
    }

    void lifecycleDelivery() {
        ledger.chargeLifecycleDelivery();
    }
}
