package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.utils.JsonPointer;

import java.util.Map;
import java.util.Set;

/**
 * Composition root for deterministic changed-subscription validation.
 *
 * <p>Projection, retained activation-interval handling, and delta construction
 * are separate focused services. This adapter preserves the established public
 * validation port and fail-closed error mapping.</p>
 */
public final class DirectSubscriptionSurfaceValidator
        implements SubscriptionSurfaceValidator {

    /** Stateless validator for direct materialized contract surfaces. */
    public static final DirectSubscriptionSurfaceValidator INSTANCE =
            new DirectSubscriptionSurfaceValidator();

    private final SubscriptionSurfaceProjector projector;
    private final ActivationIntervalValidator intervals;
    private final SubscriptionDeltaBuilder deltas;

    private DirectSubscriptionSurfaceValidator() {
        this(null, null, null, null);
    }

    private DirectSubscriptionSurfaceValidator(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter) {
        this.projector = new SubscriptionSurfaceProjector(
                contractLoader,
                snapshotManager,
                registry,
                converter);
        this.intervals = new ActivationIntervalValidator(projector.rules());
        this.deltas = new SubscriptionDeltaBuilder(intervals);
    }

    static DirectSubscriptionSurfaceValidator configured(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter) {
        return new DirectSubscriptionSurfaceValidator(
                contractLoader,
                snapshotManager,
                registry,
                converter);
    }

    @Override
    public SubscriptionDelta validate(
            SubscriptionSurfaceValidationContext context) {
        if (context.changedPaths().isEmpty()) {
            return SubscriptionDelta.empty();
        }
        try {
            Set<String> normalized =
                    projector.normalizeChangedPaths(context.changedPaths());
            Map<String, SubscriptionDelta.Entry> before =
                    context.hasActiveSubscriptionIntervals()
                            ? intervals.affectedRetainedSurface(
                                    context, normalized)
                            : projector.project(
                                    context.inputRoot(),
                                    context.inputSnapshot(),
                                    context.gasSchedule(),
                                    normalized,
                                    context);
            Map<String, SubscriptionDelta.Entry> after = projector.project(
                    context.tentativeRoot(),
                    context.tentativeSnapshot(),
                    context.gasSchedule(),
                    normalized,
                    context);
            return deltas.build(before, after, context);
        } catch (SubscriptionSurfaceInvalidException exception) {
            throw exception;
        } catch (GasLimitExceededException
                 | PortableLimitExceededException
                 | ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (ProcessorFailureException exception) {
            throw new SubscriptionSurfaceInvalidException(
                    exception.getMessage(),
                    JsonPointer.ROOT,
                    null,
                    exception.errorCategory());
        } catch (RuntimeException exception) {
            throw projector.rules().invalid(
                    "Subscription surface derivation failed: "
                            + ProcessorEngine.deterministicMessage(
                                    exception,
                                    "invalid changed surface"),
                    JsonPointer.ROOT,
                    null);
        }
    }
}
