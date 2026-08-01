package blue.language.examples;

import blue.language.BlueRuntime;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasMeter;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.provider.NodeProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Shared exact runtime types and feeder evidence for Contracts examples. */
public final class ContractsExampleSupport {

    static final String SOURCE_CHANNEL_KEY = "incoming";
    static final String TARGET_CHANNEL_KEY = "accepted";
    static final String KEY_AMOUNT = "amount";
    static final String KEY_CHANNEL = "channel";
    static final String KEY_COUNTER_PATH = "counterPath";
    static final String KEY_DELIVERY_SCOPE = "deliveryScope";
    static final String KEY_EXTERNAL_SUBSCRIPTION = "subscription";
    static final String KEY_LABEL = "label";
    static final String KEY_ORIGIN_SCOPE = "originScope";
    static final String KEY_UNITS = "units";
    static final String ROOT_SCOPE = "/";
    static final String CHILD_SCOPE = "/child";

    private static final String ROOT_SUBSCRIPTION_KEY = "root-incoming";
    private static final String CHILD_SUBSCRIPTION_KEY = "child-incoming";
    private static final String TARGET_SUBSCRIPTION_KEY = "handler-only";

    static final String COUNTER_KEY = "counter";
    private static final String CHILD_KEY = "child";
    static final String ADD_HANDLER_KEY = "addAmount";
    private static final String EMIT_HANDLER_KEY = "emit";
    private static final String GAS_HANDLER_KEY = "chargeWork";
    private static final String RUNTIME_NAMESPACE = "example.runtime";
    private static final String RUNTIME_COUNTER = "operation";
    private static final long RUNTIME_COUNTER_WEIGHT = 7L;

    private static final Node CHANNEL_TYPE_NODE =
            typeNode(ExampleExternalChannel.class);
    private static final Node ADD_HANDLER_TYPE_NODE =
            typeNode(AddAmount.class);
    private static final Node EMIT_HANDLER_TYPE_NODE =
            typeNode(EmitApplicationEvent.class);
    private static final Node GAS_HANDLER_TYPE_NODE =
            typeNode(ChargeRuntimeWork.class);

    static final String CHANNEL_TYPE_BLUE_ID = blueId(CHANNEL_TYPE_NODE);
    static final String ADD_HANDLER_TYPE_BLUE_ID =
            blueId(ADD_HANDLER_TYPE_NODE);
    static final String EMIT_HANDLER_TYPE_BLUE_ID =
            blueId(EMIT_HANDLER_TYPE_NODE);
    static final String GAS_HANDLER_TYPE_BLUE_ID =
            blueId(GAS_HANDLER_TYPE_NODE);

    private ContractsExampleSupport() {
    }

    static BlueRuntime runtime(
            NodeProvider provider,
            RuntimeWorkProcessor runtimeWorkProcessor) {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_TYPE_BLUE_ID,
                                CHANNEL_TYPE_NODE.clone(),
                                new ExampleExternalChannelProcessor())
                        .register(
                                ADD_HANDLER_TYPE_BLUE_ID,
                                ADD_HANDLER_TYPE_NODE.clone(),
                                new AddAmountProcessor())
                        .register(
                                EMIT_HANDLER_TYPE_BLUE_ID,
                                EMIT_HANDLER_TYPE_NODE.clone(),
                                new EmitApplicationEventProcessor())
                        .register(
                                GAS_HANDLER_TYPE_BLUE_ID,
                                GAS_HANDLER_TYPE_NODE.clone(),
                                runtimeWorkProcessor)
                        .build();
        return BlueRuntime.builder()
                .nodeProvider(provider)
                .contractRuntimeRegistry(registry)
                .deliveryPlanDeriver(
                        ContractsExampleSupport::deliveryPlan)
                .build();
    }

    static BlueRuntime runtime(RuntimeWorkProcessor runtimeWorkProcessor) {
        return runtime(blueId -> null, runtimeWorkProcessor);
    }

    static Node initializedCounterRoot() {
        Node contracts = new Node()
                .properties(
                        SOURCE_CHANNEL_KEY,
                        sourceChannel(ROOT_SUBSCRIPTION_KEY))
                .properties(
                        TARGET_CHANNEL_KEY,
                        handlerChannel())
                .properties(
                        ADD_HANDLER_KEY,
                        typed(ADD_HANDLER_TYPE_BLUE_ID)
                                .properties(
                                        KEY_CHANNEL,
                                        text(TARGET_CHANNEL_KEY))
                                .properties(
                                        KEY_COUNTER_PATH,
                                        text("/" + COUNTER_KEY)));
        return initialize(new Node()
                .name("External counter")
                .properties(COUNTER_KEY, integer(0L))
                .contracts(contracts));
    }

    static Node initializedRootAndChildEmitters() {
        Node child = new Node()
                .name("Child scope")
                .contracts(emitterContracts(
                        "child", CHILD_SUBSCRIPTION_KEY));
        Node rootContracts = emitterContracts(
                "root", ROOT_SUBSCRIPTION_KEY)
                .properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                .properties(
                                        ProcessorContractConstants.KEY_PATHS,
                                        new Node().items(text(CHILD_SCOPE))));
        return initialize(new Node()
                .name("Root-only emissions")
                .properties(CHILD_KEY, child)
                .contracts(rootContracts));
    }

    static Node initializedRuntimeWorkRoot(long units) {
        Node contracts = new Node()
                .properties(
                        SOURCE_CHANNEL_KEY,
                        sourceChannel(ROOT_SUBSCRIPTION_KEY))
                .properties(
                        TARGET_CHANNEL_KEY,
                        handlerChannel())
                .properties(
                        GAS_HANDLER_KEY,
                        typed(GAS_HANDLER_TYPE_BLUE_ID)
                                .properties(
                                        KEY_CHANNEL,
                                        text(TARGET_CHANNEL_KEY))
                                .properties(
                                        KEY_UNITS,
                                        integer(units)));
        return initialize(new Node()
                .name("Runtime work")
                .contracts(contracts));
    }

    static Node amountEvent(long amount) {
        return event(ROOT_SCOPE)
                .properties(KEY_AMOUNT, integer(amount));
    }

    static Node event(String scopePath) {
        return new Node()
                .properties(
                        ProcessorContractConstants.KEY_SUBSCRIPTION_KEY,
                        text(subscriptionKey(scopePath)))
                .properties(KEY_DELIVERY_SCOPE, text(scopePath));
    }

    static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    static Node typed(String blueId) {
        return new Node().type(reference(blueId));
    }

    static Node text(String value) {
        return new Node().value(value);
    }

    static Node integer(long value) {
        return new Node().value(BigInteger.valueOf(value));
    }

    static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    static String diagnostic(DocumentProcessingResult result) {
        return result.diagnostic() == null
                ? result.status().name()
                : result.status().name()
                + "/" + result.diagnostic().category().name()
                + ": " + result.diagnostic().message()
                + " " + result.diagnostic().details();
    }

    private static Node emitterContracts(
            String label,
            String subscriptionKey) {
        return new Node()
                .properties(
                        SOURCE_CHANNEL_KEY,
                        sourceChannel(subscriptionKey))
                .properties(
                        TARGET_CHANNEL_KEY,
                        handlerChannel())
                .properties(
                        EMIT_HANDLER_KEY,
                        typed(EMIT_HANDLER_TYPE_BLUE_ID)
                                .properties(
                                        KEY_CHANNEL,
                                        text(TARGET_CHANNEL_KEY))
                                .properties(KEY_LABEL, text(label)));
    }

    private static Node sourceChannel(String subscriptionKey) {
        return typed(CHANNEL_TYPE_BLUE_ID)
                .properties(
                        KEY_EXTERNAL_SUBSCRIPTION,
                        text(subscriptionKey));
    }

    private static Node handlerChannel() {
        return sourceChannel(TARGET_SUBSCRIPTION_KEY);
    }

    private static String subscriptionKey(String scopePath) {
        return CHILD_SCOPE.equals(scopePath)
                ? CHILD_SUBSCRIPTION_KEY
                : ROOT_SUBSCRIPTION_KEY;
    }

    private static Node initialize(Node document) {
        initializeScope(document);
        return document;
    }

    private static void initializeScope(Node scope) {
        Node contracts = scope.getContracts();
        if (contracts == null) {
            contracts = new Node();
            scope.contracts(contracts);
        }
        Node embedded = property(
                contracts,
                ProcessorContractConstants.KEY_EMBEDDED);
        Node paths = property(
                embedded,
                ProcessorContractConstants.KEY_PATHS);
        if (paths != null && paths.getItems() != null) {
            for (Node pathNode : paths.getItems()) {
                if (pathNode != null
                        && pathNode.getValue() instanceof String) {
                    Node child = nodeAt(
                            scope,
                            (String) pathNode.getValue());
                    if (child != null) {
                        initializeScope(child);
                    }
                }
            }
        }
        Node initialDocument = scope.clone();
        contracts.properties(
                ProcessorContractConstants.KEY_INITIALIZED,
                typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                        .properties(
                                ProcessorContractConstants.KEY_DOCUMENT,
                                initialDocument));
    }

    private static ExternalDeliveryPlan deliveryPlan(
            Node root,
            Node event) {
        List<ScopeChannel> channels = new ArrayList<>();
        collectScopeChannels(root, ROOT_SCOPE, channels);
        String selectedScope = textProperty(
                event, KEY_DELIVERY_SCOPE, ROOT_SCOPE);
        String eventBlueId = blueId(event);
        ExternalDeliveryPlan.Builder plan = ExternalDeliveryPlan.builder()
                .revisions(1L, 1L)
                .eventOrderKey(ExternalOrderKey.of(
                        Collections.singletonList(eventBlueId)))
                .activeSubscriptionIntervals(
                        Collections.<SubscriptionDelta.Entry>emptyList())
                .exactRuntimeState();
        for (ScopeChannel channel : channels) {
            String subscriptionKey = textProperty(
                    channel.channel,
                    KEY_EXTERNAL_SUBSCRIPTION,
                    SOURCE_CHANNEL_KEY);
            String contributionBlueId = blueId(channel.channel);
            ExternalChannelDependencySnapshot dependencies =
                    channelDependencies(channel, channels);
            String checkpointDomainBlueId = CheckpointDomain.derive(
                    CHANNEL_TYPE_BLUE_ID,
                    Collections.singletonList(contributionBlueId),
                    dependencies,
                    null);
            plan.activeSubscriptionInterval(
                    new SubscriptionDelta.Entry(
                            channel.scopePath,
                            channel.channelKey,
                            CHANNEL_TYPE_BLUE_ID,
                            Collections.singletonList(
                                    contributionBlueId),
                            0,
                            Collections.singletonList(subscriptionKey),
                            checkpointDomainBlueId,
                            dependencies,
                            0L,
                            null,
                            null));
            if (channel.scopePath.equals(selectedScope)
                    && SOURCE_CHANNEL_KEY.equals(
                            channel.channelKey)) {
                plan.delivery(ExternalDeliverySnapshot
                        .builder(
                                channel.scopePath,
                                channel.channelKey)
                        .order(0)
                        .sourceContribution(contributionBlueId)
                        .effectiveTypeBlueId(CHANNEL_TYPE_BLUE_ID)
                        .subscriptionKey(subscriptionKey)
                        .checkpointDomainBlueId(
                                checkpointDomainBlueId)
                        .checkpointSubjectBlueId(eventBlueId)
                        .build());
            }
        }
        return plan.build();
    }

    private static ExternalChannelDependencySnapshot channelDependencies(
            ScopeChannel source,
            List<ScopeChannel> channels) {
        if (!SOURCE_CHANNEL_KEY.equals(source.channelKey)) {
            return ExternalChannelDependencySnapshot.none();
        }
        ScopeChannel target = findChannel(
                channels,
                source.scopePath,
                TARGET_CHANNEL_KEY);
        if (target == null) {
            throw new IllegalStateException(
                    "Missing same-scope Handler target Channel");
        }
        String contributionBlueId = blueId(target.channel);
        ExternalChannelDependencySnapshot.ChannelEntry targetHeader =
                new ExternalChannelDependencySnapshot.ChannelEntry(
                        target.channelKey,
                        0,
                        CHANNEL_TYPE_BLUE_ID,
                        EffectiveContractSnapshotConstants.Role
                                .EXTERNAL_CHANNEL,
                        Collections.singletonList(contributionBlueId),
                        Collections.<String>emptyList(),
                        contributionBlueId);
        return new ExternalChannelDependencySnapshot(
                Collections.<String>emptyList(),
                Collections.<ExternalChannelDependencySnapshot.Entry>
                        emptyList(),
                Collections.<ExternalChannelDependencySnapshot.TypeFamily>
                        emptyList(),
                false,
                Collections.singletonList(targetHeader),
                false,
                Collections.<String>emptyList());
    }

    private static ScopeChannel findChannel(
            List<ScopeChannel> channels,
            String scopePath,
            String channelKey) {
        for (ScopeChannel channel : channels) {
            if (scopePath.equals(channel.scopePath)
                    && channelKey.equals(channel.channelKey)) {
                return channel;
            }
        }
        return null;
    }

    private static void collectScopeChannels(
            Node scope,
            String scopePath,
            List<ScopeChannel> channels) {
        Node contracts = scope != null ? scope.getContracts() : null;
        collectScopeChannel(
                contracts,
                scopePath,
                SOURCE_CHANNEL_KEY,
                channels);
        collectScopeChannel(
                contracts,
                scopePath,
                TARGET_CHANNEL_KEY,
                channels);
        Node embedded = property(
                contracts,
                ProcessorContractConstants.KEY_EMBEDDED);
        Node paths = property(
                embedded,
                ProcessorContractConstants.KEY_PATHS);
        if (paths == null || paths.getItems() == null) {
            return;
        }
        for (Node pathNode : paths.getItems()) {
            if (pathNode == null
                    || !(pathNode.getValue() instanceof String)) {
                continue;
            }
            String relativePath = (String) pathNode.getValue();
            Node child = nodeAt(scope, relativePath);
            if (child != null) {
                collectScopeChannels(
                        child,
                        appendScope(scopePath, relativePath),
                        channels);
            }
        }
    }

    private static void collectScopeChannel(
            Node contracts,
            String scopePath,
            String channelKey,
            List<ScopeChannel> channels) {
        Node channel = property(contracts, channelKey);
        if (channel != null) {
            channels.add(new ScopeChannel(
                    scopePath,
                    channelKey,
                    channel));
        }
    }

    private static Node nodeAt(Node root, String pointer) {
        if (root == null || pointer == null || pointer.isEmpty()
                || ROOT_SCOPE.equals(pointer)) {
            return root;
        }
        Node current = root;
        String[] segments = pointer.substring(1).split("/", -1);
        for (String segment : segments) {
            if (current.getProperties() == null) {
                return null;
            }
            current = current.getProperties().get(
                    segment.replace("~1", "/")
                            .replace("~0", "~"));
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static String appendScope(
            String scopePath,
            String relativePath) {
        return ROOT_SCOPE.equals(scopePath)
                ? relativePath
                : scopePath + relativePath;
    }

    private static Node property(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private static String textProperty(
            Node node,
            String key,
            String defaultValue) {
        Node property = property(node, key);
        return property != null
                && property.getValue() instanceof String
                ? (String) property.getValue()
                : defaultValue;
    }

    private static Node typeNode(Class<?> type) {
        return new Node().name(type.getSimpleName());
    }

    /** Minimal custom External Channel model. */
    public static final class ExampleExternalChannel
            extends ChannelContract {
        private String subscription;

        public String getSubscription() {
            return subscription;
        }

        public void setSubscription(String subscription) {
            this.subscription = subscription;
        }
    }

    /** Handler model that adds an event amount to one Root path. */
    public static final class AddAmount extends HandlerContract {
        private String counterPath;

        public String getCounterPath() {
            return counterPath;
        }

        public void setCounterPath(String counterPath) {
            this.counterPath = counterPath;
        }
    }

    /** Handler model that emits one scope-local application event. */
    public static final class EmitApplicationEvent extends HandlerContract {
        private String label;

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    /** Handler model that declares deterministic hosted-runtime work units. */
    public static final class ChargeRuntimeWork extends HandlerContract {
        private BigInteger units;

        public BigInteger getUnits() {
            return units;
        }

        public void setUnits(BigInteger units) {
            this.units = units;
        }
    }

    /** Exact Channel implementation used by the runnable examples. */
    public static final class ExampleExternalChannelProcessor
            implements ChannelProcessor<ExampleExternalChannel> {
        private static final ExternalChannelSubscriptionFunctions<
                ExampleExternalChannel> SUBSCRIPTIONS =
                new ExternalChannelSubscriptionFunctions<
                        ExampleExternalChannel>() {
                    @Override
                    public List<String> channelKeys(
                            ExampleExternalChannel contract) {
                        return Collections.singletonList(
                                contract.getSubscription());
                    }

                    @Override
                    public List<String> channelKeys(
                            ExampleExternalChannel contract,
                            ExternalChannelFunctionContext context) {
                        if (!TARGET_CHANNEL_KEY.equals(
                                context.channelKey())) {
                            context.dependOnSameScopeChannel(
                                    TARGET_CHANNEL_KEY);
                        }
                        return channelKeys(contract);
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            ExampleExternalChannel contract) {
                        return null;
                    }

                    @Override
                    public String handlerChannelKey(
                            ExampleExternalChannel contract,
                            Node event,
                            Node payload,
                            ExternalChannelFunctionContext context) {
                        return TARGET_CHANNEL_KEY;
                    }
                };

        @Override
        public Class<ExampleExternalChannel> contractType() {
            return ExampleExternalChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                ExampleExternalChannel> externalSubscriptionFunctions() {
            return SUBSCRIPTIONS;
        }

        @Override
        public boolean matches(
                ExampleExternalChannel contract,
                ChannelEvaluationContext context) {
            return true;
        }
    }

    /** Exact Handler implementation that buffers one counter patch. */
    public static final class AddAmountProcessor
            implements HandlerProcessor<AddAmount> {
        @Override
        public Class<AddAmount> contractType() {
            return AddAmount.class;
        }

        @Override
        public void execute(
                AddAmount contract,
                ProcessorExecutionContext context) {
            String counterPath = context.resolvePointer(
                    contract.getCounterPath());
            Node currentNode = context.documentAt(counterPath);
            BigInteger current = currentNode != null
                    && currentNode.getValue() instanceof BigInteger
                    ? (BigInteger) currentNode.getValue()
                    : BigInteger.ZERO;
            Node amountNode = property(context.event(), KEY_AMOUNT);
            BigInteger amount = (BigInteger) amountNode.getValue();
            context.applyPatch(JsonPatch.replace(
                    counterPath,
                    new Node().value(current.add(amount))));
        }
    }

    /** Exact Handler implementation that buffers one application event. */
    public static final class EmitApplicationEventProcessor
            implements HandlerProcessor<EmitApplicationEvent> {
        @Override
        public Class<EmitApplicationEvent> contractType() {
            return EmitApplicationEvent.class;
        }

        @Override
        public void execute(
                EmitApplicationEvent contract,
                ProcessorExecutionContext context) {
            context.emitEvent(new Node()
                    .properties(KEY_LABEL, text(contract.getLabel()))
                    .properties(
                            KEY_ORIGIN_SCOPE,
                            text(context.scopePath())));
        }
    }

    /** Exact Handler implementation with an invocation-owned child ledger. */
    public static final class RuntimeWorkProcessor
            implements HandlerProcessor<ChargeRuntimeWork> {
        private final AtomicLong lastChildGas = new AtomicLong();

        @Override
        public Class<ChargeRuntimeWork> contractType() {
            return ChargeRuntimeWork.class;
        }

        @Override
        public void execute(
                ChargeRuntimeWork contract,
                ProcessorExecutionContext context) {
            Map<String, Long> weights = new LinkedHashMap<>();
            weights.put(RUNTIME_COUNTER, RUNTIME_COUNTER_WEIGHT);
            GasMeter.ChildGasLedger ledger =
                    context.newRuntimeGasLedger(
                            RUNTIME_NAMESPACE, weights);
            ledger.charge(
                    RUNTIME_COUNTER,
                    contract.getUnits().longValueExact());
            lastChildGas.set(ledger.totalGas());
            context.submitRuntimeGasLedger(ledger);
        }

        /** Returns the exact subtotal admitted by the latest child ledger. */
        public long lastChildGas() {
            return lastChildGas.get();
        }
    }

    private static final class ScopeChannel {
        private final String scopePath;
        private final String channelKey;
        private final Node channel;

        private ScopeChannel(
                String scopePath,
                String channelKey,
                Node channel) {
            this.scopePath = scopePath;
            this.channelKey = channelKey;
            this.channel = channel;
        }
    }
}
