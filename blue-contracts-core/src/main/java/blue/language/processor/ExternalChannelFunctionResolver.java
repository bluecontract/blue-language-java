package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Run-local recursive resolver for immutable External Channel functions.
 *
 * <p>Headers are indexed once in deterministic key order. Recursive lookups
 * retain exact dependency snapshots and are bounded by portable depth and
 * catalog limits; executable bodies and unrelated provider content remain
 * unopened.</p>
 */
final class ExternalChannelFunctionResolver {

    private final ExternalChannelFunctionEvaluation.MatcherSession
            eventMatcher;
    private final RuntimeWorkSession runtimeWorkSession;
    private final ExternalChannelResolverCatalog catalog;
    private final ExternalChannelResolutionCycleGuard cycleGuard =
            new ExternalChannelResolutionCycleGuard();
    private final Map<String, Header> headers = new LinkedHashMap<>();
    private final ExternalChannelFunctionContextFactory contextFactory;

    ExternalChannelFunctionResolver(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ContractBundle bundle) {
        this(registry, converter, null, bundle, null, null);
    }

    ExternalChannelFunctionResolver(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ExternalChannelFunctionEvaluation.MatcherSession eventMatcher,
            ContractBundle bundle) {
        this(registry, converter, eventMatcher, bundle, null, null);
    }

    ExternalChannelFunctionResolver(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ExternalChannelFunctionEvaluation.MatcherSession eventMatcher,
            ContractBundle bundle,
            List<String> effectiveContractKeys) {
        this(
                registry,
                converter,
                eventMatcher,
                bundle,
                effectiveContractKeys,
                null);
    }

    ExternalChannelFunctionResolver(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ExternalChannelFunctionEvaluation.MatcherSession eventMatcher,
            ContractBundle bundle,
            List<String> effectiveContractKeys,
            RuntimeWorkSession runtimeWorkSession) {
        this.eventMatcher = eventMatcher;
        this.runtimeWorkSession = runtimeWorkSession;
        this.catalog = new ExternalChannelResolverCatalog(
                registry,
                converter,
                bundle,
                effectiveContractKeys);
        this.contextFactory = new ExternalChannelFunctionContextFactory(
                eventMatcher,
                runtimeWorkSession,
                catalog,
                new ExternalChannelFunctionContextFactory.ResolverAccess() {
                    @Override
                    public Header header(String key) {
                        return ExternalChannelFunctionResolver.this
                                .header(key);
                    }

                    @Override
                    public Evaluation evaluate(
                            String key,
                            Node exactEvent) {
                        return ExternalChannelFunctionResolver.this
                                .evaluate(key, exactEvent);
                    }

                    @Override
                    public IllegalStateException cycle(
                            String from,
                            String to,
                            String phase) {
                        return cycleGuard.cycle(from, to, phase);
                    }
                });
    }

    Header header(EffectiveContractSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Header header = header(snapshot.key());
        if (!snapshot.scopePath().equals(
                header.snapshot.scopePath())
                || !snapshot.effectiveTypeBlueId().equals(
                header.snapshot.effectiveTypeBlueId())) {
            throw new IllegalStateException(
                    "External Channel snapshot changed during evaluation at "
                            + snapshot.scopePath() + "/" + snapshot.key());
        }
        return header;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    Header header(String key) {
        Header cached = headers.get(key);
        if (cached != null) {
            return cached;
        }
        EffectiveContractSnapshot snapshot =
                catalog.requireExternalSnapshot(key);
        cycleGuard.enterHeader(key);
        try {
            ExternalChannelSubscriptionFunctions functions =
                    catalog.subscriptionFunctions(snapshot);
            ExternalChannelDependencyCapture capture =
                    new ExternalChannelDependencyCapture(
                            snapshot.deterministicDependencyNodeBlueIds());
            ExternalChannelFunctionContext context =
                    contextFactory.create(
                            snapshot,
                            capture,
                            false,
                            ExternalChannelDependencySnapshot.none(),
                            null);
            List<String> channelKeys =
                    ExternalChannelFunctionRules.immutableKeys(
                            functions.channelKeys(
                                    catalog.freshChannel(snapshot),
                                    context),
                            "channel");
            String discriminator =
                    functions.checkpointDomainDiscriminator(
                            catalog.freshChannel(snapshot),
                            context);
            ExternalChannelDependencySnapshot dependencies =
                    capture.snapshot();
            String domain = CheckpointDomain.derive(
                    snapshot.effectiveTypeBlueId(),
                    snapshot.sourceContributionNodeBlueIds(),
                    dependencies,
                    discriminator);
            FrozenNode node = catalog.requireContractNode(snapshot);
            Header created = new Header(
                    snapshot,
                    node,
                    channelKeys,
                    domain,
                    dependencies,
                    discriminator);
            headers.put(key, created);
            return created;
        } finally {
            cycleGuard.leaveHeader();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    Evaluation evaluate(
            EffectiveContractSnapshot snapshot,
            Node exactEvent) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(exactEvent, "exactEvent");
        if (eventMatcher == null) {
            throw new IllegalStateException(
                    "External Channel event matcher is unavailable at "
                            + snapshot.scopePath() + "/"
                            + snapshot.key());
        }
        Header header = header(snapshot);
        String key = snapshot.key();
        cycleGuard.enterEvent(key);
        try {
            ExternalChannelSubscriptionFunctions functions =
                    catalog.subscriptionFunctions(snapshot);
            ExternalChannelDependencyCapture capture =
                    new ExternalChannelDependencyCapture(
                            snapshot.deterministicDependencyNodeBlueIds());
            ExternalChannelFunctionContext headerContext =
                    contextFactory.create(
                            snapshot,
                            capture,
                            false,
                            header.dependencies,
                            null);
            List<String> channelKeys =
                    ExternalChannelFunctionRules.immutableKeys(
                            functions.channelKeys(
                                    catalog.freshChannel(snapshot),
                                    headerContext),
                            "channel");
            if (!header.channelKeys.equals(channelKeys)) {
                throw new IllegalStateException(
                        "External Channel subscription keys changed between "
                                + "header and event evaluation at "
                                + snapshot.scopePath() + "/" + key);
            }
            ExternalChannelFunctionContext context =
                    contextFactory.create(
                            snapshot,
                            capture,
                            true,
                            header.dependencies,
                            exactEvent);
            context.exactEventBlueId();
            List<String> eventKeys =
                    ExternalChannelFunctionRules.immutableKeys(
                            functions.eventKeys(
                                    exactEvent.clone(), context),
                            "event");
            boolean preselects = ExternalChannelFunctionRules.preselects(
                    functions,
                    catalog.freshChannel(snapshot),
                    exactEvent.clone(),
                    context,
                    channelKeys,
                    eventKeys);
            boolean accepts = ExternalChannelFunctionRules.accepts(
                    functions,
                    catalog.freshChannel(snapshot),
                    exactEvent.clone(),
                    context,
                    preselects);

            FrozenNode payload = null;
            FrozenNode checkpointSubject = null;
            String payloadBlueId = null;
            String checkpointSubjectBlueId = null;
            String handlerChannelKey = null;
            String logicalDeliveryKey = null;
            ChannelMemberSnapshot handlerChannel = null;
            if (accepts) {
                Node suppliedPayload = functions.payload(
                        catalog.freshChannel(snapshot),
                        exactEvent.clone(),
                        context);
                if (suppliedPayload == null) {
                    throw new IllegalStateException(
                            "External Channel PAYLOAD returned no exact node "
                                    + "at " + snapshot.scopePath() + "/"
                                    + key);
                }
                ExactBlueValue admittedPayload =
                        admitHostedOutput(suppliedPayload);
                payload = admittedPayload.frozenValue();
                payloadBlueId = admittedPayload.blueId();
                handlerChannelKey = immutableRoutingKey(
                        functions.handlerChannelKey(
                                catalog.freshChannel(snapshot),
                                exactEvent.clone(),
                                payload.toNode(),
                                context),
                        "handler Channel");
                handlerChannel = catalog.handlerChannelForDispatch(
                        snapshot,
                        handlerChannelKey,
                        header.dependencies);
                logicalDeliveryKey = immutableRoutingKey(
                        functions.logicalDeliveryKey(
                                catalog.freshChannel(snapshot),
                                exactEvent.clone(),
                                payload.toNode(),
                                context),
                        "logical delivery");
                Node suppliedSubject = functions.checkpointSubject(
                        catalog.freshChannel(snapshot),
                        exactEvent.clone(),
                        payload.toNode(),
                        context);
                if (suppliedSubject == null) {
                    throw new IllegalStateException(
                            "External Channel CHECKPOINT_SUBJECT returned no "
                                    + "exact node at "
                                    + snapshot.scopePath() + "/" + key);
                }
                try {
                    ExactBlueValue admittedSubject =
                            admitHostedOutput(suppliedSubject);
                    checkpointSubject = suppliedSubject.isReferenceOnly()
                            ? FrozenNode.fromNode(suppliedSubject.clone())
                            : admittedSubject.frozenValue();
                    checkpointSubjectBlueId = admittedSubject.blueId();
                } catch (RuntimeException exception) {
                    if (exception instanceof GasLimitExceededException
                            || exception
                            instanceof PortableLimitExceededException
                            || exception
                            instanceof ExecutionEvidenceUnavailableException) {
                        throw exception;
                    }
                    throw new IllegalStateException(
                            "External Channel CHECKPOINT_SUBJECT is not exact "
                                    + "BlueId Input at "
                                    + snapshot.scopePath() + "/" + key,
                            exception);
                }
            }
            ExternalChannelDependencySnapshot eventDependencies =
                    capture.snapshot();
            if (!header.dependencies.covers(eventDependencies)) {
                throw new IllegalStateException(
                        "External Channel event evaluation consulted an "
                                + "undeclared same-scope dependency at "
                                + snapshot.scopePath() + "/" + key);
            }
            return new Evaluation(
                    channelKeys,
                    eventKeys,
                    preselects,
                    accepts,
                    header.checkpointDomainBlueId,
                    payload,
                    payloadBlueId,
                    checkpointSubject,
                    checkpointSubjectBlueId,
                    handlerChannelKey,
                    logicalDeliveryKey,
                    handlerChannel,
                    header.dependencies,
                    header.runtimeDiscriminator,
                    contextFactory.channelLookupResults());
        } finally {
            cycleGuard.leaveEvent();
        }
    }

    private ExactBlueValue admitHostedOutput(Node output) {
        Node exact = Objects.requireNonNull(output, "output");
        ExactBlueValue carried = runtimeWorkSession != null
                ? runtimeWorkSession.carriedExactInput(exact)
                : null;
        if (carried != null) {
            return carried;
        }
        if (runtimeWorkSession == null
                || !runtimeWorkSession.hasSemanticOutputBoundary()) {
            throw new IllegalStateException(
                    "External Channel hosted output requires a live "
                            + "Language semantic admission boundary");
        }
        return runtimeWorkSession.semanticOutputBoundary().admit(exact);
    }

    private Evaluation evaluate(String key, Node exactEvent) {
        return evaluate(catalog.requireExternalSnapshot(key), exactEvent);
    }

    static boolean overridesExact(
            ExternalChannelSubscriptionFunctions<?> functions,
            String name,
            Class<?>... parameterTypes) {
        return ExternalChannelFunctionRules.overridesExact(
                functions, name, parameterTypes);
    }

    static String immutableRoutingKey(String supplied, String label) {
        return ExternalChannelFunctionRules.immutableRoutingKey(
                supplied, label);
    }

    /** Immutable result of header-only external-channel evaluation. */
    static final class Header {
        private final EffectiveContractSnapshot snapshot;
        private final FrozenNode contractNode;
        private final List<String> channelKeys;
        private final String checkpointDomainBlueId;
        private final ExternalChannelDependencySnapshot dependencies;
        private final String runtimeDiscriminator;

        private Header(
                EffectiveContractSnapshot snapshot,
                FrozenNode contractNode,
                List<String> channelKeys,
                String checkpointDomainBlueId,
                ExternalChannelDependencySnapshot dependencies,
                String runtimeDiscriminator) {
            this.snapshot = snapshot;
            this.contractNode = contractNode;
            this.channelKeys = channelKeys;
            this.checkpointDomainBlueId = checkpointDomainBlueId;
            this.dependencies = dependencies;
            this.runtimeDiscriminator = runtimeDiscriminator;
        }

        List<String> channelKeys() {
            return channelKeys;
        }

        String checkpointDomainBlueId() {
            return checkpointDomainBlueId;
        }

        ExternalChannelDependencySnapshot dependencies() {
            return dependencies;
        }

        String runtimeDiscriminator() {
            return runtimeDiscriminator;
        }

        boolean sameResult(Header other) {
            return other != null
                    && channelKeys.equals(other.channelKeys)
                    && checkpointDomainBlueId.equals(
                    other.checkpointDomainBlueId)
                    && dependencies.equals(other.dependencies)
                    && Objects.equals(
                    runtimeDiscriminator,
                    other.runtimeDiscriminator);
        }

        EffectiveContractSnapshot snapshotInternal() {
            return snapshot;
        }

        FrozenNode contractNodeInternal() {
            return contractNode;
        }
    }

    /** Immutable full evaluation used by routing and checkpointing. */
    static final class Evaluation {
        private final List<String> channelKeys;
        private final List<String> eventKeys;
        private final boolean preselects;
        private final boolean accepts;
        private final String checkpointDomainBlueId;
        private final FrozenNode payload;
        private final String payloadBlueId;
        private final FrozenNode checkpointSubject;
        private final String checkpointSubjectBlueId;
        private final String handlerChannelKey;
        private final String logicalDeliveryKey;
        private final ChannelMemberSnapshot handlerChannel;
        private final ExternalChannelDependencySnapshot dependencies;
        private final String runtimeDiscriminator;
        private final List<String> channelLookupResults;

        private Evaluation(
                List<String> channelKeys,
                List<String> eventKeys,
                boolean preselects,
                boolean accepts,
                String checkpointDomainBlueId,
                FrozenNode payload,
                String payloadBlueId,
                FrozenNode checkpointSubject,
                String checkpointSubjectBlueId,
                String handlerChannelKey,
                String logicalDeliveryKey,
                ChannelMemberSnapshot handlerChannel,
                ExternalChannelDependencySnapshot dependencies,
                String runtimeDiscriminator,
                List<String> channelLookupResults) {
            this.channelKeys = channelKeys;
            this.eventKeys = eventKeys;
            this.preselects = preselects;
            this.accepts = accepts;
            this.checkpointDomainBlueId = checkpointDomainBlueId;
            this.payload = payload;
            this.payloadBlueId = payloadBlueId;
            this.checkpointSubject = checkpointSubject;
            this.checkpointSubjectBlueId = checkpointSubjectBlueId;
            this.handlerChannelKey = handlerChannelKey;
            this.logicalDeliveryKey = logicalDeliveryKey;
            this.handlerChannel = handlerChannel;
            this.dependencies = dependencies;
            this.runtimeDiscriminator = runtimeDiscriminator;
            this.channelLookupResults = Collections.unmodifiableList(
                    new ArrayList<>(channelLookupResults));
        }

        List<String> channelKeys() {
            return channelKeys;
        }

        List<String> eventKeys() {
            return eventKeys;
        }

        boolean preselects() {
            return preselects;
        }

        boolean accepts() {
            return accepts;
        }

        String checkpointDomainBlueId() {
            return checkpointDomainBlueId;
        }

        FrozenNode payload() {
            return payload;
        }

        String payloadBlueId() {
            return payloadBlueId;
        }

        FrozenNode checkpointSubject() {
            return checkpointSubject;
        }

        String checkpointSubjectBlueId() {
            return checkpointSubjectBlueId;
        }

        String handlerChannelKey() {
            return handlerChannelKey;
        }

        String logicalDeliveryKey() {
            return logicalDeliveryKey;
        }

        ChannelMemberSnapshot handlerChannel() {
            return handlerChannel;
        }

        ExternalChannelDependencySnapshot dependencies() {
            return dependencies;
        }

        String runtimeDiscriminator() {
            return runtimeDiscriminator;
        }

        List<String> channelLookupResults() {
            return channelLookupResults;
        }
    }
}
