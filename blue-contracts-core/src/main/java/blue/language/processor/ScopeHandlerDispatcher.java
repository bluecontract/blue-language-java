package blue.language.processor;

import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Matches and invokes handler contracts for one frozen same-scope Channel. */
final class ScopeHandlerDispatcher {

    private final ProcessorInvocationServices owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;

    ScopeHandlerDispatcher(
            ProcessorInvocationServices owner,
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            Node event) {
        return dispatch(
                scopePath,
                bundle,
                channelKey,
                event,
                event,
                CheckpointIdentityCalculator.identity(
                        event, owner.languageRuntimeAccess()),
                null,
                null,
                java.util.Collections.<ExactBlueValue>emptyList(), false);
    }

    boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            FrozenNode exactEvent,
            String exactEventBlueId,
            List<ExactBlueValue> carriedExactValues) {
        return dispatch(
                scopePath,
                bundle,
                channelKey,
                exactEvent,
                exactEventBlueId,
                carriedExactValues,
                false);
    }

    boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            FrozenNode exactEvent,
            String exactEventBlueId,
            List<ExactBlueValue> carriedExactValues,
            boolean allowTerminatingScope) {
        FrozenNode carried = Objects.requireNonNull(exactEvent, "exactEvent");
        String admittedEventBlueId = BlueIds.requireBlueIdOrCyclicMember(
                exactEventBlueId,
                "exactEventBlueId");
        Node event = carried.toNode();
        return dispatch(
                scopePath,
                bundle,
                channelKey,
                event,
                event,
                admittedEventBlueId,
                carried,
                admittedEventBlueId,
                carriedExactValues,
                allowTerminatingScope);
    }

    boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            Node event,
            boolean allowTerminatingScope) {
        return dispatch(
                scopePath,
                bundle,
                channelKey,
                event,
                event,
                CheckpointIdentityCalculator.identity(
                        event, owner.languageRuntimeAccess()),
                null,
                null,
                java.util.Collections.<ExactBlueValue>emptyList(),
                allowTerminatingScope);
    }

    boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            Node event,
            Node occurrenceEvent,
            String occurrenceEventBlueId) {
        Node checkedEvent = Objects.requireNonNull(event, "event");
        Node checkedOccurrence = Objects.requireNonNull(
                occurrenceEvent, "occurrenceEvent");
        String admittedOccurrenceBlueId =
                BlueIds.requireBlueIdOrCyclicMember(
                        occurrenceEventBlueId,
                        "occurrenceEventBlueId");
        FrozenNode frozenEvent = FrozenNode.fromResolvedNode(
                checkedEvent.clone());
        FrozenNode frozenOccurrence = FrozenNode.fromResolvedNode(
                checkedOccurrence.clone());
        String eventBlueId = CheckpointIdentityCalculator.identity(
                checkedEvent,
                owner.languageRuntimeAccess());
        return dispatch(
                scopePath,
                bundle,
                channelKey,
                checkedEvent,
                checkedOccurrence,
                admittedOccurrenceBlueId,
                frozenEvent,
                eventBlueId,
                java.util.Collections.singletonList(
                        new ExactBlueValue(
                                frozenOccurrence,
                                admittedOccurrenceBlueId)),
                false);
    }

    private boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            Node event,
            Node occurrenceEvent,
            String occurrenceEventBlueId,
            FrozenNode exactEvent,
            String exactEventBlueId,
            List<ExactBlueValue> carriedExactValues,
            boolean allowTerminatingScope) {
        ProcessingObserver metrics = owner.observer();
        long discoveryStart = System.nanoTime();
        List<ContractBundle.HandlerBinding> handlers =
                bundle.handlersFor(channelKey);
        ProcessingObservations.record(
                metrics,
                ProcessingMetricId.HANDLER_DISCOVERY_NANOS,
                System.nanoTime() - discoveryStart);
        if (handlers.isEmpty()) {
            return scopeMayContinue(scopePath, allowTerminatingScope);
        }
        for (ContractBundle.HandlerBinding handler : handlers) {
            if (!scopeMayContinue(scopePath, allowTerminatingScope)) {
                return false;
            }
            if (!matches(
                    scopePath,
                    channelKey,
                    event,
                    occurrenceEvent,
                    occurrenceEventBlueId,
                    bundle,
                    handler,
                    metrics)) {
                continue;
            }
            ContractBundle.HandlerBinding executableHandler =
                    materialize(scopePath, bundle, handler);
            if (executableHandler == null) {
                return false;
            }
            execute(
                    scopePath,
                    channelKey,
                    event,
                    occurrenceEvent,
                    exactEvent,
                    exactEventBlueId,
                    occurrenceEventBlueId,
                    carriedExactValues,
                    bundle,
                    handler,
                    executableHandler,
                    metrics);
            if (!scopeMayContinue(scopePath, allowTerminatingScope)) {
                return false;
            }
        }
        return scopeMayContinue(scopePath, allowTerminatingScope);
    }

    private boolean matches(
            String scopePath,
            String channelKey,
            Node event,
            Node occurrenceEvent,
            String occurrenceEventBlueId,
            ContractBundle bundle,
            ContractBundle.HandlerBinding handler,
            ProcessingObserver metrics) {
        RuntimeWorkSession matchWork = null;
        ExternalChannelFunctionEvaluation.MatcherSession matcherSession = null;
        Boolean matched = null;
        Throwable failure = null;
        boolean evidenceUnavailable = false;
        long matchStart = 0L;
        boolean timingStarted = false;
        try {
            matchWork = runtime.newRuntimeWorkSession(execution.blue());
            matcherSession = runtime.externalChannelMatcherSessions().open();
            HandlerMatchContext context = new HandlerMatchContext(
                    scopePath,
                    handler.key(),
                    channelKey,
                    event,
                    occurrenceEvent,
                    occurrenceEventBlueId,
                    bundle.markers(),
                    owner.matchingService(),
                    bundle.canonicalTypeIdentities(),
                    matchWork,
                    matcherSession);
            ProcessingObservations.record(
                    metrics, ProcessingMetricId.HANDLER_MATCH_ATTEMPTS, 1L);
            runtime.chargeHandlerCandidateTested(scopePath, handler.key());
            matchStart = System.nanoTime();
            timingStarted = true;
            matched = ProcessorEngine.matchesHandler(
                    owner, handler.contract(), context);
            matchWork.complete();
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            failure = unavailable;
            evidenceUnavailable = true;
        } catch (RuntimeException | Error caught) {
            failure = caught;
        } finally {
            if (failure != null) {
                failure = evidenceUnavailable
                        ? RuntimeWorkSession.suspendIfOpenPreserving(
                                matchWork, failure)
                        : RuntimeWorkSession.failIfOpenPreserving(
                                matchWork, failure);
            }
            failure = RuntimeWorkSession.closePreserving(
                    matcherSession == null ? null : matcherSession::close,
                    failure);
            failure = RuntimeWorkSession.closePreserving(matchWork, failure);
            if (timingStarted) {
                final long elapsed = System.nanoTime() - matchStart;
                failure = RuntimeWorkSession.closePreserving(
                        () -> ProcessingObservations.record(
                                metrics,
                                ProcessingMetricId.HANDLER_MATCH_NANOS,
                                elapsed),
                        failure);
            }
        }
        RuntimeWorkSession.rethrow(failure);
        return Objects.requireNonNull(matched, "handlerMatchResult");
    }

    private ContractBundle.HandlerBinding materialize(
            String scopePath,
            ContractBundle bundle,
            ContractBundle.HandlerBinding handler) {
        try {
            recordSelectedExecutableBodyDemands(scopePath, handler);
            return owner.contractLoader()
                    .materializeSelectedExecutableBodies(
                            handler,
                            runtime::resolveSelectedExecutableReference);
        } catch (RuntimeException exception) {
            if (exception instanceof GasLimitExceededException
                    || exception instanceof PortableLimitExceededException
                    || exception
                    instanceof ExecutionEvidenceUnavailableException
                    || exception
                    instanceof InvalidExecutionEvidenceException
                    || ScopeIdentityErrorMapper
                    .isProviderIdentityFailure(exception)) {
                throw exception;
            }
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    execution.fatalCategory(
                            exception,
                            ProcessorErrorCategory.RuntimeExecutionFailure),
                    execution.fatalReason(
                            exception,
                            "Handler executable body materialization failed"));
            return null;
        }
    }

    private void execute(
            String scopePath,
            String channelKey,
            Node event,
            Node occurrenceEvent,
            FrozenNode exactEvent,
            String exactEventBlueId,
            String occurrenceEventBlueId,
            List<ExactBlueValue> carriedExactValues,
            ContractBundle bundle,
            ContractBundle.HandlerBinding selectedHandler,
            ContractBundle.HandlerBinding executableHandler,
            ProcessingObserver metrics) {
        runtime.chargeHandlerOverhead(
                scopePath, selectedHandler.key());
        ContractBundle effectBundle = execution.bundleForScope(
                ProcessorEngine.normalizeScope(scopePath));
        if (effectBundle == null) {
            effectBundle = bundle;
        }
        ProcessorExecutionContext context = execution.createContext(
                scopePath,
                effectBundle,
                event,
                occurrenceEvent,
                exactEvent,
                exactEventBlueId,
                occurrenceEventBlueId,
                carriedExactValues,
                executableHandler.key(),
                executableHandler.node(),
                false);
        context.bindSelectedExecutableBodies(
                executableHandler.executableBodyFields(),
                selectedExecutableBodyBlueIds(selectedHandler),
                selectedHandler.node());
        context.bindEffectTypeIdentities(
                executableHandler.typeIdentities());
        ProcessingObservations.record(
                metrics, ProcessingMetricId.HANDLERS_EXECUTED, 1L);
        long executionStart = System.nanoTime();
        try (ProcessorExecutionContext ownedContext = context) {
            try {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put(
                        ProcessingTraceConstants.FIELD_CHANNEL_KEY,
                        channelKey);
                runtime.recordTrace(
                        ProcessingTraceRecord.Kind.HANDLER_EXECUTION,
                        scopePath,
                        executableHandler.key(),
                        null,
                        details,
                        event);
                ProcessorEngine.executeHandler(
                        owner,
                        executableHandler.contract(),
                        ownedContext);
                ownedContext.applyBufferedEffects();
            } catch (ExecutionEvidenceUnavailableException unavailable) {
                ownedContext.suspendRuntimeWork();
                throw unavailable;
            } catch (NoncommittingExecutionException suspension) {
                /*
                 * Buffered application effects run only after hosted runtime
                 * work has completed. The closure attempt owns that tentative
                 * gas prefix and discards it with the noncommitting attempt;
                 * a completed child runtime session cannot be suspended.
                 */
                throw suspension;
            }
        } catch (NoncommittingExecutionException suspension) {
            throw suspension;
        } catch (GasLimitExceededException
                 | PortableLimitExceededException
                 | SubscriptionSurfaceInvalidException
                 | ExecutionEvidenceUnavailableException
                 | InvalidExecutionEvidenceException
                 | DocumentStepRuntimeGapException exception) {
            throw exception;
        } catch (RunTerminationException exception) {
            throw exception;
        } catch (ProcessorFatalException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    exception.errorCategory(),
                    execution.fatalReason(
                            exception, "Handler execution failed"));
        } catch (RuntimeException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    execution.fatalCategory(
                            exception,
                            ProcessorErrorCategory.RuntimeExecutionFailure),
                    execution.fatalReason(
                            exception, "Handler execution failed"));
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.HANDLER_EXECUTION_NANOS,
                    System.nanoTime() - executionStart);
        }
    }

    private boolean scopeMayContinue(
            String scopePath,
            boolean allowTerminatingScope) {
        return allowTerminatingScope
                ? !execution.shouldStopScopeWork(scopePath)
                : execution.isScopeActive(scopePath);
    }

    private void recordSelectedExecutableBodyDemands(
            String scopePath,
            ContractBundle.HandlerBinding handler) {
        if (handler == null || handler.node() == null) {
            return;
        }
        for (String field : handler.executableBodyFields()) {
            List<String> path = new ArrayList<>(
                    JsonPointer.split(scopePath));
            path.add(ProcessorContractConstants.KEY_CONTRACTS);
            path.add(handler.key());
            path.add(field);
            runtime.recordSelectedExecutableBodyDemand(
                    handler.node().property(field),
                    scopePath,
                    handler.key(),
                    JsonPointer.toPointer(path));
        }
    }

    private Map<String, String> selectedExecutableBodyBlueIds(
            ContractBundle.HandlerBinding binding) {
        Map<String, String> identities = new LinkedHashMap<>();
        FrozenNode contract = binding != null ? binding.node() : null;
        Map<String, FrozenNode> properties = contract != null
                ? contract.getProperties() : null;
        if (properties == null) {
            return identities;
        }
        for (String field : binding.executableBodyFields()) {
            FrozenNode body = properties.get(field);
            if (body != null) {
                Node sourceProjection = body.toNode();
                identities.put(
                        field,
                        CanonicalIdentityEvidence.executableBodyBlueId(
                                sourceProjection,
                                runtime.snapshotManager,
                                "Selected executable body '" + field
                                        + "' for contract '"
                                        + binding.key() + "'"));
            }
        }
        return identities;
    }
}
