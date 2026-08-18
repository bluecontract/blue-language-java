package blue.language.processor;

import blue.language.runtime.LanguageRuntimeAccess;

import java.util.Map;
import java.util.Objects;

/**
 * Invocation-owned bridge between portable processor gas and child runtime
 * work ledgers.
 *
 * <p>All ledgers share the same parent meter. A child is detached only while
 * it is accumulating a private admitted prefix and is merged exactly once by
 * the owning {@link RuntimeWorkSession}. Operational observations are not
 * accepted by this class and therefore cannot affect semantic gas.</p>
 */
final class ProcessingGasContext {

    private final GasMeter meter;
    private final ProcessGasMeter processMeter;
    private final SemanticOutputBoundary.AdmissionMemo outputAdmissionMemo =
            new SemanticOutputBoundary.AdmissionMemo();

    ProcessingGasContext(GasMeter meter) {
        this.meter = Objects.requireNonNull(meter, "meter");
        this.processMeter = new ProcessGasMeter(meter);
    }

    GasMeter meter() {
        return meter;
    }

    ProcessGasMeter processMeter() {
        return processMeter;
    }

    Object semanticAdmissionMemoIdentity() {
        return outputAdmissionMemo;
    }

    void configureLocalGasLimits(Map<String, Long> limitsByDocumentId) {
        meter.configureLocalGasLimits(limitsByDocumentId);
    }

    GasMeter.AttributionScope withAttribution(
            GasChargeContext attribution) {
        return meter.withAttribution(attribution);
    }

    GasMeter.ChildGasLedger newChildLedger(
            String namespace,
            Map<String, Long> counterWeights) {
        long kindLimit = meter.schedule().portableLimit(
                GasScheduleConstants.PortableLimit
                        .RUNTIME_CHILD_LEDGER_COUNTER_KINDS);
        if (counterWeights != null
                && counterWeights.size() > kindLimit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.RuntimeLedgerLimitExceeded,
                    GasScheduleConstants.PortableLimit
                            .RUNTIME_CHILD_LEDGER_COUNTER_KINDS,
                    counterWeights.size(),
                    kindLimit);
        }
        return meter.childLedger(namespace, counterWeights);
    }

    RuntimeWorkSession newRuntimeWorkSession(
            LanguageRuntimeAccess languageRuntime,
            ProcessingSnapshotManager snapshotManager) {
        RuntimeWorkSession session = new RuntimeWorkSession(
                meter, RuntimeWorkSession.Mode.PROCESSING);
        attachSemanticOutputBoundary(
                session,
                languageRuntime,
                snapshotManager);
        return session;
    }

    /** Opens admission work against this invocation's shared live budget. */
    RuntimeWorkSession newAdmissionRuntimeWorkSession(
            LanguageRuntimeAccess languageRuntime,
            ProcessingSnapshotManager snapshotManager) {
        RuntimeWorkSession session = new RuntimeWorkSession(
                meter, RuntimeWorkSession.Mode.ADMISSION);
        attachSemanticOutputBoundary(
                session,
                languageRuntime,
                snapshotManager);
        return session;
    }

    private void attachSemanticOutputBoundary(
            RuntimeWorkSession session,
            LanguageRuntimeAccess languageRuntime,
            ProcessingSnapshotManager snapshotManager) {
        if (languageRuntime != null) {
            session.attachSemanticOutputBoundary(
                    new SemanticOutputBoundary(
                            session,
                            languageRuntime,
                            snapshotManager,
                            meter.semantic(),
                            outputAdmissionMemo));
        }
    }

    void merge(GasMeter.ChildGasLedger ledger) {
        meter.merge(ledger);
    }
}
