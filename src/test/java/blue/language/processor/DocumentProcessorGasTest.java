package blue.language.processor;

import blue.language.model.NodePath;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.contracts.EmitEventsContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.Contract;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.codec.jackson.UncheckedObjectMapper;
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
    void shouldProduceDeterministicInitializationGasForEquivalentRoots() {
        // given
        Node document = blue.yamlToNode("name: Doc\n");

        // when
        DocumentProcessingResult first =
                blue.initializeDocument(document.clone());
        DocumentProcessingResult second =
                blue.initializeDocument(document.clone());
        Node initializedMarker =
                extractInitializedMarker(first.document());

        // then
        assertNotNull(initializedMarker);
        assertTrue(first.events().isEmpty(),
                "processor-generated initialization lifecycle is local");
        assertEquals(first.totalGas(), second.totalGas(),
                "equivalent semantic work must have identical portable gas");
        assertTrue(first.totalGas() > 0L);
    }

    @Test
    void shouldProduceDeterministicProcessPatchGasIndependentOfByteSize() {
        // given
        String yaml = "name: Base\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node initialized =
                blue.initializeDocument(blue.yamlToNode(yaml))
                        .document().clone();
        Node event =
                blue.objectToNode(new TestEvent().eventId("evt-1"));

        // when
        DocumentProcessingResult first =
                blue.processDocument(initialized.clone(), event.clone());
        DocumentProcessingResult second =
                blue.processDocument(initialized.clone(), event.clone());

        // then
        assertEquals(1, first.document().getAsInteger("/x"));
        assertTrue(first.events().isEmpty(),
                "the input event is not automatically an output");
        assertEquals(first.totalGas(), second.totalGas(),
                "equivalent PROCESS invocations must have identical portable gas");
        assertTrue(first.totalGas() > 0L);
    }

    @Test
    void shouldChargeDeterministicGasForEquivalentEmittedEventWork() {
        // given
        String yaml = "name: Emit\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  emitter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.EMIT_EVENTS + "\n" +
                "    events:\n" +
                "      - type:\n" +
                "          blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT + "\n" +
                "        kind: emitted\n" +
                "  triggered:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL + "\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document().clone();
        Node event = blue.objectToNode(new TestEvent().eventId("evt-emit"));

        // when
        DocumentProcessingResult first =
                blue.processDocument(initialized.clone(), event.clone());
        DocumentProcessingResult second =
                blue.processDocument(initialized.clone(), event.clone());

        // then
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
    void shouldReuseResolvedTypeCacheForProcessDocumentWithoutChangingGas() {
        // given
        ProcessingTypeGraph types = processingTypeGraph();
        Node initialized = initializedProcessingDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        Node coldEvent = coldBlue.objectToNode(new TestEvent().eventId("evt-cold"));
        coldProvider.reset();
        ResolvedSnapshot precomputedTypeGraph =
                ProcessorTestSupport.blue(types.provider)
                        .loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider =
                new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider)
                .cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmEvent = warmBlue.objectToNode(
                new TestEvent().eventId("evt-warm"));
        warmProvider.reset();

        // when
        DocumentProcessingResult cold = coldBlue.processDocument(initialized.clone(), coldEvent);
        int coldAccountFetches =
                coldProvider.fetchCount(types.accountId);
        int coldMoneyFetches =
                coldProvider.fetchCount(types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-cold-reused")));
        int reusedFetches = coldProvider.fetchCount();
        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        // then
        assertProcessedAccount(cold, types);
        assertTrue(coldAccountFetches > 0);
        assertTrue(coldMoneyFetches > 0);
        assertTrue(cold.totalGas() > 0L);
        assertProcessedAccount(coldReused, types);
        assertTrue(reusedFetches > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas(),
                "cache warmth must not change portable gas");
        assertProcessedAccount(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas(),
                "physical cache representation must not change portable gas");
    }

    @Test
    void shouldReuseResolvedTypeCacheWithoutChangingGasWhenInitializingDocument() {
        // given
        ProcessingTypeGraph types = processingTypeGraph();
        Node original = accountDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        ResolvedSnapshot precomputedTypeGraph =
                ProcessorTestSupport.blue(types.provider)
                        .loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider =
                new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider)
                .cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmOriginal = accountDocument(types);
        warmProvider.reset();

        // when
        DocumentProcessingResult cold = coldBlue.initializeDocument(original.clone());
        int coldAccountFetches =
                coldProvider.fetchCount(types.accountId);
        int coldMoneyFetches =
                coldProvider.fetchCount(types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.initializeDocument(original.clone());
        int reusedFetches = coldProvider.fetchCount();
        DocumentProcessingResult warm = warmBlue.initializeDocument(warmOriginal);

        // then
        assertInitializedAccount(cold, types);
        assertTrue(coldAccountFetches > 0);
        assertTrue(coldMoneyFetches > 0);
        assertInitializedAccount(coldReused, types);
        assertTrue(reusedFetches > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas());
        assertInitializedAccount(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void shouldCacheRepeatedNestedTypeReferencesDuringProcessDocumentWithoutChangingGas() {
        // given
        RepeatedTypeGraph types = repeatedTypeGraph();
        Node initialized = initializedPortfolioDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        Node coldEvent = coldBlue.objectToNode(new TestEvent().eventId("evt-repeated-cold"));
        coldProvider.reset();
        ResolvedSnapshot precomputedTypeGraph =
                ProcessorTestSupport.blue(types.provider)
                        .loadSnapshot(portfolioCanonical(types));
        CountingNodeProvider warmProvider =
                new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider)
                .cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmEvent = warmBlue.objectToNode(
                new TestEvent().eventId("evt-repeated-warm"));
        warmProvider.reset();

        // when
        DocumentProcessingResult cold = coldBlue.processDocument(initialized.clone(), coldEvent);
        int coldPortfolioFetches =
                coldProvider.fetchCount(types.portfolioId);
        int coldAccountFetches =
                coldProvider.fetchCount(types.accountId);
        int coldMoneyFetches =
                coldProvider.fetchCount(types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-repeated-reused")));
        int reusedFetches = coldProvider.fetchCount();
        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        // then
        assertProcessedPortfolio(cold, types);
        assertTrue(coldPortfolioFetches > 0);
        assertTrue(coldAccountFetches > 0);
        assertTrue(coldMoneyFetches > 0);
        assertProcessedPortfolio(coldReused, types);
        assertTrue(reusedFetches > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas());
        assertProcessedPortfolio(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void shouldShareResolvedTypeCacheAcrossEmbeddedChildScopesDuringInitializationWithoutChangingGas() {
        // given
        ProcessingTypeGraph types = processingTypeGraph();
        Node original = embeddedAccountsDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        ResolvedSnapshot precomputedTypeGraph =
                ProcessorTestSupport.blue(types.provider)
                        .loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider =
                new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider)
                .cacheResolvedSnapshot(precomputedTypeGraph);
        warmProvider.reset();

        // when
        DocumentProcessingResult cold = coldBlue.initializeDocument(original.clone());
        int coldAccountFetches =
                coldProvider.fetchCount(types.accountId);
        int coldMoneyFetches =
                coldProvider.fetchCount(types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.initializeDocument(original.clone());
        int reusedFetches = coldProvider.fetchCount();
        DocumentProcessingResult warm = warmBlue.initializeDocument(original.clone());

        // then
        assertInitializedEmbeddedAccounts(cold, types);
        assertTrue(coldAccountFetches > 0);
        assertTrue(coldMoneyFetches > 0);
        assertInitializedEmbeddedAccounts(coldReused, types);
        assertTrue(reusedFetches > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas());
        assertInitializedEmbeddedAccounts(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void shouldShareResolvedTypeCacheAcrossEmbeddedChildScopesDuringProcessingWithoutChangingGas() {
        // given
        ProcessingTypeGraph types = processingTypeGraph();
        Node initialized = initializedEmbeddedProcessingDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        Node coldEvent = coldBlue.objectToNode(new TestEvent().eventId("evt-embedded-cold"));
        coldProvider.reset();
        ResolvedSnapshot precomputedTypeGraph =
                ProcessorTestSupport.blue(types.provider)
                        .loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider =
                new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider)
                .cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmEvent = warmBlue.objectToNode(
                new TestEvent().eventId("evt-embedded-warm"));
        warmProvider.reset();

        // when
        DocumentProcessingResult cold = coldBlue.processDocument(initialized.clone(), coldEvent);
        int coldAccountFetches =
                coldProvider.fetchCount(types.accountId);
        int coldMoneyFetches =
                coldProvider.fetchCount(types.moneyId);
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-embedded-reused")));
        int reusedFetches = coldProvider.fetchCount();
        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        // then
        assertProcessedEmbeddedAccounts(cold, types);
        assertTrue(coldAccountFetches > 0);
        assertTrue(coldMoneyFetches > 0);
        assertProcessedEmbeddedAccounts(coldReused, types);
        assertTrue(reusedFetches > 0,
                "provider evidence is reverified independently of resolver cache warmth");
        assertEquals(cold.totalGas(), coldReused.totalGas());
        assertProcessedEmbeddedAccounts(warm, types);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void shouldRefreshProcessorConformanceCacheAndKeepRegisteredProcessorsWhenNodeProviderChanges() {
        // given
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

        // when
        DocumentProcessingResult initialized = blue.initializeDocument(document);
        int firstProviderInitializationFetches =
                firstProvider.fetchCount();
        int secondAccountInitializationFetches =
                secondProvider.fetchCount(
                        secondTypes.accountId);
        int secondMoneyInitializationFetches =
                secondProvider.fetchCount(
                        secondTypes.moneyId);
        secondProvider.reset();
        DocumentProcessingResult processed = blue.processDocument(initialized.document().clone(),
                blue.objectToNode(new TestEvent().eventId("evt-provider-swap")));
        int firstProviderProcessFetches =
                firstProvider.fetchCount();
        int secondProviderProcessFetches =
                secondProvider.fetchCount();

        // then
        assertFalse(isCapabilityFailure(initialized), diagnosticMessage(initialized));
        assertEquals(0, firstProviderInitializationFetches);
        assertTrue(secondAccountInitializationFetches > 0);
        assertTrue(secondMoneyInitializationFetches > 0);
        assertProcessedAccount(processed, secondTypes);
        assertEquals(0, firstProviderProcessFetches);
        assertTrue(secondProviderProcessFetches > 0,
                "PROCESS must continue to verify evidence through the replacement provider");
    }

    @Test
    void shouldReturnCanonicalExplicitlyResolvableProcessDocumentResult() {
        // given
        ProcessingTypeGraph types = processingTypeGraph();
        Node initialized = initializedProcessingDocument(types);
        CountingNodeProvider provider = new CountingNodeProvider(types.provider);
        Blue blue = processingBlue(provider);
        provider.reset();

        // when
        DocumentProcessingResult result = blue.processDocument(initialized.clone(),
                blue.objectToNode(new TestEvent().eventId("evt-snapshot")));
        ResolvedSnapshot snapshot = snapshot(blue, result);

        // then
        assertProcessedAccount(result, types);
        assertEquals(snapshot.blueId(), documentBlueId(result));
        assertEquals(DirectBlueIdCalculator.calculateUncheckedBlueId(result.document()),
                documentBlueId(result));
        assertEquals(1, result.document().getAsInteger("/balance/cents"));
        assertEquals(1, snapshot.resolvedRoot().getAsInteger("/balance/cents"));
        assertNullNode(result.document(), "/balance/currency");
        assertEquals("USD",
                snapshot.resolvedRoot().getAsText("/balance/currency"));
        assertFetched(provider, types.accountId);
        assertFetched(provider, types.moneyId);
    }

    @Test
    void shouldReturnCanonicalExplicitlyResolvableInitializationResult() {
        // given
        ProcessingTypeGraph types = processingTypeGraph();
        CountingNodeProvider provider = new CountingNodeProvider(types.provider);
        Blue blue = processingBlue(provider);

        // when
        DocumentProcessingResult result = blue.initializeDocument(accountDocument(types));
        ResolvedSnapshot snapshot = snapshot(blue, result);

        // then
        assertInitializedAccount(result, types);
        assertEquals(snapshot.blueId(), documentBlueId(result));
        assertEquals(DirectBlueIdCalculator.calculateUncheckedBlueId(result.document()),
                documentBlueId(result));
        assertEquals(0, result.document().getAsInteger("/balance/cents"));
        assertEquals(0, snapshot.resolvedRoot().getAsInteger("/balance/cents"));
        assertNullNode(result.document(), "/balance/currency");
        assertEquals("USD",
                snapshot.resolvedRoot().getAsText("/balance/currency"));
        assertFetched(provider, types.accountId);
        assertFetched(provider, types.moneyId);
    }

    @Test
    void shouldReturnCapabilityFailureInputWithoutSpendingGasOnResolution() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        String yaml = "contracts:\n" +
                "  unsupported:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    channel: missing\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        // when
        Node input = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(input);

        // then
        assertTrue(isCapabilityFailure(result));
        assertEquals(0L, result.totalGas());
        assertEquals(blue.nodeToJson(input),
                blue.nodeToJson(result.document()),
                "a noncommitting result returns the exact input document");
        assertTrue(result.events().isEmpty());
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
                initialized.status() + ": " + diagnosticMessage(initialized));
        return initialized.document().clone();
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
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
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
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        Node document = result.document();
        Node resolved = resolveResultDocument(result, types.provider);
        assertEquals(1, document.getAsInteger("/balance/cents"));
        assertEquals(typeName(types.provider, types.moneyId), resolved.getAsNode("/balance/type").getName());
        assertEquals(typeName(types.provider, types.accountId), resolved.getType().getName());
    }

    private void assertInitializedAccount(DocumentProcessingResult result, ProcessingTypeGraph types) {
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        Node document = result.document();
        Node resolved = resolveResultDocument(result, types.provider);
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
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    path: /secondary/balance\n" +
                "    propertyKey: cents\n" +
                "    propertyValue: 1\n",
                Node.class);
        DocumentProcessingResult initialized = setupBlue.initializeDocument(document);
        assertTrue(setupBlue.isInitialized(initialized.document()));
        return initialized.document().clone();
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
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        Node document = result.document();
        Node resolved = resolveResultDocument(result, types.provider);
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
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /primary\n" +
                "      - /secondary\n", Node.class);
    }

    private Node initializedEmbeddedProcessingDocument(ProcessingTypeGraph types) {
        Blue setupBlue = processingBlue(new CountingNodeProvider(types.provider));
        DocumentProcessingResult initialized =
                setupBlue.initializeDocument(embeddedAccountsProcessingDocument(types));
        assertTrue(setupBlue.isInitialized(initialized.document()));
        return initialized.document().clone();
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
                "        blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "    setter:\n" +
                "      channel: testChannel\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
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
                "        blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "    setter:\n" +
                "      channel: testChannel\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      path: /balance\n" +
                "      propertyKey: cents\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /primary\n" +
                "      - /secondary\n", Node.class);
    }

    private void assertInitializedEmbeddedAccounts(DocumentProcessingResult result, ProcessingTypeGraph types) {
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        Node document = result.document();
        Node resolved = resolveResultDocument(result, types.provider);
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
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        Node document = result.document();
        Node resolved = resolveResultDocument(result, types.provider);
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

    private Node resolveResultDocument(DocumentProcessingResult result,
                                       BasicNodeProvider provider) {
        Blue resolver = processingBlue(
                new CountingNodeProvider(provider));
        try {
            return resolvedDocument(resolver, result);
        } finally {
            resolver.close();
        }
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
            // Missing properties throw in NodePath; either form means absent.
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
            ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;
    private static final String TEST_EVENT_BLUE_ID =
            ProcessorTestTypeBlueIds.TEST_EVENT;
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
        install(blue, null);
    }

    static void install(Blue blue, long gasLimit) {
        install(blue, Long.valueOf(gasLimit));
    }

    private static void install(Blue blue, Long gasLimit) {
        final DocumentProcessor[] owner = new DocumentProcessor[1];
        owner[0] = replaceProcessor(
                blue,
                (root, event) -> derive(
                        owner[0], root, event),
                gasLimit);
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
                                                        DirectBlueIdCalculator
                                                                .calculateBlueId(
                                                                        event))))
                                .activeSubscriptionIntervals(
                                        Collections
                                                .<SubscriptionDelta.Entry>
                                                        emptyList())
                                .exactRuntimeState()
                                .build(),
                null);
    }

    private static DocumentProcessor replaceProcessor(
            Blue blue,
            ExternalDeliveryPlanDeriver deriver,
            Long gasLimit) {
        DocumentProcessor current = blue.getDocumentProcessor();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeRegistry(current.administration().contractRegistry())
                .contractTypeResolver(
                        current.administration().contractTypeResolver())
                .matchingService(
                        new ContractMatchingService(blue))
                .observer(
                        current.processingObserver())
                .gasSchedule(current.gasSchedule())
                .runtimeRegistryIdentity(
                        current.runtimeRegistryIdentity())
                .deliveryPlanDeriver(
                        deriver);
        if (gasLimit != null) {
            builder.gasLimit(gasLimit);
        }
        if (current.conformanceEngine() != null) {
            builder.conformanceEngine(
                    current.conformanceEngine());
        }
        if (current.conformancePlannerOverride() != null) {
            builder.conformancePlannerOverride(
                    current.conformancePlannerOverride());
        }
        if (current.snapshotManager() != null) {
            builder.snapshotStore(
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
                        .snapshotStore(snapshotManager)
                        .registerContractProcessor(
                                testEventChannelProcessor())
                        .deliveryPlanDeriver(
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
                DirectBlueIdCalculator.calculateBlueId(event);
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

        ProcessorInvocationState inspection =
                new ProcessorInvocationState(
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
