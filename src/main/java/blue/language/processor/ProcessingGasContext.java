package blue.language.processor;

import blue.language.Blue;

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
            Blue blue,
            ProcessingSnapshotManager snapshotManager) {
        RuntimeWorkSession session = new RuntimeWorkSession(
                meter, RuntimeWorkSession.Mode.PROCESSING);
        if (blue != null) {
            session.attachSemanticOutputBoundary(
                    new SemanticOutputBoundary(
                            session,
                            blue,
                            snapshotManager,
                            meter.semantic(),
                            outputAdmissionMemo));
        }
        return session;
    }

    void merge(GasMeter.ChildGasLedger ledger) {
        meter.merge(ledger);
    }
}
