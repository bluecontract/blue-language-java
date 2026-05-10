package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.contracts.EmitEventsContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.TestEvent;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
        blue = new Blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new EmitEventsContractProcessor());
    }

    @Test
    void initializationGasMatchesExpectedCharges() {
        Node document = blue.yamlToNode("name: Doc\n");

        DocumentProcessingResult result = blue.initializeDocument(document.clone());

        Node initializedMarker = extractInitializedMarker(result.document());
        long markerSizeCharge = sizeCharge(initializedMarker);

        long expected = scopeEntryCharge("/")
                + 1_000L // initialization
                + 30L    // lifecycle delivery
                + 2L     // boundary check
                + (20L + markerSizeCharge) // patch add
                + 10L;   // cascade routing for root

        assertEquals(expected, result.totalGas());
    }

    @Test
    void processDocumentPatchGasMatchesExpectedCharges() {
        String yaml = "name: Base\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document().clone();
        Node event = blue.objectToNode(new TestEvent().eventId("evt-1"));

        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node valueNode = extractProperty(result.document(), "x");
        long valueSizeCharge = sizeCharge(valueNode);

        long expected = scopeEntryCharge("/")
                + 5L    // channel match attempt
                + 50L   // handler overhead
                + 2L    // boundary check
                + (20L + valueSizeCharge) // add/replace patch
                + 10L   // cascade routing (root only)
                + 20L;  // checkpoint update direct write

        assertEquals(expected, result.totalGas());
    }

    @Test
    void processDocumentEmitsTriggeredEventChargesEmitAndDrain() {
        String yaml = "name: Emit\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  emitter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: EmitEvents\n" +
                "    events:\n" +
                "      - type:\n" +
                "          blueId: TestEvent\n" +
                "        kind: emitted\n" +
                "  triggered:\n" +
                "    type:\n" +
                "      blueId: TriggeredEventChannel\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document().clone();
        Node event = blue.objectToNode(new TestEvent().eventId("evt-emit"));

        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node emittedTemplate = extractEmitterEventTemplate(result.document());
        long emittedSizeCharge = sizeCharge(emittedTemplate);

        long expected = scopeEntryCharge("/")
                + 5L    // external channel match
                + 50L   // handler overhead
                + (20L + emittedSizeCharge) // emit event
                + 10L   // drain triggered FIFO
                + 20L;  // checkpoint update after successful channel

        assertEquals(expected, result.totalGas());
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
        assertEquals(1, coldProvider.fetchCount(types.accountId));
        assertEquals(1, coldProvider.fetchCount(types.moneyId));
        assertTrue(coldBlue.resolvedReferenceCacheSize() >= 2);

        int coldCacheSizeAfterFirstRun = coldBlue.resolvedReferenceCacheSize();
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-cold-reused")));

        assertProcessedAccount(coldReused, types);
        assertEquals(0, coldProvider.fetchCount());
        assertTrue(coldBlue.resolvedReferenceCacheSize() >= coldCacheSizeAfterFirstRun);
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = new Blue(types.provider).loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        int warmCacheSizeBeforeProcessing = warmBlue.resolvedReferenceCacheSize();
        Node warmEvent = warmBlue.objectToNode(new TestEvent().eventId("evt-warm"));
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        assertProcessedAccount(warm, types);
        assertEquals(0, warmProvider.fetchCount());
        assertTrue(warmBlue.resolvedReferenceCacheSize() >= warmCacheSizeBeforeProcessing);
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void initializeDocumentReusesResolvedTypeCacheWithoutChangingGas() {
        ProcessingTypeGraph types = processingTypeGraph();
        Node original = accountDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);

        DocumentProcessingResult cold = coldBlue.initializeDocument(original.clone());

        assertInitializedAccount(cold, types);
        assertEquals(1, coldProvider.fetchCount(types.accountId));
        assertEquals(1, coldProvider.fetchCount(types.moneyId));
        assertTrue(coldBlue.resolvedReferenceCacheSize() >= 2);

        int coldCacheSizeAfterFirstRun = coldBlue.resolvedReferenceCacheSize();
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.initializeDocument(original.clone());

        assertInitializedAccount(coldReused, types);
        assertEquals(0, coldProvider.fetchCount());
        assertEquals(coldCacheSizeAfterFirstRun, coldBlue.resolvedReferenceCacheSize());
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = new Blue(types.provider).loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmOriginal = accountDocument(types);
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.initializeDocument(warmOriginal);

        assertInitializedAccount(warm, types);
        assertEquals(0, warmProvider.fetchCount());
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void processDocumentCachesRepeatedNestedTypeReferencesOnlyOnceWithoutChangingGas() {
        RepeatedTypeGraph types = repeatedTypeGraph();
        Node initialized = initializedPortfolioDocument(types);

        CountingNodeProvider coldProvider = new CountingNodeProvider(types.provider);
        Blue coldBlue = processingBlue(coldProvider);
        Node coldEvent = coldBlue.objectToNode(new TestEvent().eventId("evt-repeated-cold"));
        coldProvider.reset();

        DocumentProcessingResult cold = coldBlue.processDocument(initialized.clone(), coldEvent);

        assertProcessedPortfolio(cold, types);
        assertEquals(1, coldProvider.fetchCount(types.portfolioId));
        assertEquals(1, coldProvider.fetchCount(types.accountId));
        assertEquals(1, coldProvider.fetchCount(types.moneyId));
        assertTrue(coldBlue.resolvedReferenceCacheSize() >= 3);

        int coldCacheSizeAfterFirstRun = coldBlue.resolvedReferenceCacheSize();
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-repeated-reused")));

        assertProcessedPortfolio(coldReused, types);
        assertEquals(0, coldProvider.fetchCount());
        assertTrue(coldBlue.resolvedReferenceCacheSize() >= coldCacheSizeAfterFirstRun);
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = new Blue(types.provider).loadSnapshot(portfolioCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmEvent = warmBlue.objectToNode(new TestEvent().eventId("evt-repeated-warm"));
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        assertProcessedPortfolio(warm, types);
        assertEquals(0, warmProvider.fetchCount());
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
        assertEquals(1, coldProvider.fetchCount(types.accountId));
        assertEquals(1, coldProvider.fetchCount(types.moneyId));
        assertTrue(coldBlue.resolvedReferenceCacheSize() >= 2);

        int coldCacheSizeAfterFirstRun = coldBlue.resolvedReferenceCacheSize();
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.initializeDocument(original.clone());

        assertInitializedEmbeddedAccounts(coldReused, types);
        assertEquals(0, coldProvider.fetchCount());
        assertEquals(coldCacheSizeAfterFirstRun, coldBlue.resolvedReferenceCacheSize());
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = new Blue(types.provider).loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.initializeDocument(original.clone());

        assertInitializedEmbeddedAccounts(warm, types);
        assertEquals(0, warmProvider.fetchCount());
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
        assertEquals(1, coldProvider.fetchCount(types.accountId));
        assertEquals(1, coldProvider.fetchCount(types.moneyId));
        assertTrue(coldBlue.resolvedReferenceCacheSize() >= 2);

        int coldCacheSizeAfterFirstRun = coldBlue.resolvedReferenceCacheSize();
        coldProvider.reset();
        DocumentProcessingResult coldReused = coldBlue.processDocument(initialized.clone(),
                coldBlue.objectToNode(new TestEvent().eventId("evt-embedded-reused")));

        assertProcessedEmbeddedAccounts(coldReused, types);
        assertEquals(0, coldProvider.fetchCount());
        assertTrue(coldBlue.resolvedReferenceCacheSize() >= coldCacheSizeAfterFirstRun);
        assertEquals(cold.totalGas(), coldReused.totalGas());

        ResolvedSnapshot precomputedTypeGraph = new Blue(types.provider).loadSnapshot(accountCanonical(types));
        CountingNodeProvider warmProvider = new CountingNodeProvider(types.provider);
        Blue warmBlue = processingBlue(warmProvider).cacheResolvedSnapshot(precomputedTypeGraph);
        Node warmEvent = warmBlue.objectToNode(new TestEvent().eventId("evt-embedded-warm"));
        warmProvider.reset();

        DocumentProcessingResult warm = warmBlue.processDocument(initialized.clone(), warmEvent);

        assertProcessedEmbeddedAccounts(warm, types);
        assertEquals(0, warmProvider.fetchCount());
        assertEquals(cold.totalGas(), warm.totalGas());
    }

    @Test
    void changingNodeProviderRefreshesProcessorConformanceCacheAndKeepsRegisteredProcessors() {
        ProcessingTypeGraph firstTypes = processingTypeGraph("First");
        ProcessingTypeGraph secondTypes = processingTypeGraph("Second");
        CountingNodeProvider firstProvider = new CountingNodeProvider(firstTypes.provider);
        CountingNodeProvider secondProvider = new CountingNodeProvider(secondTypes.provider);
        Blue blue = processingBlue(firstProvider);

        blue.nodeProvider(secondProvider);
        firstProvider.reset();
        secondProvider.reset();
        Node document = processingDocument(secondTypes);

        DocumentProcessingResult initialized = blue.initializeDocument(document);

        assertFalse(initialized.capabilityFailure(), initialized.failureReason());
        assertEquals(0, firstProvider.fetchCount());
        assertEquals(1, secondProvider.fetchCount(secondTypes.accountId));
        assertEquals(1, secondProvider.fetchCount(secondTypes.moneyId));

        secondProvider.reset();
        DocumentProcessingResult processed = blue.processDocument(initialized.document().clone(),
                blue.objectToNode(new TestEvent().eventId("evt-provider-swap")));

        assertProcessedAccount(processed, secondTypes);
        assertEquals(0, firstProvider.fetchCount());
        assertEquals(0, secondProvider.fetchCount());
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
        assertEquals(BlueIdCalculator.calculateBlueId(result.canonicalDocument()), result.blueId());
        assertEquals(1, result.canonicalDocument().getAsInteger("/balance/cents"));
        assertEquals(1, result.resolvedDocument().getAsInteger("/balance/cents"));
        assertNullNode(result.canonicalDocument(), "/balance/currency");
        assertEquals("USD", result.resolvedDocument().getAsText("/balance/currency"));
        assertEquals(1, provider.fetchCount(types.accountId));
        assertEquals(1, provider.fetchCount(types.moneyId));
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
        assertEquals(BlueIdCalculator.calculateBlueId(result.canonicalDocument()), result.blueId());
        assertEquals(0, result.canonicalDocument().getAsInteger("/balance/cents"));
        assertEquals(0, result.resolvedDocument().getAsInteger("/balance/cents"));
        assertNullNode(result.canonicalDocument(), "/balance/currency");
        assertEquals("USD", result.resolvedDocument().getAsText("/balance/currency"));
        assertEquals(1, provider.fetchCount(types.accountId));
        assertEquals(1, provider.fetchCount(types.moneyId));
    }

    @Test
    void capabilityFailureResultDoesNotBuildSnapshotOrSpendGasOnResolution() {
        Blue blue = new Blue();
        String yaml = "contracts:\n" +
                "  unsupported:\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
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
        Map<String, Node> contracts = document.getProperties();
        assertNotNull(contracts);
        Node contractsNode = contracts.get("contracts");
        assertNotNull(contractsNode);
        return contractsNode.getProperties().get("initialized");
    }

    private Node extractProperty(Node document, String key) {
        Map<String, Node> props = document.getProperties();
        assertNotNull(props);
        return props.get(key);
    }

    private Node extractEmitterEventTemplate(Node document) {
        Node contracts = document.getProperties().get("contracts");
        assertNotNull(contracts);
        Node emitter = contracts.getProperties().get("emitter");
        assertNotNull(emitter);
        Node events = emitter.getProperties().get("events");
        assertNotNull(events);
        return events.getItems().get(0);
    }

    private long scopeEntryCharge(String scopePath) {
        int depth = scopeDepth(scopePath);
        return 50L + 10L * depth;
    }

    private int scopeDepth(String scopePath) {
        if (scopePath == null || scopePath.isEmpty() || "/".equals(scopePath)) {
            return 0;
        }
        String trimmed = scopePath;
        if (trimmed.charAt(0) == '/') {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return 0;
        }
        int depth = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private long sizeCharge(Node node) {
        long bytes = canonicalSize(node);
        return (bytes + 99L) / 100L;
    }

    private long canonicalSize(Node node) {
        Object canonical = NodeToMapListOrValue.get(node);
        try {
            String json = UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(canonical);
            String canonicalJson = new JsonCanonicalizer(json).getEncodedString();
            return canonicalJson.getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize node", ex);
        }
    }

    private Blue processingBlue(NodeProvider provider) {
        Blue result = new Blue(provider);
        result.registerContractProcessor(new TestEventChannelProcessor());
        result.registerContractProcessor(new SetPropertyContractProcessor());
        result.registerContractProcessor(new EmitEventsContractProcessor());
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
        Node document = setupBlue.preprocess(processingDocument(types));
        DocumentProcessingResult initialized = setupBlue.initializeDocument(document);
        assertTrue(setupBlue.isInitialized(initialized.document()));
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
                "      blueId: TestEventChannel\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
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
        Node document = setupBlue.yamlToNode(
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
                "      blueId: TestEventChannel\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    path: /secondary/balance\n" +
                "    propertyKey: cents\n" +
                "    propertyValue: 1\n");
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
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /primary\n" +
                "      - /secondary\n", Node.class);
    }

    private Node initializedEmbeddedProcessingDocument(ProcessingTypeGraph types) {
        Blue setupBlue = processingBlue(new CountingNodeProvider(types.provider));
        Node initialized = setupBlue.initializeDocument(embeddedAccountsProcessingDocument(types)).document();
        assertTrue(setupBlue.isInitialized(initialized));
        return new Blue(types.provider).reverse(initialized);
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
                "        blueId: TestEventChannel\n" +
                "    setter:\n" +
                "      channel: testChannel\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
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
                "        blueId: TestEventChannel\n" +
                "    setter:\n" +
                "      channel: testChannel\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      path: /balance\n" +
                "      propertyKey: cents\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
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
            if (isProcessorTypeStub(blueId)) {
                return Collections.singletonList(new Node().name(blueId));
            }
            fetchCount++;
            fetchCountsByBlueId.merge(blueId, 1, Integer::sum);
            return delegate.fetchByBlueId(blueId);
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

        private boolean isProcessorTypeStub(String blueId) {
            return "InitializationMarker".equals(blueId)
                    || "ChannelEventCheckpoint".equals(blueId)
                    || "TestEventChannel".equals(blueId)
                    || "SetProperty".equals(blueId)
                    || "ProcessEmbedded".equals(blueId);
        }
    }
}
