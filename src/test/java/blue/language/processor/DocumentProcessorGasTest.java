package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.contracts.EmitEventsContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.Contract;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.UncheckedObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorGasTest {

    private Blue blue;

    @BeforeEach
    void setUp() {
        blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new EmitEventsContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
    }

    @Test
    void initializationGasIsDeterministicForEquivalentRoots() {
        Node document = blue.yamlToNode("name: Doc\n");

        DocumentProcessingResult first =
                blue.initializeDocument(document.clone());
        DocumentProcessingResult second =
                blue.initializeDocument(document.clone());

        Node initializedMarker =
                extractInitializedMarker(first.document());
        assertNotNull(initializedMarker);
        assertTrue(first.events().isEmpty(),
                "processor-generated initialization lifecycle is local");
        assertEquals(first.totalGas(), second.totalGas(),
                "equivalent semantic work must have identical portable gas");
        assertTrue(first.totalGas() > 0L);
    }

    @Test
    void processPatchGasIsDeterministicAndNotByteSized() {
        String yaml = "name: Base\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node initialized =
                blue.initializeDocument(blue.yamlToNode(yaml))
                        .document().clone();
        Node event =
                blue.objectToNode(new TestEvent().eventId("evt-1"));

        DocumentProcessingResult first =
                blue.processDocument(initialized.clone(), event.clone());
        DocumentProcessingResult second =
                blue.processDocument(initialized.clone(), event.clone());

        assertEquals(1, first.document().getAsInteger("/x"));
        assertTrue(first.events().isEmpty(),
                "the input event is not automatically an output");
        assertEquals(first.totalGas(), second.totalGas(),
                "equivalent PROCESS invocations must have identical portable gas");
        assertTrue(first.totalGas() > 0L);
    }

    @Test
    void emittedEventGasIsDeterministicForEquivalentWork() {
        String yaml = "name: Emit\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  emitter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8L41csGU9GJkoza1159y2pYbJ6yGAi4huvgmu44Ah2d5\n" +
                "    events:\n" +
                "      - type:\n" +
                "          blueId: Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf\n" +
                "        kind: emitted\n" +
                "  triggered:\n" +
                "    type:\n" +
                "      blueId: DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document().clone();
        Node event = blue.objectToNode(new TestEvent().eventId("evt-emit"));

        DocumentProcessingResult first =
                blue.processDocument(initialized.clone(), event.clone());
        DocumentProcessingResult second =
                blue.processDocument(initialized.clone(), event.clone());

        assertNotNull(extractEmitterEventTemplate(first.document()));
        assertEquals(1, first.events().size(),
                "the explicit Root emission enters the public outbox once");
        assertEquals("emitted",
                first.events().get(0).getAsText("/kind"));
        assertEquals(first.totalGas(), second.totalGas(),
                "equivalent event emission must have identical portable gas");
        assertTrue(first.totalGas() > 0L);
    }

    @Test
    void processDocumentReusesResolvedTypeCacheWithoutChangingGas() {
        ProcessingTypeGraph types = processingTypeGraph();
        Node initialized = initializedProcessingDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        Node coldEvent = coldBlue.objectToNode(new TestEvent().eventId("evt-cold"));
        coldProvider.reset();

        DocumentProcessingResult cold = coldBlue.processDocument(initialized.clone(), coldEvent);

        assertProcessedAccount(cold, types);
        assertFetched(coldProvider, types.accountId);
        assertFetched(coldProvider, types.moneyId);
        assertTrue(cold.totalGas() > 0L);

        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-cold-reused")));

        assertProcessedAccount(coldReused, types);
        assertTrue(coldProvider.fetchCount() > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas(),
                "cache warmth must not change portable gas");

        ResolvedSnapshot precomputedTypeGraph = ProcessorTestSupport.blue(types.provider).loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmEvent = warmBlue.objectToNode(new TestEvent().eventId("evt-warm"));
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        assertProcessedAccount(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas(),
                "physical cache representation must not change portable gas");
    }

    @Test
    void initializeDocumentReusesResolvedTypeCacheWithoutChangingGas() {
        ProcessingTypeGraph types = processingTypeGraph();
        Node original = accountDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);

        DocumentProcessingResult cold = coldBlue.initializeDocument(original.clone());

        assertInitializedAccount(cold, types);
        assertFetched(coldProvider, types.accountId);
        assertFetched(coldProvider, types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.initializeDocument(original.clone());

        assertInitializedAccount(coldReused, types);
        assertTrue(coldProvider.fetchCount() > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = ProcessorTestSupport.blue(types.provider).loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmOriginal = accountDocument(types);
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.initializeDocument(warmOriginal);

        assertInitializedAccount(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void processDocumentCachesRepeatedNestedTypeReferencesWithoutChangingGas() {
        RepeatedTypeGraph types = repeatedTypeGraph();
        Node initialized = initializedPortfolioDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        Node coldEvent = coldBlue.objectToNode(new TestEvent().eventId("evt-repeated-cold"));
        coldProvider.reset();

        DocumentProcessingResult cold = coldBlue.processDocument(initialized.clone(), coldEvent);

        assertProcessedPortfolio(cold, types);
        assertFetched(coldProvider, types.portfolioId);
        assertFetched(coldProvider, types.accountId);
        assertFetched(coldProvider, types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-repeated-reused")));

        assertProcessedPortfolio(coldReused, types);
        assertTrue(coldProvider.fetchCount() > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = ProcessorTestSupport.blue(types.provider).loadSnapshot(portfolioCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmEvent = warmBlue.objectToNode(new TestEvent().eventId("evt-repeated-warm"));
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        assertProcessedPortfolio(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void embeddedInitializationSharesResolvedTypeCacheAcrossChildScopesWithoutChangingGas() {
        ProcessingTypeGraph types = processingTypeGraph();
        Node original = embeddedAccountsDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);

        DocumentProcessingResult cold = coldBlue.initializeDocument(original.clone());

        assertInitializedEmbeddedAccounts(cold, types);
        assertFetched(coldProvider, types.accountId);
        assertFetched(coldProvider, types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.initializeDocument(original.clone());

        assertInitializedEmbeddedAccounts(coldReused, types);
        assertTrue(coldProvider.fetchCount() > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = ProcessorTestSupport.blue(types.provider).loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.initializeDocument(original.clone());

        assertInitializedEmbeddedAccounts(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void embeddedProcessingSharesResolvedTypeCacheAcrossChildScopesWithoutChangingGas() {
        ProcessingTypeGraph types = processingTypeGraph();
        Node initialized = initializedEmbeddedProcessingDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        Node coldEvent = coldBlue.objectToNode(new TestEvent().eventId("evt-embedded-cold"));
        coldProvider.reset();

        DocumentProcessingResult cold = coldBlue.processDocument(initialized.clone(), coldEvent);

        assertProcessedEmbeddedAccounts(cold, types);
        assertFetched(coldProvider, types.accountId);
        assertFetched(coldProvider, types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-embedded-reused")));

        assertProcessedEmbeddedAccounts(coldReused, types);
        assertTrue(coldProvider.fetchCount() > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = ProcessorTestSupport.blue(types.provider).loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmEvent = warmBlue.objectToNode(new TestEvent().eventId("evt-embedded-warm"));
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        assertProcessedEmbeddedAccounts(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void changingNodeProviderRefreshesProcessorConformanceCacheAndKeepsRegisteredProcessors() {
        ProcessingTypeGraph firstTypes = processingTypeGraph("First");
        ProcessingTypeGraph secondTypes = processingTypeGraph("Second");
        CountingNodeProvider firstProvider = new CountingNodeProvider(firstTypes.provider);
        CountingNodeProvider secondProvider = new CountingNodeProvider(secondTypes.provider);
        Blue blue = processingBlue(firstProvider);

        blue.nodeProvider(ProcessorTestSupport.providerWithTestContractTypes(
                DocumentProcessorExactFeederSupport
                        .strictDirectContentProvider(secondProvider)));
        DocumentProcessorExactFeederSupport.install(blue);
        firstProvider.reset();
        secondProvider.reset();
        Node document = processingDocument(secondTypes);

        DocumentProcessingResult initialized = blue.initializeDocument(document);

        assertFalse(initialized.capabilityFailure(), initialized.failureReason());
        assertEquals(0, firstProvider.fetchCount());
        assertFetched(secondProvider, secondTypes.accountId);
        assertFetched(secondProvider, secondTypes.moneyId);

        secondProvider.reset();
        DocumentProcessingResult processed = blue.processDocument(initialized.canonicalDocument().clone(),
                blue.objectToNode(new TestEvent().eventId("evt-provider-swap")));

        assertProcessedAccount(processed, secondTypes);
        assertEquals(0, firstProvider.fetchCount());
        assertTrue(secondProvider.fetchCount() > 0,
                "PROCESS must continue to verify evidence through the replacement provider");
    }

    @Test
    void processDocumentResultExposesCanonicalSnapshotBlueIdAndResolvedView() {
        ProcessingTypeGraph types = processingTypeGraph();
        Node initialized = initializedProcessingDocument(types);
        CountingNodeProvider provider = new CountingNodeProvider(types.provider);
        Blue blue = processingBlue(provider);
        provider.reset();

        DocumentProcessingResult result = blue.processDocument(initialized.clone(),
                blue.objectToNode(new TestEvent().eventId("evt-snapshot")));

        assertProcessedAccount(result, types);
        assertNotNull(result.snapshot());
        assertEquals(result.snapshot().blueId(), result.blueId());
        assertEquals(BlueIdCalculator.calculateUncheckedBlueId(result.canonicalDocument()), result.blueId());
        assertEquals(1, result.canonicalDocument().getAsInteger("/balance/cents"));
        assertEquals(1, result.resolvedDocument().getAsInteger("/balance/cents"));
        assertNullNode(result.canonicalDocument(), "/balance/currency");
        assertEquals("USD", result.resolvedDocument().getAsText("/balance/currency"));
        assertFetched(provider, types.accountId);
        assertFetched(provider, types.moneyId);
    }

    @Test
    void initializeDocumentResultExposesCanonicalSnapshotBlueIdAndResolvedView() {
        ProcessingTypeGraph types = processingTypeGraph();
        CountingNodeProvider provider = new CountingNodeProvider(types.provider);
        Blue blue = processingBlue(provider);

        DocumentProcessingResult result = blue.initializeDocument(accountDocument(types));

        assertInitializedAccount(result, types);
        assertNotNull(result.snapshot());
        assertEquals(result.snapshot().blueId(), result.blueId());
        assertEquals(BlueIdCalculator.calculateUncheckedBlueId(result.canonicalDocument()), result.blueId());
        assertEquals(0, result.canonicalDocument().getAsInteger("/balance/cents"));
        assertEquals(0, result.resolvedDocument().getAsInteger("/balance/cents"));
        assertNullNode(result.canonicalDocument(), "/balance/currency");
        assertEquals("USD", result.resolvedDocument().getAsText("/balance/currency"));
        assertFetched(provider, types.accountId);
        assertFetched(provider, types.moneyId);
    }

    @Test
    void capabilityFailureResultDoesNotBuildSnapshotOrSpendGasOnResolution() {
        Blue blue = ProcessorTestSupport.blue();
        String yaml = "contracts:\n" +
                "  unsupported:\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    channel: missing\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        DocumentProcessingResult result = blue.initializeDocument(blue.yamlToNode(yaml));

        assertTrue(result.capabilityFailure());
        assertEquals(0L, result.totalGas());
        assertEquals(null, result.snapshot());
        assertEquals(null, result.blueId());
        assertEquals(null, result.canonicalDocument());
        assertEquals(null, result.resolvedDocument());
    }

    private Node extractInitializedMarker(Node document) {
        Node contractsNode = document.getContracts();
        assertNotNull(contractsNode);
        return contractsNode.getProperties().get("initialized");
    }

    private Node extractProperty(Node document, String key) {
        Map<String, Node> props = document.getProperties();
        assertNotNull(props);
        return props.get(key);
    }

    private Node extractEmitterEventTemplate(Node document) {
        Node contracts = document.getContracts();
        assertNotNull(contracts);
        Node emitter = contracts.getProperties().get("emitter");
        assertNotNull(emitter);
        Node events = emitter.getProperties().get("events");
        assertNotNull(events);
        return events.getItems().get(0);
    }

    private Blue processingBlue(NodeProvider provider) {
        Blue result = ProcessorTestSupport.blue(
                DocumentProcessorExactFeederSupport
                        .strictDirectContentProvider(provider));
        result.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        result.registerContractProcessor(new SetPropertyContractProcessor());
        result.registerContractProcessor(new EmitEventsContractProcessor());
        DocumentProcessorExactFeederSupport.install(result);
        return result;
    }

    private ProcessingTypeGraph processingTypeGraph() {
        return processingTypeGraph("");
    }

    private ProcessingTypeGraph processingTypeGraph(String prefix) {
        BasicNodeProvider provider = new BasicNodeProvider();
        String moneyName = prefix.isEmpty() ? "Money" : prefix + " Money";
        String accountName = prefix.isEmpty() ? "Account" : prefix + " Account";
        provider.addSingleDocs(
                "name: " + moneyName + "\n" +
                "currency: USD\n" +
                "cents:\n" +
                "  type: Integer");
        String moneyId = provider.getBlueIdByName(moneyName);
        provider.addSingleDocs(
                "name: " + accountName + "\n" +
                "balance:\n" +
                "  type:\n" +
                "    blueId: " + moneyId);
        String accountId = provider.getBlueIdByName(accountName);
        return new ProcessingTypeGraph(provider, moneyId, accountId);
    }

    private Node initializedProcessingDocument(ProcessingTypeGraph types) {
        Blue setupBlue = processingBlue(new CountingNodeProvider(types.provider));
        Node document = processingDocument(types);
        DocumentProcessingResult initialized = setupBlue.initializeDocument(document);
        assertTrue(setupBlue.isInitialized(initialized.document()),
                initialized.status() + ": " + initialized.failureReason());
        return initialized.canonicalDocument().clone();
    }

    private Node processingDocument(ProcessingTypeGraph types) {
        return UncheckedObjectMapper.YAML_MAPPER.readValue(
                "name: Wallet\n" +
                "type:\n" +
                "  blueId: " + types.accountId + "\n" +
                "balance:\n" +
                "  type:\n" +
                "    blueId: " + types.moneyId + "\n" +
                "  currency: USD\n" +
                "  cents: 0\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    path: /balance\n" +
                "    propertyKey: cents\n" +
                "    propertyValue: 1\n", Node.class);
    }

    private Node accountCanonical(ProcessingTypeGraph types) {
        return UncheckedObjectMapper.YAML_MAPPER.readValue(
                "name: Account\n" +
                "balance:\n" +
                "  type:\n" +
                "    blueId: " + types.moneyId, Node.class);
    }

    private Node accountDocument(ProcessingTypeGraph types) {
        return UncheckedObjectMapper.YAML_MAPPER.readValue(
                "name: Wallet\n" +
                "type:\n" +
                "  blueId: " + types.accountId + "\n" +
                "balance:\n" +
                "  type:\n" +
                "    blueId: " + types.moneyId + "\n" +
                "  currency: USD\n" +
                "  cents: 0", Node.class);
    }

    private void assertProcessedAccount(DocumentProcessingResult result, ProcessingTypeGraph types) {
        assertFalse(result.capabilityFailure(), result.failureReason());
        Node document = result.document();
        Node resolved = result.resolvedDocument();
        assertEquals(1, document.getAsInteger("/balance/cents"));
        assertEquals(typeName(types.provider, types.moneyId), resolved.getAsNode("/balance/type").getName());
        assertEquals(typeName(types.provider, types.accountId), resolved.getType().getName());
    }

    private void assertInitializedAccount(DocumentProcessingResult result, ProcessingTypeGraph types) {
        assertFalse(result.capabilityFailure(), result.failureReason());
        Node document = result.document();
        Node resolved = result.resolvedDocument();
        assertNotNull(document.getAsNode("/contracts/initialized"));
        assertEquals(0, document.getAsInteger("/balance/cents"));
        assertEquals(typeName(types.provider, types.moneyId), resolved.getAsNode("/balance/type").getName());
        assertEquals(typeName(types.provider, types.accountId), resolved.getType().getName());
    }

    private RepeatedTypeGraph repeatedTypeGraph() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Money\n" +
                "currency: USD\n" +
                "cents:\n" +
                "  type: Integer");
        String moneyId = provider.getBlueIdByName("Money");
        provider.addSingleDocs(
                "name: Account\n" +
                "balance:\n" +
                "  type:\n" +
                "    blueId: " + moneyId);
        String accountId = provider.getBlueIdByName("Account");
        provider.addSingleDocs(
                "name: Portfolio\n" +
                "primary:\n" +
                "  type:\n" +
                "    blueId: " + accountId + "\n" +
                "secondary:\n" +
                "  type:\n" +
                "    blueId: " + accountId);
        String portfolioId = provider.getBlueIdByName("Portfolio");
        return new RepeatedTypeGraph(provider, moneyId, accountId, portfolioId);
    }

    private Node initializedPortfolioDocument(RepeatedTypeGraph types) {
        Blue setupBlue = processingBlue(new CountingNodeProvider(types.provider));
        Node document = UncheckedObjectMapper.YAML_MAPPER.readValue(
                "name: Portfolio Instance\n" +
                "type:\n" +
                "  blueId: " + types.portfolioId + "\n" +
                "primary:\n" +
                "  type:\n" +
                "    blueId: " + types.accountId + "\n" +
                "  balance:\n" +
                "    type:\n" +
                "      blueId: " + types.moneyId + "\n" +
                "    currency: USD\n" +
                "    cents: 0\n" +
                "secondary:\n" +
                "  type:\n" +
                "    blueId: " + types.accountId + "\n" +
                "  balance:\n" +
                "    type:\n" +
                "      blueId: " + types.moneyId + "\n" +
                "    currency: USD\n" +
                "    cents: 0\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    path: /secondary/balance\n" +
                "    propertyKey: cents\n" +
                "    propertyValue: 1\n",
                Node.class);
        DocumentProcessingResult initialized = setupBlue.initializeDocument(document);
        assertTrue(setupBlue.isInitialized(initialized.document()));
        return initialized.canonicalDocument().clone();
    }

    private Node portfolioCanonical(RepeatedTypeGraph types) {
        return UncheckedObjectMapper.YAML_MAPPER.readValue(
                "name: Portfolio\n" +
                "primary:\n" +
                "  type:\n" +
                "    blueId: " + types.accountId + "\n" +
                "secondary:\n" +
                "  type:\n" +
                "    blueId: " + types.accountId, Node.class);
    }

    private void assertProcessedPortfolio(DocumentProcessingResult result, RepeatedTypeGraph types) {
        assertFalse(result.capabilityFailure(), result.failureReason());
        Node document = result.document();
        Node resolved = result.resolvedDocument();
        assertEquals(0, document.getAsInteger("/primary/balance/cents"));
        assertEquals(1, document.getAsInteger("/secondary/balance/cents"));
        assertEquals(typeName(types.provider, types.portfolioId), resolved.getType().getName());
        assertEquals(typeName(types.provider, types.accountId), resolved.getAsNode("/primary/type").getName());
        assertEquals(typeName(types.provider, types.accountId), resolved.getAsNode("/secondary/type").getName());
        assertEquals(typeName(types.provider, types.moneyId), resolved.getAsNode("/primary/balance/type").getName());
        assertEquals(typeName(types.provider, types.moneyId), resolved.getAsNode("/secondary/balance/type").getName());
    }

    private Node embeddedAccountsDocument(ProcessingTypeGraph types) {
        return UncheckedObjectMapper.YAML_MAPPER.readValue(
                "primary:\n" +
                "  name: Primary Wallet\n" +
                "  type:\n" +
                "    blueId: " + types.accountId + "\n" +
                "  balance:\n" +
                "    type:\n" +
                "      blueId: " + types.moneyId + "\n" +
                "    currency: USD\n" +
                "    cents: 0\n" +
                "secondary:\n" +
                "  name: Secondary Wallet\n" +
                "  type:\n" +
                "    blueId: " + types.accountId + "\n" +
                "  balance:\n" +
                "    type:\n" +
                "      blueId: " + types.moneyId + "\n" +
                "    currency: USD\n" +
                "    cents: 0\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /primary\n" +
                "      - /secondary\n", Node.class);
    }

    private Node initializedEmbeddedProcessingDocument(ProcessingTypeGraph types) {
        Blue setupBlue = processingBlue(new CountingNodeProvider(types.provider));
        DocumentProcessingResult initialized =
                setupBlue.initializeDocument(embeddedAccountsProcessingDocument(types));
        assertTrue(setupBlue.isInitialized(initialized.canonicalDocument()));
        return initialized.canonicalDocument().clone();
    }

    private Node embeddedAccountsProcessingDocument(ProcessingTypeGraph types) {
        return UncheckedObjectMapper.YAML_MAPPER.readValue(
                "primary:\n" +
                "  name: Primary Wallet\n" +
                "  type:\n" +
                "    blueId: " + types.accountId + "\n" +
                "  balance:\n" +
                "    type:\n" +
                "      blueId: " + types.moneyId + "\n" +
                "    currency: USD\n" +
                "    cents: 0\n" +
                "  contracts:\n" +
                "    testChannel:\n" +
                "      type:\n" +
                "        blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "    setter:\n" +
                "      channel: testChannel\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      path: /balance\n" +
                "      propertyKey: cents\n" +
                "      propertyValue: 1\n" +
                "secondary:\n" +
                "  name: Secondary Wallet\n" +
                "  type:\n" +
                "    blueId: " + types.accountId + "\n" +
                "  balance:\n" +
                "    type:\n" +
                "      blueId: " + types.moneyId + "\n" +
                "    currency: USD\n" +
                "    cents: 0\n" +
                "  contracts:\n" +
                "    testChannel:\n" +
                "      type:\n" +
                "        blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "    setter:\n" +
                "      channel: testChannel\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      path: /balance\n" +
                "      propertyKey: cents\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /primary\n" +
                "      - /secondary\n", Node.class);
    }

    private void assertInitializedEmbeddedAccounts(DocumentProcessingResult result, ProcessingTypeGraph types) {
        assertFalse(result.capabilityFailure(), result.failureReason());
        Node document = result.document();
        Node resolved = result.resolvedDocument();
        assertNotNull(document.getAsNode("/contracts/initialized"));
        assertInitializedEmbeddedAccount(document, resolved, "/primary", types);
        assertInitializedEmbeddedAccount(document, resolved, "/secondary", types);
    }

    private void assertInitializedEmbeddedAccount(Node document, Node resolved, String path, ProcessingTypeGraph types) {
        assertNotNull(document.getAsNode(path + "/contracts/initialized"));
        assertEquals(0, document.getAsInteger(path + "/balance/cents"));
        assertEquals(typeName(types.provider, types.accountId), resolved.getAsNode(path + "/type").getName());
        assertEquals(typeName(types.provider, types.moneyId), resolved.getAsNode(path + "/balance/type").getName());
    }

    private void assertProcessedEmbeddedAccounts(DocumentProcessingResult result, ProcessingTypeGraph types) {
        assertFalse(result.capabilityFailure(), result.failureReason());
        Node document = result.document();
        Node resolved = result.resolvedDocument();
        assertProcessedEmbeddedAccount(document, resolved, "/primary", types);
        assertProcessedEmbeddedAccount(document, resolved, "/secondary", types);
    }

    private void assertProcessedEmbeddedAccount(Node document, Node resolved, String path, ProcessingTypeGraph types) {
        assertNotNull(document.getAsNode(path + "/contracts/initialized"));
        assertEquals(1, document.getAsInteger(path + "/balance/cents"));
        assertEquals(typeName(types.provider, types.accountId), resolved.getAsNode(path + "/type").getName());
        assertEquals(typeName(types.provider, types.moneyId), resolved.getAsNode(path + "/balance/type").getName());
    }

    private String typeName(BasicNodeProvider provider, String blueId) {
        Node node = provider.fetchFirstByBlueId(blueId);
        return node != null ? node.getName() : null;
    }

    private void assertFetched(CountingNodeProvider provider, String blueId) {
        assertTrue(provider.fetchCount(blueId) > 0,
                () -> "Expected a cold provider read for " + blueId + ": "
                        + provider.fetchCountsByBlueId);
    }

    private void assertNullNode(Node document, String path) {
        try {
            assertEquals(null, document.getAsNode(path));
        } catch (IllegalArgumentException ignored) {
            // Missing properties throw in NodePathAccessor; either form means absent.
        }
    }

    private static final class ProcessingTypeGraph {
        private final BasicNodeProvider provider;
        private final String moneyId;
        private final String accountId;

        private ProcessingTypeGraph(BasicNodeProvider provider,
                                    String moneyId,
                                    String accountId) {
            this.provider = provider;
            this.moneyId = moneyId;
            this.accountId = accountId;
        }
    }

    private static final class RepeatedTypeGraph {
        private final BasicNodeProvider provider;
        private final String moneyId;
        private final String accountId;
        private final String portfolioId;

        private RepeatedTypeGraph(BasicNodeProvider provider,
                                  String moneyId,
                                  String accountId,
                                  String portfolioId) {
            this.provider = provider;
            this.moneyId = moneyId;
            this.accountId = accountId;
            this.portfolioId = portfolioId;
        }
    }

    private static final class CountingNodeProvider implements NodeProvider {
        private final NodeProvider delegate;
        private int fetchCount;
        private final Map<String, Integer> fetchCountsByBlueId = new HashMap<>();

        private CountingNodeProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            List<Node> resolved =
                    delegate.fetchByBlueId(blueId);
            if (resolved != null && !resolved.isEmpty()) {
                fetchCount++;
                fetchCountsByBlueId.merge(
                        blueId, 1, Integer::sum);
            }
            return resolved;
        }

        private int fetchCount() {
            return fetchCount;
        }

        private int fetchCount(String blueId) {
            return fetchCountsByBlueId.getOrDefault(blueId, 0);
        }

        private void reset() {
            fetchCount = 0;
            fetchCountsByBlueId.clear();
        }

    }
}

/**
 * Exact in-memory feeder used by the pre-1.0 processor regression slice.
 *
 * <p>The helper derives a complete retained subscription surface from the
 * effective contract snapshots, binds it to one exact Root/event pair, and
 * lets the production verifier independently re-resolve every occurrence.
 * It deliberately remains test-only; it is not an ambient PROCESS fallback.</p>
 */
final class DocumentProcessorExactFeederSupport {

    private static final String TEST_EVENT_CHANNEL_BLUE_ID =
            "BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L";
    private static final String TEST_EVENT_BLUE_ID =
            "Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf";
    private static final long ROOT_REVISION = 1L;

    private DocumentProcessorExactFeederSupport() {
    }

    static TestEventChannelProcessor testEventChannelProcessor() {
        return new ExactTestEventChannelProcessor();
    }

    /**
     * BasicNodeProvider identifies returned content with a transport-level
     * top-level blueId. Contracts 1.0 consumes verified direct content, whose
     * authored node must not mix that identity wrapper with sibling fields.
     */
    static NodeProvider strictDirectContentProvider(NodeProvider delegate) {
        return blueId -> {
            List<Node> fetched = delegate.fetchByBlueId(blueId);
            if (fetched == null) {
                return null;
            }
            List<Node> direct = new ArrayList<>(fetched.size());
            for (Node supplied : fetched) {
                if (supplied == null) {
                    direct.add(null);
                    continue;
                }
                Node node = supplied.clone();
                if (!node.isReferenceOnly()) {
                    node.blueId(null);
                }
                direct.add(node);
            }
            return direct;
        };
    }

    static void install(Blue blue) {
        final DocumentProcessor[] owner = new DocumentProcessor[1];
        owner[0] = replaceProcessor(
                blue,
                (root, event) -> derive(
                        owner[0], root, event));
    }

    static void installExactEmptyFeeder(Blue blue) {
        replaceProcessor(
                blue,
                (root, event) ->
                        ExternalDeliveryPlan.builder()
                                .revisions(
                                        ROOT_REVISION,
                                        ROOT_REVISION)
                                .eventOrderKey(
                                        ExternalOrderKey.of(
                                                Collections.singletonList(
                                                        BlueIdCalculator
                                                                .calculateBlueId(
                                                                        event))))
                                .activeSubscriptionIntervals(
                                        Collections
                                                .<SubscriptionDelta.Entry>
                                                        emptyList())
                                .exactRuntimeState()
                                .build());
    }

    private static DocumentProcessor replaceProcessor(
            Blue blue,
            ExternalDeliveryPlanDeriver deriver) {
        DocumentProcessor current = blue.getDocumentProcessor();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .withRegistry(current.getContractRegistry())
                .withContractTypeResolver(
                        current.getContractTypeResolver())
                .withMatchingService(
                        new ContractMatchingService(blue))
                .withProcessingMetricsSink(
                        current.processingMetricsSink())
                .withGasSchedule(current.gasSchedule())
                .withRuntimeRegistryIdentity(
                        current.runtimeRegistryIdentity())
                .withExternalDeliveryPlanDeriver(
                        deriver);
        if (current.conformanceEngine() != null) {
            builder.withConformanceEngine(
                    current.conformanceEngine());
        }
        if (current.conformancePlannerOverride() != null) {
            builder.withConformancePlannerOverride(
                    current.conformancePlannerOverride());
        }
        if (current.snapshotManager() != null) {
            builder.withSnapshotManager(
                    current.snapshotManager());
        }
        DocumentProcessor exact = builder.build();
        blue.documentProcessor(exact);
        return exact;
    }

    @SafeVarargs
    static DocumentProcessor processor(
            ProcessingSnapshotManager snapshotManager,
            ContractProcessor<? extends Contract>... processors) {
        final DocumentProcessor[] owner = new DocumentProcessor[1];
        DocumentProcessor.Builder builder =
                DocumentProcessor.builder()
                        .withSnapshotManager(snapshotManager)
                        .registerContractProcessor(
                                testEventChannelProcessor())
                        .withExternalDeliveryPlanDeriver(
                                (root, event) -> derive(
                                        owner[0], root, event));
        if (processors != null) {
            for (ContractProcessor<? extends Contract> processor
                    : processors) {
                builder.registerContractProcessor(processor);
            }
        }
        owner[0] = builder.build();
        return owner[0];
    }

    private static ExternalDeliveryPlan derive(
            DocumentProcessor owner,
            Node root,
            Node event) {
        if (owner == null) {
            throw new IllegalStateException(
                    "Exact test feeder has no processor owner");
        }
        String eventBlueId =
                BlueIdCalculator.calculateBlueId(event);
        String eventTypeBlueId = event.getType() != null
                ? event.getType().getBlueId() : null;
        ExternalOrderKey eventOrder =
                ExternalOrderKey.of(
                        Collections.singletonList(eventBlueId));
        ExternalDeliveryPlan.Builder plan =
                ExternalDeliveryPlan.builder()
                        .revisions(
                                ROOT_REVISION,
                                ROOT_REVISION)
                        .eventOrderKey(eventOrder)
                        .activeSubscriptionIntervals(
                                Collections
                                        .<SubscriptionDelta.Entry>
                                                emptyList())
                        .exactRuntimeState();

        ProcessorEngine.Execution inspection =
                new ProcessorEngine.Execution(
                        owner, root.clone());
        Deque<String> pending = new ArrayDeque<>();
        List<String> visited = new ArrayList<>();
        pending.add("/");
        while (!pending.isEmpty()) {
            String scopePath = pending.removeFirst();
            if (visited.contains(scopePath)) {
                throw new IllegalArgumentException(
                        "Repeated Process Embedded scope: "
                                + scopePath);
            }
            visited.add(scopePath);
            inspection.preflightScope(scopePath);
            ContractBundle bundle =
                    inspection.bundleForScope(scopePath);
            if (bundle == null) {
                throw new IllegalStateException(
                        "No effective contract bundle at "
                                + scopePath);
            }
            for (EffectiveContractSnapshot snapshot
                    : bundle.effectiveContractSnapshots()) {
                if (!"external-channel".equals(
                        snapshot.role())) {
                    continue;
                }
                if (!TEST_EVENT_CHANNEL_BLUE_ID.equals(
                        snapshot.effectiveTypeBlueId())) {
                    throw new IllegalArgumentException(
                            "Unexpected external test channel type: "
                                    + snapshot.effectiveTypeBlueId());
                }
                TestEventChannel channel =
                        (TestEventChannel) bundle.channel(
                                snapshot.key());
                String subscriptionKey =
                        channel.getEventType() != null
                                ? channel.getEventType()
                                : TEST_EVENT_BLUE_ID;
                List<String> subscriptionKeys =
                        Collections.singletonList(
                                subscriptionKey);
                String checkpointDomain =
                        CheckpointDomain.derive(
                                snapshot
                                        .effectiveTypeBlueId(),
                                snapshot
                                        .sourceContributionNodeBlueIds(),
                                null);
                plan.activeSubscriptionInterval(
                        new SubscriptionDelta.Entry(
                                scopePath,
                                snapshot.key(),
                                snapshot
                                        .effectiveTypeBlueId(),
                                snapshot
                                        .sourceContributionNodeBlueIds(),
                                snapshot.order(),
                                subscriptionKeys,
                                checkpointDomain,
                                ROOT_REVISION,
                                null,
                                null));
                if (!subscriptionKey.equals(
                        eventTypeBlueId)) {
                    continue;
                }
                ExternalDeliverySnapshot.Builder delivery =
                        ExternalDeliverySnapshot.builder(
                                        scopePath,
                                        snapshot.key())
                                .order(snapshot.order())
                                .effectiveTypeBlueId(
                                        snapshot
                                                .effectiveTypeBlueId())
                                .subscriptionKey(
                                        subscriptionKey)
                                .checkpointDomainBlueId(
                                        checkpointDomain)
                                .checkpointSubjectBlueId(
                                        eventBlueId);
                for (String contribution
                        : snapshot
                                .sourceContributionNodeBlueIds()) {
                    delivery.sourceContribution(
                            contribution);
                }
                plan.delivery(delivery.build());
            }
            for (String embeddedPath
                    : bundle.embeddedPaths()) {
                pending.addLast(
                        ProcessorEngine.resolvePointer(
                                scopePath,
                                embeddedPath));
            }
        }
        return plan.build();
    }

    private static final class ExactTestEventChannelProcessor
            extends TestEventChannelProcessor {

        private final ExternalChannelSubscriptionFunctions<
                TestEventChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        TestEventChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestEventChannel channel) {
                        String eventType =
                                channel.getEventType();
                        return Collections.singletonList(
                                eventType != null
                                        ? eventType
                                        : TEST_EVENT_BLUE_ID);
                    }

                    @Override
                    public List<String> eventKeys(
                            Node event) {
                        Node type = event != null
                                ? event.getType() : null;
                        String eventType = type != null
                                ? type.getBlueId() : null;
                        return eventType != null
                                ? Collections.singletonList(
                                        eventType)
                                : Collections
                                        .<String>emptyList();
                    }

                    @Override
                    public String
                    checkpointDomainDiscriminator(
                            TestEventChannel channel) {
                        return null;
                    }
                };

        @Override
        public ExternalChannelSubscriptionFunctions<
                TestEventChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }
}
