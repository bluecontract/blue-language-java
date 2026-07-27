package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.snapshot.FrozenNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Run-local recursive resolver for immutable External Channel functions.
 */
final class ExternalChannelFunctionResolver {

    private static final GasSchedule PORTABLE_LIMITS =
            GasSchedule.contracts10();

    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final ExternalChannelFunctionEvaluation.MatcherSession
            eventMatcher;
    private final ContractBundle bundle;
    private final Map<String, Header> headers = new LinkedHashMap<>();
    private final Deque<String> resolvingHeaders = new ArrayDeque<>();
    private final Deque<String> evaluatingEvents = new ArrayDeque<>();

    ExternalChannelFunctionResolver(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ContractBundle bundle) {
        this(registry, converter, null, bundle);
    }

    ExternalChannelFunctionResolver(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ExternalChannelFunctionEvaluation.MatcherSession
                    eventMatcher,
            ContractBundle bundle) {
        this.registry = Objects.requireNonNull(
                registry, "registry");
        this.converter = Objects.requireNonNull(
                converter, "converter");
        this.eventMatcher = eventMatcher;
        this.bundle = Objects.requireNonNull(bundle, "bundle");
    }

    Header header(EffectiveContractSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Header header = header(snapshot.key());
        if (!snapshot.scopePath().equals(header.snapshot.scopePath())
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
                requireExternalSnapshot(key);
        enter(resolvingHeaders, key, "dependency");
        try {
            ChannelContract probe = freshChannel(snapshot);
            ChannelProcessor processor =
                    registry.lookupChannel(probe).orElse(null);
            ExternalChannelSubscriptionFunctions functions =
                    processor != null
                            ? processor
                            .externalSubscriptionFunctions()
                            : null;
            if (functions == null) {
                throw new IllegalStateException(
                        "External Channel runtime type does not expose supported "
                                + "immutable subscription functions: "
                                + snapshot.effectiveTypeBlueId());
            }
            DependencyCapture capture =
                    new DependencyCapture(
                            snapshot
                                    .deterministicDependencyNodeBlueIds());
            ExternalChannelFunctionContext context =
                    context(snapshot, capture, false);
            List<String> channelKeys = immutableKeys(
                    functions.channelKeys(
                            freshChannel(snapshot), context),
                    "channel");
            String discriminator =
                    functions.checkpointDomainDiscriminator(
                            freshChannel(snapshot), context);
            ExternalChannelDependencySnapshot dependencies =
                    capture.snapshot();
            String domain = CheckpointDomain.derive(
                    snapshot.effectiveTypeBlueId(),
                    snapshot.sourceContributionNodeBlueIds(),
                    dependencies,
                    discriminator);
            FrozenNode node = requireContractNode(snapshot);
            Header created = new Header(
                    snapshot,
                    node,
                    channelKeys,
                    domain,
                    dependencies);
            headers.put(key, created);
            return created;
        } finally {
            resolvingHeaders.removeLast();
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
        enter(evaluatingEvents, key, "event-evaluation");
        try {
            ChannelContract probe = freshChannel(snapshot);
            ChannelProcessor processor =
                    registry.lookupChannel(probe).orElse(null);
            ExternalChannelSubscriptionFunctions functions =
                    processor != null
                            ? processor
                            .externalSubscriptionFunctions()
                            : null;
            if (functions == null) {
                throw new IllegalStateException(
                        "External Channel runtime type does not expose supported "
                                + "immutable subscription functions: "
                                + snapshot.effectiveTypeBlueId());
            }
            DependencyCapture capture =
                    new DependencyCapture(
                            snapshot
                                    .deterministicDependencyNodeBlueIds());
            ExternalChannelFunctionContext headerContext =
                    context(snapshot, capture, false);
            List<String> channelKeys = immutableKeys(
                    functions.channelKeys(
                            freshChannel(snapshot),
                            headerContext),
                    "channel");
            if (!header.channelKeys.equals(channelKeys)) {
                throw new IllegalStateException(
                        "External Channel subscription keys changed between "
                                + "header and event evaluation at "
                                + snapshot.scopePath() + "/" + key);
            }
            ExternalChannelFunctionContext context =
                    context(snapshot, capture, true);
            List<String> eventKeys = immutableKeys(
                    functions.eventKeys(
                            exactEvent.clone(), context),
                    "event");
            boolean preselects = preselects(
                    functions,
                    freshChannel(snapshot),
                    exactEvent.clone(),
                    context,
                    channelKeys,
                    eventKeys);
            boolean accepts = accepts(
                    functions,
                    freshChannel(snapshot),
                    exactEvent.clone(),
                    context,
                    preselects);

            FrozenNode payload = null;
            FrozenNode checkpointSubject = null;
            String handlerChannelKey = null;
            String logicalDeliveryKey = null;
            if (accepts) {
                Node suppliedPayload = functions.payload(
                        freshChannel(snapshot),
                        exactEvent.clone(),
                        context);
                if (suppliedPayload == null) {
                    throw new IllegalStateException(
                            "External Channel PAYLOAD returned no exact node "
                                    + "at " + snapshot.scopePath() + "/"
                                    + key);
                }
                payload = FrozenNode.fromResolvedNode(
                        suppliedPayload.clone());
                handlerChannelKey = immutableRoutingKey(
                        functions.handlerChannelKey(
                                freshChannel(snapshot),
                                exactEvent.clone(),
                                payload.toNode(),
                                context),
                        "handler Channel");
                logicalDeliveryKey = immutableRoutingKey(
                        functions.logicalDeliveryKey(
                                freshChannel(snapshot),
                                exactEvent.clone(),
                                payload.toNode(),
                                context),
                        "logical delivery");
                Node suppliedSubject = functions.checkpointSubject(
                        freshChannel(snapshot),
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
                    checkpointSubject =
                            FrozenNode.fromNode(
                                    suppliedSubject.clone());
                } catch (RuntimeException exception) {
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
                    checkpointSubject,
                    handlerChannelKey,
                    logicalDeliveryKey,
                    header.dependencies);
        } finally {
            evaluatingEvents.removeLast();
        }
    }

    private Evaluation evaluate(String key, Node exactEvent) {
        return evaluate(requireExternalSnapshot(key), exactEvent);
    }

    private ExternalChannelFunctionContext context(
            EffectiveContractSnapshot owner,
            DependencyCapture capture,
            boolean eventEvaluation) {
        return new ExternalChannelFunctionContext(
                owner.scopePath(),
                owner.key(),
                new ExternalChannelFunctionContext.Access() {
                    @Override
                    public ExternalChannelMemberSnapshot member(
                            String key) {
                        if (owner.key().equals(key)) {
                            throw cycle(
                                    owner.key(), owner.key(),
                                    "self dependency");
                        }
                        Header member = header(key);
                        capture.record(member);
                        return memberSnapshot(
                                member,
                                owner,
                                eventEvaluation);
                    }

                    @Override
                    public List<ExternalChannelMemberSnapshot> members() {
                        capture.wholeSurface();
                        List<EffectiveContractSnapshot> snapshots =
                                externalSnapshots(owner.key());
                        List<ExternalChannelMemberSnapshot> members =
                                new ArrayList<>(snapshots.size());
                        for (EffectiveContractSnapshot snapshot : snapshots) {
                            Header member = header(snapshot.key());
                            capture.record(member);
                            members.add(memberSnapshot(
                                    member,
                                    owner,
                                    eventEvaluation));
                        }
                        return Collections.unmodifiableList(members);
                    }

                    @Override
                    public List<ExternalChannelMemberSnapshot>
                    membersByEffectiveType(
                            String effectiveTypeBlueId) {
                        List<EffectiveContractSnapshot> matching =
                                new ArrayList<>();
                        for (EffectiveContractSnapshot snapshot
                                : externalSnapshots(owner.key())) {
                            if (effectiveTypeBlueId.equals(
                                    snapshot.effectiveTypeBlueId())) {
                                matching.add(snapshot);
                            }
                        }
                        capture.typeFamily(
                                owner.key(),
                                effectiveTypeBlueId,
                                matching);
                        List<ExternalChannelMemberSnapshot> members =
                                new ArrayList<>(matching.size());
                        for (EffectiveContractSnapshot snapshot
                                : matching) {
                            members.add(shallowMemberSnapshot(
                                    snapshot,
                                    capture,
                                    owner,
                                    eventEvaluation));
                        }
                        return Collections.unmodifiableList(members);
                    }

                    @Override
                    public boolean matchesPattern(
                            FrozenNode candidate,
                            FrozenNode pattern) {
                        if (!eventEvaluation) {
                            throw new IllegalStateException(
                                    "External Channel pattern matching is "
                                            + "available only during event "
                                            + "evaluation at "
                                            + owner.scopePath() + "/"
                                            + owner.key());
                        }
                        return eventMatcher.matches(
                                candidate,
                                pattern);
                    }

                    @Override
                    public FrozenNode materializeExactReference(
                            FrozenNode reference) {
                        requireEventEvaluation(
                                owner,
                                eventEvaluation,
                                "exact event fragment materialization");
                        return eventMatcher
                                .materializeExactReference(
                                        reference);
                    }
                });
    }

    private ExternalChannelMemberSnapshot memberSnapshot(
            Header header,
            EffectiveContractSnapshot owner,
            boolean eventEvaluation) {
        return new ExternalChannelMemberSnapshot(
                header.snapshot.key(),
                header.snapshot.order(),
                header.snapshot.effectiveTypeBlueId(),
                header.snapshot.sourceContributionNodeBlueIds(),
                header.dependencies,
                header.channelKeys,
                header.checkpointDomainBlueId,
                header.contractNode.toNode(),
                exactEvent -> {
                    requireEventEvaluation(
                            owner,
                            eventEvaluation,
                            "member evaluation");
                    Evaluation evaluation =
                            evaluate(
                                    header.snapshot.key(),
                                    exactEvent);
                    return new ExternalChannelMemberEvaluation(
                            evaluation.channelKeys,
                            evaluation.eventKeys,
                            evaluation.preselects,
                            evaluation.accepts,
                            evaluation.checkpointDomainBlueId,
                            evaluation.payload != null
                                    ? evaluation.payload.toNode()
                                    : null,
                            evaluation.checkpointSubject != null
                                    ? evaluation
                                    .checkpointSubject.toNode()
                                    : null,
                            evaluation.handlerChannelKey,
                            evaluation.logicalDeliveryKey);
                });
    }

    /**
     * Creates a member view from the immutable effective-contract header
     * without recursively running that member's subscription functions.
     * Derived fields and event evaluation resolve only the selected member and
     * promote it to a full dependency of the context owner.
     */
    private ExternalChannelMemberSnapshot shallowMemberSnapshot(
            EffectiveContractSnapshot snapshot,
            DependencyCapture capture,
            EffectiveContractSnapshot owner,
            boolean eventEvaluation) {
        FrozenNode contractNode = requireContractNode(snapshot);
        ExternalChannelMemberSnapshot.Header lazyHeader =
                new ExternalChannelMemberSnapshot.Header() {
                    private Header resolve() {
                        Header resolved = header(snapshot.key());
                        capture.record(resolved);
                        return resolved;
                    }

                    @Override
                    public ExternalChannelDependencySnapshot dependencies() {
                        return resolve().dependencies;
                    }

                    @Override
                    public List<String> channelKeys() {
                        return resolve().channelKeys;
                    }

                    @Override
                    public String checkpointDomainBlueId() {
                        return resolve().checkpointDomainBlueId;
                    }
                };
        return new ExternalChannelMemberSnapshot(
                snapshot.key(),
                snapshot.order(),
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                contractNode.toNode(),
                lazyHeader,
                exactEvent -> {
                    requireEventEvaluation(
                            owner,
                            eventEvaluation,
                            "member evaluation");
                    Header selected = header(snapshot.key());
                    capture.record(selected);
                    Evaluation evaluation =
                            evaluate(snapshot.key(), exactEvent);
                    return memberEvaluation(evaluation);
                });
    }

    private ExternalChannelMemberEvaluation memberEvaluation(
            Evaluation evaluation) {
        return new ExternalChannelMemberEvaluation(
                evaluation.channelKeys,
                evaluation.eventKeys,
                evaluation.preselects,
                evaluation.accepts,
                evaluation.checkpointDomainBlueId,
                evaluation.payload != null
                        ? evaluation.payload.toNode()
                        : null,
                evaluation.checkpointSubject != null
                        ? evaluation.checkpointSubject.toNode()
                        : null,
                evaluation.handlerChannelKey,
                evaluation.logicalDeliveryKey);
    }

    private List<EffectiveContractSnapshot> externalSnapshots(
            String excludedKey) {
        List<EffectiveContractSnapshot> snapshots =
                new ArrayList<>();
        long externalCount = 0L;
        for (EffectiveContractSnapshot snapshot
                : bundle.effectiveContractSnapshots()) {
            if ("external-channel".equals(snapshot.role())) {
                externalCount++;
                if (!snapshot.key().equals(excludedKey)) {
                    snapshots.add(snapshot);
                }
            }
        }
        long memberLimit = PORTABLE_LIMITS.portableLimit(
                "externalChannelsPerScope");
        if (externalCount > memberLimit) {
            throw new IllegalStateException(
                    "Same-scope External Channel dependency surface exceeds "
                            + memberLimit);
        }
        snapshots.sort(new Comparator<EffectiveContractSnapshot>() {
            @Override
            public int compare(
                    EffectiveContractSnapshot left,
                    EffectiveContractSnapshot right) {
                int order = Integer.compare(
                        left.order(), right.order());
                if (order != 0) {
                    return order;
                }
                int key = ExternalOrderKey.compareTextCodePoints(
                        left.key(), right.key());
                if (key != 0) {
                    return key;
                }
                return ExternalOrderKey.compareTextCodePoints(
                        left.effectiveTypeBlueId(),
                        right.effectiveTypeBlueId());
            }
        });
        return snapshots;
    }

    private EffectiveContractSnapshot requireExternalSnapshot(
            String key) {
        EffectiveContractSnapshot snapshot =
                bundle.effectiveContractSnapshot(key);
        if (snapshot == null) {
            throw new IllegalStateException(
                    "Missing same-scope External Channel dependency: "
                            + key);
        }
        if (!"external-channel".equals(snapshot.role())) {
            throw new IllegalStateException(
                    "Same-scope dependency is not an External Channel: "
                            + key);
        }
        return snapshot;
    }

    private FrozenNode requireContractNode(
            EffectiveContractSnapshot snapshot) {
        FrozenNode content =
                bundle.contractNode(snapshot.key());
        if (content == null) {
            throw new IllegalStateException(
                    "External Channel effective content is unavailable at "
                            + snapshot.scopePath() + "/"
                            + snapshot.key());
        }
        return content;
    }

    private ChannelContract freshChannel(
            EffectiveContractSnapshot snapshot) {
        Contract converted = converter.convertWithType(
                requireContractNode(snapshot).toNode(),
                Contract.class,
                false);
        if (!(converted instanceof ChannelContract)) {
            throw new IllegalStateException(
                    "External Channel could not be converted at "
                            + snapshot.scopePath() + "/"
                            + snapshot.key());
        }
        ChannelContract channel = (ChannelContract) converted;
        channel.setKey(snapshot.key());
        channel.setTypeBlueId(snapshot.effectiveTypeBlueId());
        return channel;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean preselects(
            ExternalChannelSubscriptionFunctions functions,
            ChannelContract channel,
            Node event,
            ExternalChannelFunctionContext context,
            List<String> channelKeys,
            List<String> eventKeys) {
        boolean contextualOverride =
                overridesExact(
                        functions,
                        "preselects",
                        ChannelContract.class,
                        Node.class,
                        ExternalChannelFunctionContext.class);
        boolean contextFreeOverride =
                overridesExact(
                        functions,
                        "preselects",
                        ChannelContract.class,
                        Node.class);
        if (!contextualOverride
                && !contextFreeOverride) {
            Set<String> eventKeySet =
                    new LinkedHashSet<>(eventKeys);
            for (String channelKey : channelKeys) {
                if (eventKeySet.contains(channelKey)) {
                    return true;
                }
            }
            return false;
        }
        if (!contextualOverride) {
            return functions.preselects(channel, event);
        }
        return functions.preselects(channel, event, context);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean accepts(
            ExternalChannelSubscriptionFunctions functions,
            ChannelContract channel,
            Node event,
            ExternalChannelFunctionContext context,
            boolean preselects) {
        boolean contextualOverride =
                overridesExact(
                        functions,
                        "accepts",
                        ChannelContract.class,
                        Node.class,
                        ExternalChannelFunctionContext.class);
        boolean contextFreeOverride =
                overridesExact(
                        functions,
                        "accepts",
                        ChannelContract.class,
                        Node.class);
        if (!contextualOverride
                && !contextFreeOverride) {
            return preselects;
        }
        if (!contextualOverride) {
            return functions.accepts(channel, event);
        }
        return functions.accepts(channel, event, context);
    }

    static boolean overridesExact(
            ExternalChannelSubscriptionFunctions<?> functions,
            String name,
            Class<?>... parameterTypes) {
        final java.lang.reflect.Method method;
        try {
            method = functions.getClass().getMethod(
                    name,
                    parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "External Channel function signature is unavailable: "
                            + name,
                    exception);
        }
        return method.getDeclaringClass()
                != ExternalChannelSubscriptionFunctions.class;
    }

    private void requireEventEvaluation(
            EffectiveContractSnapshot owner,
            boolean eventEvaluation,
            String operation) {
        if (!eventEvaluation) {
            throw new IllegalStateException(
                    "External Channel " + operation
                            + " is available only during event "
                            + "evaluation at "
                            + owner.scopePath() + "/"
                            + owner.key());
        }
        eventMatcher.requireActive();
    }

    private void enter(
            Deque<String> stack,
            String key,
            String phase) {
        long depthLimit = PORTABLE_LIMITS.portableLimit(
                "embeddedDepth");
        if (stack.size() >= depthLimit) {
            throw new IllegalStateException(
                    "External Channel " + phase
                            + " depth exceeds "
                            + depthLimit);
        }
        if (stack.contains(key)) {
            throw cycle(
                    stack.peekLast(), key, phase);
        }
        stack.addLast(key);
    }

    private IllegalStateException cycle(
            String from, String to, String phase) {
        return new IllegalStateException(
                "Cyclic same-scope External Channel dependency during "
                        + phase + ": " + from + " -> " + to);
    }

    private static List<String> immutableKeys(
            List<String> supplied,
            String label) {
        if (supplied == null) {
            throw new IllegalStateException(
                    "External subscription " + label
                            + " key function returned no finite set");
        }
        List<String> copy = new ArrayList<>(supplied);
        Set<String> unique = new LinkedHashSet<>();
        for (String key : copy) {
            if (key == null || key.isEmpty()
                    || !unique.add(key)) {
                throw new IllegalStateException(
                        "External subscription " + label
                                + " keys must be unique non-empty Text");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    static String immutableRoutingKey(
            String supplied,
            String label) {
        if (supplied == null || supplied.isEmpty()) {
            throw new IllegalStateException(
                    "External Channel " + label
                            + " key must be non-empty Text");
        }
        long codePoints =
                supplied.codePointCount(0, supplied.length());
        long codePointLimit = PORTABLE_LIMITS.portableLimit(
                "contractKeyCodePoints");
        if (codePoints > codePointLimit) {
            throw new IllegalStateException(
                    "External Channel " + label
                            + " key exceeds contractKeyCodePoints portable "
                            + "limit " + codePointLimit + ": "
                            + codePoints);
        }
        long utf8Bytes =
                supplied.getBytes(StandardCharsets.UTF_8).length;
        long utf8Limit = PORTABLE_LIMITS.portableLimit(
                "contractKeyUtf8Bytes");
        if (utf8Bytes > utf8Limit) {
            throw new IllegalStateException(
                    "External Channel " + label
                            + " key exceeds contractKeyUtf8Bytes portable "
                            + "limit " + utf8Limit + ": "
                            + utf8Bytes);
        }
        return supplied;
    }

    static final class Header {
        private final EffectiveContractSnapshot snapshot;
        private final FrozenNode contractNode;
        private final List<String> channelKeys;
        private final String checkpointDomainBlueId;
        private final ExternalChannelDependencySnapshot dependencies;

        private Header(
                EffectiveContractSnapshot snapshot,
                FrozenNode contractNode,
                List<String> channelKeys,
                String checkpointDomainBlueId,
                ExternalChannelDependencySnapshot dependencies) {
            this.snapshot = snapshot;
            this.contractNode = contractNode;
            this.channelKeys = channelKeys;
            this.checkpointDomainBlueId =
                    checkpointDomainBlueId;
            this.dependencies = dependencies;
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

        boolean sameResult(Header other) {
            return other != null
                    && channelKeys.equals(other.channelKeys)
                    && checkpointDomainBlueId.equals(
                    other.checkpointDomainBlueId)
                    && dependencies.equals(other.dependencies);
        }
    }

    static final class Evaluation {
        private final List<String> channelKeys;
        private final List<String> eventKeys;
        private final boolean preselects;
        private final boolean accepts;
        private final String checkpointDomainBlueId;
        private final FrozenNode payload;
        private final FrozenNode checkpointSubject;
        private final String handlerChannelKey;
        private final String logicalDeliveryKey;
        private final ExternalChannelDependencySnapshot dependencies;

        private Evaluation(
                List<String> channelKeys,
                List<String> eventKeys,
                boolean preselects,
                boolean accepts,
                String checkpointDomainBlueId,
                FrozenNode payload,
                FrozenNode checkpointSubject,
                String handlerChannelKey,
                String logicalDeliveryKey,
                ExternalChannelDependencySnapshot dependencies) {
            this.channelKeys = channelKeys;
            this.eventKeys = eventKeys;
            this.preselects = preselects;
            this.accepts = accepts;
            this.checkpointDomainBlueId =
                    checkpointDomainBlueId;
            this.payload = payload;
            this.checkpointSubject = checkpointSubject;
            this.handlerChannelKey = handlerChannelKey;
            this.logicalDeliveryKey = logicalDeliveryKey;
            this.dependencies = dependencies;
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

        FrozenNode checkpointSubject() {
            return checkpointSubject;
        }

        String handlerChannelKey() {
            return handlerChannelKey;
        }

        String logicalDeliveryKey() {
            return logicalDeliveryKey;
        }

        ExternalChannelDependencySnapshot dependencies() {
            return dependencies;
        }
    }

    private static final class DependencyCapture {
        private final List<String> intrinsic;
        private final Map<String, ExternalChannelDependencySnapshot.Entry>
                entries = new LinkedHashMap<>();
        private final Map<String, ExternalChannelDependencySnapshot.TypeFamily>
                typeFamilies = new LinkedHashMap<>();
        private boolean wholeSurface;

        private DependencyCapture(List<String> intrinsic) {
            this.intrinsic = new ArrayList<>(intrinsic);
        }

        private void record(Header header) {
            record(new ExternalChannelDependencySnapshot.Entry(
                    header.snapshot.key(),
                    header.snapshot.order(),
                    header.snapshot.effectiveTypeBlueId(),
                    header.snapshot
                            .sourceContributionNodeBlueIds(),
                    header.dependencies
                            .deterministicDependencyNodeBlueIds(),
                    header.checkpointDomainBlueId));
            for (ExternalChannelDependencySnapshot.Entry dependency
                    : header.dependencies.entries()) {
                record(dependency);
            }
            for (ExternalChannelDependencySnapshot.TypeFamily family
                    : header.dependencies.typeFamilies()) {
                record(family);
            }
            wholeSurface |= header.dependencies
                    .wholeSameScopeExternalSurface();
        }

        private void record(
                ExternalChannelDependencySnapshot.Entry entry) {
            ExternalChannelDependencySnapshot.Entry prior =
                    entries.get(entry.channelKey());
            if (prior != null && !prior.equals(entry)) {
                throw new IllegalStateException(
                        "Conflicting same-scope External Channel dependency "
                                + "snapshot for " + entry.channelKey());
            }
            if (prior == null) {
                entries.put(entry.channelKey(), entry);
            }
        }

        private void typeFamily(
                String excludingChannelKey,
                String effectiveTypeBlueId,
                List<EffectiveContractSnapshot> matching) {
            List<ExternalChannelDependencySnapshot.Member> members =
                    new ArrayList<>(matching.size());
            for (EffectiveContractSnapshot snapshot : matching) {
                members.add(
                        new ExternalChannelDependencySnapshot.Member(
                                snapshot.key(),
                                snapshot.order(),
                                snapshot.sourceContributionNodeBlueIds(),
                                snapshot
                                        .deterministicDependencyNodeBlueIds()));
            }
            record(new ExternalChannelDependencySnapshot.TypeFamily(
                    excludingChannelKey,
                    effectiveTypeBlueId,
                    members));
        }

        private void record(
                ExternalChannelDependencySnapshot.TypeFamily family) {
            String selector = family.excludingChannelKey()
                    + "\u0000" + family.effectiveTypeBlueId();
            ExternalChannelDependencySnapshot.TypeFamily prior =
                    typeFamilies.get(selector);
            if (prior != null && !prior.equals(family)) {
                throw new IllegalStateException(
                        "Conflicting same-scope External Channel type-family "
                                + "snapshot for "
                                + family.effectiveTypeBlueId()
                                + " excluding "
                                + family.excludingChannelKey());
            }
            if (prior == null) {
                typeFamilies.put(selector, family);
            }
        }

        private void wholeSurface() {
            wholeSurface = true;
        }

        private ExternalChannelDependencySnapshot snapshot() {
            if (intrinsic.isEmpty()
                    && entries.isEmpty()
                    && typeFamilies.isEmpty()
                    && !wholeSurface) {
                return ExternalChannelDependencySnapshot.none();
            }
            return new ExternalChannelDependencySnapshot(
                    intrinsic,
                    new ArrayList<>(entries.values()),
                    new ArrayList<>(typeFamilies.values()),
                    wholeSurface);
        }
    }
}
