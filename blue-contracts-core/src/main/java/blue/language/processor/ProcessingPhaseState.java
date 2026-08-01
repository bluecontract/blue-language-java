package blue.language.processor;

import blue.language.model.Node;

import java.util.Objects;

/**
 * Defensively immutable cursor shared by the ordered PROCESS phases.
 *
 * <p>The invocation-owned session is intentionally shared by every phase,
 * while the mutable event {@link Node} is snapshotted on construction and on
 * access so callers cannot alter a stored hand-off.</p>
 */
final class ProcessingPhaseState {

    enum Stage {
        INPUT_ADMITTED,
        EVIDENCE_VERIFIED,
        CLOSURE_PREFLIGHTED,
        EXTERNAL_DELIVERIES_CLASSIFIED,
        SCOPES_INITIALIZED,
        LOGICAL_DELIVERIES_EXECUTED,
        INTERNAL_OCCURRENCES_DRAINED,
        SOUNDNESS_VALIDATED,
        SUBSCRIPTION_DELTA_VALIDATED
    }

    private final ProcessingSession session;
    private final Node event;
    private final Stage stage;

    private ProcessingPhaseState(
            ProcessingSession session,
            Node event,
            Stage stage) {
        this.session = Objects.requireNonNull(session, "session");
        this.event = event != null ? event.clone() : null;
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    static ProcessingPhaseState admitted(
            ProcessingSession session,
            Node event) {
        return new ProcessingPhaseState(
                session, event, Stage.INPUT_ADMITTED);
    }

    ProcessingSession session() {
        return session;
    }

    Node event() {
        return event != null ? event.clone() : null;
    }

    Stage stage() {
        return stage;
    }

    ProcessingPhaseState advance(
            Stage requiredCurrent,
            Stage next) {
        if (stage != requiredCurrent) {
            throw new IllegalStateException(
                    "PROCESS phase order violation: expected "
                            + requiredCurrent + " but was " + stage);
        }
        return new ProcessingPhaseState(session, event, next);
    }
}
