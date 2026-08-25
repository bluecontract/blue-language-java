package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;

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
                scopePath, bundle, channelKey, event, event, null,
                java.util.Collections.<ExactBlueValue>emptyList(), false);
    }

    boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            FrozenNode exactEvent,
            List<ExactBlueValue> carriedExactValues) {
        FrozenNode carried = Objects.requireNonNull(exactEvent, "exactEvent");
        Node event = carried.toNode();
        return dispatch(
                scopePath, bundle, channelKey, event, event, carried,
                carriedExactValues, false);
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
                null,
                java.util.Collections.<ExactBlueValue>emptyList(),
                allowTerminatingScope);
    }

    boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            Node event,
            Node occurrenceEvent) {
        return dispatch(
                scopePath,
                bundle,
                channelKey,
                event,
                occurrenceEvent,
                null,
                java.util.Collections.<ExactBlueValue>emptyList(),
                false);
    }

    private boolean dispatch(
            String scopePath,
            ContractBundle bundle,
            String channelKey,
            Node event,
            Node occurrenceEvent,
            FrozenNode exactEvent,
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
            ContractBundle bundle,
            ContractBundle.HandlerBinding handler,
            ProcessingObserver metrics) {
        RuntimeWorkSession matchWork = runtime.newRuntimeWorkSession(
                execution.blue());
        ExternalChannelFunctionEvaluation.MatcherSession matcherSession =
                runtime.externalChannelMatcherSessions().open();
        HandlerMatchContext context = new HandlerMatchContext(
                scopePath,
                handler.key(),
                channelKey,
                event,
                occurrenceEvent,
                bundle.markers(),
                owner.matchingService(),
                matchWork,
                matcherSession);
        ProcessingObservations.record(
                metrics, ProcessingMetricId.HANDLER_MATCH_ATTEMPTS, 1L);
        runtime.chargeHandlerCandidateTested(scopePath, handler.key());
        long matchStart = System.nanoTime();
        try {
            boolean matches = ProcessorEngine.matchesHandler(
                    owner, handler.contract(), context);
            matchWork.complete();
            return matches;
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            matchWork.suspend();
            throw unavailable;
        } catch (RuntimeException | Error failure) {
            matchWork.failDeterministically();
            throw failure;
        } finally {
            matcherSession.close();
            matchWork.close();
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.HANDLER_MATCH_NANOS,
                    System.nanoTime() - matchStart);
        }
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
                            runtime::materializeSelectedExecutableReference);
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
                carriedExactValues,
                executableHandler.key(),
                executableHandler.node(),
                false);
        context.bindSelectedExecutableBodies(
                executableHandler.executableBodyFields(),
                selectedExecutableBodyBlueIds(selectedHandler));
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
        }
        } catch (GasLimitExceededException
                 | PortableLimitExceededException
                 | SubscriptionSurfaceInvalidException
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
                identities.put(
                        field,
                        body.isReferenceOnly()
                                ? body.getReferenceBlueId()
                                : body.blueId());
            }
        }
        return identities;
    }
}
