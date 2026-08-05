package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.provider.NodeProvider;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared deterministic fixtures, provider probes, and assertions for JMH. */
public final class EmbeddedCollectionBenchmarkSupport {

    static final String PARAM_SIZE_TEN = "10";
    static final String PARAM_SIZE_ONE_HUNDRED = "100";
    static final String PARAM_SIZE_ONE_THOUSAND = "1000";
    static final String PARAM_SIZE_PORTABLE_EDGE = "4096";
    static final int PORTABLE_EDGE_COLLECTION_SIZE =
            Integer.parseInt(PARAM_SIZE_PORTABLE_EDGE);
    static final int MEMBER_KEY_MINIMUM_WIDTH = 4;
    static final int SELECTED_MEMBER_DIVISOR = 2;
    static final int ROOT_SCOPE_COUNT = 1;
    static final int ROOT_AND_COLLECTION_CONTAINER_COUNT = 2;
    static final long EXPECTED_SINGLE_PROVIDER_DEMAND = 1L;
    static final long EXPECTED_SINGLE_HANDLER_EXECUTION = 1L;
    static final int EXPECTED_SINGLE_SUBSCRIPTION_CHANGE = 1;
    static final long INITIAL_PROCESSING_REVISION = 1L;
    static final int FIRST_DELIVERY_ORDER = 0;

    static final String COLLECTION_KEY = "members";
    static final String EMBEDDED_KEY = "embedded";
    static final String CHANNEL_KEY = "source";
    static final String HANDLER_KEY = "handle";
    static final String EXECUTABLE_BODY_FIELD = "script";
    static final String BODY_MEMBER_FIELD = "member";
    static final String SUBSCRIPTION_KEY =
            "collection-benchmark-event";
    static final String CHECKPOINT_DOMAIN =
            "collection-benchmark-domain";

    static final Node CHANNEL_TYPE =
            new Node().name("Collection Paths Benchmark Channel");
    static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    static final Node HANDLER_TYPE =
            new Node().name("Collection Paths Benchmark Handler");
    static final String HANDLER_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(HANDLER_TYPE);

    private EmbeddedCollectionBenchmarkSupport() {
    }

    static MemberFixture memberFixture(int size) {
        Map<String, Node> inline = new LinkedHashMap<>();
        Map<String, Node> references = new LinkedHashMap<>();
        Map<String, FrozenNode> exact = new LinkedHashMap<>();
        for (int index = size - 1; index >= 0; index--) {
            String key = memberKey(index);
            Node body = new Node().properties(
                    BODY_MEMBER_FIELD, new Node().value(key));
            String bodyBlueId = DirectBlueIdCalculator
                    .calculateBlueId(body);
            Node header = new Node().properties(
                    EXECUTABLE_BODY_FIELD,
                    new Node().blueId(bodyBlueId));
            String headerBlueId = DirectBlueIdCalculator
                    .calculateBlueId(header);
            inline.put(key, header);
            references.put(key, new Node().blueId(headerBlueId));
            exact.put(
                    headerBlueId,
                    FrozenNode.fromResolvedNode(header));
        }
        return new MemberFixture(inline, references, exact);
    }

    static Node plainScope(
            int size,
            String memberWithExternalChannel) {
        Map<String, Node> members = new LinkedHashMap<>();
        for (int index = size - 1; index >= 0; index--) {
            String key = memberKey(index);
            Node member = new Node();
            if (key.equals(memberWithExternalChannel)) {
                member.contracts(new Node().properties(
                        CHANNEL_KEY,
                        scriptedExternalChannel()));
            }
            members.put(key, member);
        }
        return scope(members);
    }

    static Node processingMember(String bodyBlueId) {
        return new Node().contracts(
                new Node()
                        .properties(
                                CHANNEL_KEY,
                                new Node()
                                        .type(reference(
                                                CHANNEL_TYPE_BLUE_ID))
                                        .properties(
                                                ProcessorContractConstants
                                                        .KEY_SUBSCRIPTION_KEY,
                                                new Node().value(
                                                        SUBSCRIPTION_KEY)))
                        .properties(
                                HANDLER_KEY,
                                new Node()
                                        .type(reference(
                                                HANDLER_TYPE_BLUE_ID))
                                        .properties(
                                                EffectiveContractSnapshotConstants
                                                        .DispatchField.CHANNEL,
                                                new Node().value(CHANNEL_KEY))
                                        .properties(
                                                EXECUTABLE_BODY_FIELD,
                                                reference(bodyBlueId))));
    }

    static Node scriptedExternalChannel() {
        return new Node()
                .type(reference(RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL))
                .properties(
                        ProcessorContractConstants.KEY_SUBSCRIPTION_KEYS,
                        new Node().items(
                                new Node().value(SUBSCRIPTION_KEY)));
    }

    static SubscriptionSurfaceValidationContext validationContext(
            Node before,
            Node after,
            Set<String> changedPaths) {
        return SubscriptionSurfaceValidationContext.builder(
                        before,
                        after,
                        new LinkedHashSet<>(changedPaths),
                        GasSchedule.contracts10())
                .build();
    }

    static Node scope(Map<String, Node> members) {
        return scope(new Node().properties(members));
    }

    static Node scope(Node collection) {
        return new Node()
                .properties(COLLECTION_KEY, collection)
                .contracts(new Node().properties(
                        EMBEDDED_KEY,
                        new Node()
                                .type(reference(
                                        RuntimeBlueIds.PROCESS_EMBEDDED))
                                .properties(
                                        ProcessorContractConstants.KEY_PATHS,
                                        new Node().items(
                                                Collections.<Node>emptyList()))
                                .properties(
                                        ProcessorContractConstants
                                                .KEY_COLLECTION_PATHS,
                                        new Node().items(
                                                new Node().value(
                                                        collectionPath())))));
    }

    static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    static Node nodeAt(Node root, String path) {
        Node current = root;
        for (String segment : JsonPointer.split(path)) {
            current = current.getProperties().get(segment);
        }
        return current;
    }

    static String collectionPath() {
        return PointerUtils.appendPointer(
                JsonPointer.ROOT,
                COLLECTION_KEY);
    }

    static String memberKey(int index) {
        String value = Integer.toString(index);
        StringBuilder result = new StringBuilder("member-");
        for (int padding = value.length();
             padding < MEMBER_KEY_MINIMUM_WIDTH;
             padding++) {
            result.append('0');
        }
        return result.append(value).toString();
    }

    static long manifestQuantity(List<GasTraceEntry> trace) {
        long quantity = 0L;
        for (GasTraceEntry entry : trace) {
            if (GasScheduleConstants.Namespace.SEMANTIC.equals(
                    entry.namespace())
                    && GasScheduleConstants.SemanticCounter
                            .NODE_MANIFEST_OPENED.equals(
                                    entry.counter())) {
                quantity += entry.quantity();
            }
        }
        return quantity;
    }

    static void requireEqualLogicalGas(
            GasObservation expected,
            GasObservation actual,
            String variant) {
        if (expected.totalGas != actual.totalGas
                || expected.traceEntries != actual.traceEntries
                || expected.manifestQuantity != actual.manifestQuantity
                || expected.rejected != actual.rejected
                || !sameRejection(
                        expected.rejection,
                        actual.rejection)
                || !sameTrace(expected.trace, actual.trace)) {
            throw new IllegalStateException(
                    variant + " changed the logical gas trace");
        }
    }

    /** Requires success below the edge and the exact bounded prefix at 4,096. */
    static void requireExpectedGasObservation(
            int size,
            GasObservation observation) {
        boolean expectedRejection =
                size == PORTABLE_EDGE_COLLECTION_SIZE;
        if (observation.rejected != expectedRejection) {
            throw new IllegalStateException(
                    "Metered projection for size " + size
                            + (expectedRejection
                            ? " did not reject at the gas boundary"
                            : " unexpectedly rejected at the gas boundary"));
        }
        if (!expectedRejection) {
            return;
        }
        long maximumGas = GasSchedule.contracts10().maxProcessGas();
        ProcessorDiagnostic diagnostic = observation.rejection.diagnostic();
        if (diagnostic.category()
                != ProcessorErrorCategory.GasLimitExceeded
                || observation.totalGas != maximumGas
                || observation.rejection.admittedGas() != maximumGas
                || observation.rejection.effectiveBudget() != maximumGas) {
            throw new IllegalStateException(
                    "Size " + size
                            + " did not produce the exact Contracts 1.0 "
                            + "gas-exhaustion prefix");
        }
    }

    /** Requires the exact production-catalog outcome for the selected size. */
    static void requireExpectedCatalogOutcome(
            int size,
            EffectiveFragmentationCatalog catalog,
            PortableLimitExceededException rejection) {
        boolean expectedRejection =
                size == PORTABLE_EDGE_COLLECTION_SIZE;
        if ((rejection != null) != expectedRejection) {
            throw new IllegalStateException(
                    "Fragmentation catalog for size " + size
                            + (expectedRejection
                            ? " did not reject at the participating-scope limit"
                            : " unexpectedly rejected at a portable limit"));
        }
        if (!expectedRejection) {
            long expectedScopes = (long) size + ROOT_SCOPE_COUNT;
            if (catalog == null
                    || catalog.effectiveContractsByScope().size()
                    != expectedScopes) {
                throw new IllegalStateException(
                        "Fragmentation catalog for size " + size
                                + " did not contain " + expectedScopes
                                + " scopes");
            }
            return;
        }
        long maximumScopes = GasSchedule.contracts10().portableLimit(
                GasScheduleConstants.PortableLimit
                        .PARTICIPATING_SCOPES_PER_EVENT);
        ProcessorDiagnostic diagnostic = rejection.diagnostic();
        if (catalog != null
                || diagnostic.category()
                != ProcessorErrorCategory.DirectNodeLimitExceeded
                || !GasScheduleConstants.PortableLimit
                        .PARTICIPATING_SCOPES_PER_EVENT.equals(
                                rejection.limitName())
                || rejection.limit() != maximumScopes
                || rejection.observed()
                != maximumScopes + ROOT_SCOPE_COUNT) {
            throw new IllegalStateException(
                    "Size " + size
                            + " did not produce the exact participating-scope "
                            + "portable-limit rejection");
        }
    }

    /** Requires exact successful processing or exact 4,096-member gas failure. */
    static boolean requireExpectedSelectedProcessingOutcome(
            int size,
            DocumentProcessingResult result,
            long executions,
            CountingNodeProvider provider) {
        boolean expectedRejection =
                size == PORTABLE_EDGE_COLLECTION_SIZE;
        if (!expectedRejection) {
            if (result.status() != ProcessorStatus.SUCCESS
                    || result.diagnostic() != null
                    || executions
                    != EXPECTED_SINGLE_HANDLER_EXECUTION
                    || provider.demands()
                    != EXPECTED_SINGLE_PROVIDER_DEMAND
                    || provider.materializations()
                    != EXPECTED_SINGLE_PROVIDER_DEMAND) {
                throw new IllegalStateException(
                        "Selected processing for size " + size
                                + " did not complete with one exact body demand "
                                + "and one handler execution");
            }
            return false;
        }
        long maximumGas = GasSchedule.contracts10().maxProcessGas();
        String expectedBudget = Long.toString(maximumGas);
        ProcessorDiagnostic diagnostic = result.diagnostic();
        if (result.status() != ProcessorStatus.GAS_LIMIT_EXCEEDED
                || diagnostic == null
                || diagnostic.category()
                != ProcessorErrorCategory.GasLimitExceeded
                || result.totalGas() != maximumGas
                || !expectedBudget.equals(diagnostic.detail(
                        ProcessorDiagnosticConstants
                                .FIELD_ADMITTED_GAS))
                || !expectedBudget.equals(diagnostic.detail(
                        ProcessorDiagnosticConstants
                                .FIELD_EFFECTIVE_BUDGET))
                || executions != 0L
                || provider.demands() != 0L
                || provider.materializations() != 0L) {
            throw new IllegalStateException(
                    "Selected processing for size " + size
                            + " did not produce the exact Contracts 1.0 "
                            + "gas-limit result before body demand");
        }
        return true;
    }

    static void requireProjectedMembers(
            EmbeddedScopePlan plan,
            long expected) {
        if (plan.concreteChildPaths().size() != expected) {
            throw new IllegalStateException(
                    "Projected " + plan.concreteChildPaths().size()
                            + " members instead of " + expected);
        }
    }

    static void requireProviderCounts(
            CountingMaterializer materializer,
            long expected,
            String variant) {
        if (materializer.demands() != expected
                || materializer.materializations() != expected) {
            throw new IllegalStateException(
                    variant + " made " + materializer.demands()
                            + " provider demands and materialized "
                            + materializer.materializations()
                            + " exact references; expected " + expected);
        }
    }

    static void requireProviderCounts(
            CountingNodeProvider provider,
            long expected,
            String variant) {
        if (provider.demands() != expected
                || provider.materializations() != expected) {
            throw new IllegalStateException(
                    variant + " made " + provider.demands()
                            + " provider demands and materialized "
                            + provider.materializations()
                            + " exact references; expected " + expected);
        }
    }

    static void requireEmptyDelta(
            SubscriptionDelta delta,
            String variant) {
        if (!delta.isEmpty()) {
            throw new IllegalStateException(
                    variant
                            + " unexpectedly changed an external subscription");
        }
    }

    private static boolean sameTrace(
            List<GasTraceEntry> left,
            List<GasTraceEntry> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            GasTraceEntry leftEntry = left.get(index);
            GasTraceEntry rightEntry = right.get(index);
            if (leftEntry.sequence() != rightEntry.sequence()
                    || leftEntry.quantity() != rightEntry.quantity()
                    || leftEntry.weight() != rightEntry.weight()
                    || leftEntry.subtotal() != rightEntry.subtotal()
                    || !equal(leftEntry.namespace(), rightEntry.namespace())
                    || !equal(leftEntry.counter(), rightEntry.counter())
                    || !equal(leftEntry.scopePath(), rightEntry.scopePath())
                    || !equal(leftEntry.contractKey(), rightEntry.contractKey())
                    || !equal(leftEntry.logicalPath(), rightEntry.logicalPath())
                    || !equal(leftEntry.reason(), rightEntry.reason())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRejection(
            GasLimitExceededException left,
            GasLimitExceededException right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.quantity() == right.quantity()
                && left.weight() == right.weight()
                && left.admittedGas() == right.admittedGas()
                && left.effectiveBudget() == right.effectiveBudget()
                && equal(left.namespace(), right.namespace())
                && equal(left.counter(), right.counter());
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    /** Exact provider boundary used by planner-only reference variants. */
    static final class CountingMaterializer
            implements EmbeddedScopePlanner.ExactReferenceMaterializer {
        private final Map<String, FrozenNode> content;
        private long demands;
        private long materializations;

        CountingMaterializer(Map<String, FrozenNode> content) {
            this.content = Collections.unmodifiableMap(
                    new LinkedHashMap<>(content));
        }

        @Override
        public FrozenNode materialize(FrozenNode reference) {
            demands++;
            FrozenNode result = content.get(
                    reference.getReferenceBlueId());
            if (result != null) {
                materializations++;
            }
            return result;
        }

        void reset() {
            demands = 0L;
            materializations = 0L;
        }

        long demands() {
            return demands;
        }

        long materializations() {
            return materializations;
        }
    }

    /** Physical provider used by the selected-handler benchmark. */
    static final class CountingNodeProvider implements NodeProvider {
        private final Map<String, Node> bodies;
        private final Map<String, Long> demandCounts = new LinkedHashMap<>();
        private long demands;
        private long materializations;

        CountingNodeProvider(Map<String, Node> bodies) {
            this.bodies = Collections.unmodifiableMap(
                    new LinkedHashMap<>(bodies));
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            demands++;
            demandCounts.put(
                    blueId,
                    demandCounts.containsKey(blueId)
                            ? demandCounts.get(blueId) + 1L
                            : 1L);
            Node body = bodies.get(blueId);
            if (body == null) {
                return null;
            }
            materializations++;
            return Collections.singletonList(body.clone());
        }

        void reset() {
            demandCounts.clear();
            demands = 0L;
            materializations = 0L;
        }

        long demands() {
            return demands;
        }

        long materializations() {
            return materializations;
        }

        long unselectedBodyDemands(String selectedBodyBlueId) {
            long result = 0L;
            for (Map.Entry<String, Long> entry : demandCounts.entrySet()) {
                if (bodies.containsKey(entry.getKey())
                        && !entry.getKey().equals(selectedBodyBlueId)) {
                    result += entry.getValue();
                }
            }
            return result;
        }
    }

    /** Minimal external channel model for end-to-end processing. */
    public static final class BenchmarkChannel extends ChannelContract {
        private String subscriptionKey;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }
    }

    /** Minimal handler model with one provider-backed executable field. */
    public static final class BenchmarkHandler extends HandlerContract {
        private Node script;

        public Node getScript() {
            return script;
        }

        public void setScript(Node script) {
            this.script = script;
        }
    }

    /** External channel semantics used only by the benchmark fixture. */
    static final class BenchmarkChannelProcessor
            implements ChannelProcessor<BenchmarkChannel> {
        private final ExternalChannelSubscriptionFunctions<BenchmarkChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<BenchmarkChannel>() {
                    @Override
                    public List<String> channelKeys(
                            BenchmarkChannel channel) {
                        return Collections.singletonList(
                                channel.getSubscriptionKey());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            BenchmarkChannel channel) {
                        return CHECKPOINT_DOMAIN;
                    }
                };

        @Override
        public Class<BenchmarkChannel> contractType() {
            return BenchmarkChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<BenchmarkChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    /** No-effect selected handler that proves its body was materialized. */
    static final class BenchmarkHandlerProcessor
            implements HandlerProcessor<BenchmarkHandler> {
        private final EmbeddedCollectionSelectedProcessingState state;

        BenchmarkHandlerProcessor(
                EmbeddedCollectionSelectedProcessingState state) {
            this.state = state;
        }

        @Override
        public Class<BenchmarkHandler> contractType() {
            return BenchmarkHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList(EXECUTABLE_BODY_FIELD);
        }

        @Override
        public String deriveChannel(
                BenchmarkHandler handler,
                HandlerRegistrationContext context) {
            return CHANNEL_KEY;
        }

        @Override
        public void execute(
                BenchmarkHandler handler,
                ProcessorExecutionContext context) {
            if (handler.getScript() == null
                    || handler.getScript().isReferenceOnly()) {
                throw new IllegalStateException(
                        "Selected executable body was not materialized");
            }
            state.executions++;
        }
    }

    /** Precomputed exact logical-gas observation for one fixture form. */
    static final class GasObservation {
        final long totalGas;
        final long traceEntries;
        final long manifestQuantity;
        final boolean rejected;
        final GasLimitExceededException rejection;
        final List<GasTraceEntry> trace;

        GasObservation(
                long totalGas,
                List<GasTraceEntry> trace,
                GasLimitExceededException rejection) {
            this.totalGas = totalGas;
            this.trace = Collections.unmodifiableList(
                    new ArrayList<>(trace));
            this.traceEntries = this.trace.size();
            this.manifestQuantity = manifestQuantity(this.trace);
            this.rejection = rejection;
            this.rejected = rejection != null;
        }
    }

    /** Inline/reference views over one exact member-header set. */
    static final class MemberFixture {
        final Map<String, Node> inlineMembers;
        final Map<String, Node> referenceMembers;
        final Map<String, FrozenNode> exactMemberHeaders;

        MemberFixture(
                Map<String, Node> inlineMembers,
                Map<String, Node> referenceMembers,
                Map<String, FrozenNode> exactMemberHeaders) {
            this.inlineMembers = inlineMembers;
            this.referenceMembers = referenceMembers;
            this.exactMemberHeaders = exactMemberHeaders;
        }
    }
}
