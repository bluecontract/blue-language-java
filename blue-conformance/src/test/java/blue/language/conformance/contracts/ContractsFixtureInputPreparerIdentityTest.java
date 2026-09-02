package blue.language.conformance.contracts;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins fixture evidence to the same canonical contract Source semantics. */
final class ContractsFixtureInputPreparerIdentityTest {

    private static final String FEED_FIXTURE =
            "blue-contracts-closure-1.0/fixtures/feed/c-feed-02.yaml";
    private static final String NESTED_EXECUTABLE_FIXTURE =
            "blue-contracts-closure-1.0/fixtures/evo/c-evo-03.yaml";

    @Test
    void shouldBindNestedExecutableIdentityForNodeAndSnapshotEntryPoints() {
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        JsonNode input = ContractsFixtureHarnessDataSupport
                .readYaml(NESTED_EXECUTABLE_FIXTURE)
                .path("input");
        ContractsFixtureHarnessDataSupport.PreparedInput nodeInput =
                harness.prepare(input, null, null, true, false);
        ObjectNode eagerVariant = UncheckedObjectMapper.JSON_MAPPER
                .createObjectNode();
        eagerVariant.put("rootForm", "eager");
        ContractsFixtureHarnessDataSupport.PreparedInput snapshotInput =
                harness.prepare(
                        input, eagerVariant, null, true, false);

        ContractsFixtureHarnessDataSupport.ProcessExecution nodeExecution =
                harness.runProcess(nodeInput);
        ContractsFixtureHarnessDataSupport.ProcessExecution snapshotExecution =
                harness.runProcess(snapshotInput);

        assertEquals(
                nodeInput.evidence.rootBlueId(),
                snapshotInput.evidence.rootBlueId());
        assertEquals(ProcessorStatus.SUCCESS, nodeExecution.result.status());
        assertEquals(
                ProcessorStatus.SUCCESS,
                snapshotExecution.result.status(),
                () -> snapshotExecution.result.diagnostic() == null
                        ? "no diagnostic"
                        : snapshotExecution.result.diagnostic().category()
                                + ": "
                                + snapshotExecution.result.diagnostic().message()
                                + " "
                                + snapshotExecution.result.diagnostic().details());
    }

    @Test
    void shouldPrepareEquivalentProviderBackedAndInlineChannelEvidence() {
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        ObjectNode referencedInput = fixtureInputWithExactDefinition();
        ObjectNode inlineTypeInput = referencedInput.deepCopy();
        ObjectNode inlineTypeContract = (ObjectNode) inlineTypeInput
                .path("root")
                .path("contracts")
                .path("in");
        inlineTypeContract.set(
                "type",
                ContractsFixtureFeederEnvironment.compactFixtureObject(
                        harness.registry.require(
                                MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL)));
        ObjectNode inlineDefinitionInput = referencedInput.deepCopy();
        ObjectNode inlineDefinitionContract = (ObjectNode) inlineDefinitionInput
                .path("root")
                .path("contracts")
                .path("in");
        inlineDefinitionContract.set(
                "definition",
                ContractsFixtureFeederEnvironment.compactFixtureObject(
                        exactDefinition()));

        ContractsFixtureHarnessDataSupport.PreparedInput referenced =
                harness.prepare(
                        referencedInput, null, null, true, false);
        ContractsFixtureHarnessDataSupport.PreparedInput inlineType =
                harness.prepare(
                        inlineTypeInput, null, null, true, false);
        ContractsFixtureHarnessDataSupport.PreparedInput inlineDefinition =
                harness.prepare(
                        inlineDefinitionInput, null, null, true, false);

        assertEquals(1, referenced.derivedDeliveries.size());
        assertEquivalentChannelEvidence(referenced, inlineType);
        assertEquivalentChannelEvidence(referenced, inlineDefinition);
    }

    private static void assertEquivalentChannelEvidence(
            ContractsFixtureHarnessDataSupport.PreparedInput expected,
            ContractsFixtureHarnessDataSupport.PreparedInput actual) {
        assertEquals(
                expected.evidence.rootBlueId(),
                actual.evidence.rootBlueId());
        assertEquals(1, actual.derivedDeliveries.size());
        ExternalDeliverySnapshot referencedDelivery =
                expected.derivedDeliveries.get(0).snapshot;
        ExternalDeliverySnapshot inlineDelivery =
                actual.derivedDeliveries.get(0).snapshot;
        assertEquals(
                MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                inlineDelivery.effectiveTypeBlueId());
        assertEquals(
                referencedDelivery.sourceContributionNodeBlueIds(),
                inlineDelivery.sourceContributionNodeBlueIds());
        assertEquals(
                referencedDelivery.checkpointDomainBlueId(),
                inlineDelivery.checkpointDomainBlueId());
    }

    @Test
    void shouldCatalogEveryRuntimeOwnedExactChannelField() {
        Map<String, List<String>> fields =
                ContractsFixtureHarnessDataSupport.RegistryEnvironment
                        .load()
                        .exactSourceFieldsByType();

        assertTrue(fields.get(RuntimeBlueIds.CHANNEL)
                .contains("definition"));
        assertTrue(fields.get(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                .contains("definition"));
        assertTrue(fields.get(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)
                .contains("definition"));
        assertTrue(fields.get(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
                .containsAll(java.util.Arrays.asList(
                        "definition", "event")));
        assertTrue(fields.get(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                .containsAll(java.util.Arrays.asList(
                        "definition", "event")));
        assertTrue(fields.get(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                .contains("document"));
    }

    @Test
    void shouldCanonicalizeInlineCheckpointSubjectBeforeDerivingEvidence() {
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        ObjectNode input = fixtureInputWithExactDefinition();
        Node subjectType = new Node()
                .properties("kind", new Node().value("checkpoint-subject"));
        String subjectTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(subjectType);
        putProviderNode(input, subjectTypeBlueId, subjectType);
        Node inlineSubject = new Node()
                .type(subjectType.clone())
                .properties("payload", new Node().value("same"));
        Node referencedTypeSubject = new Node()
                .type(new Node().blueId(subjectTypeBlueId))
                .properties("payload", new Node().value("same"));

        ObjectNode inlineVariant = UncheckedObjectMapper.JSON_MAPPER
                .createObjectNode();
        inlineVariant.set(
                "checkpointSubject",
                ContractsFixtureFeederEnvironment.compactFixtureObject(
                        inlineSubject));
        ObjectNode referencedVariant = UncheckedObjectMapper.JSON_MAPPER
                .createObjectNode();
        referencedVariant.set(
                "checkpointSubject",
                ContractsFixtureFeederEnvironment.compactFixtureObject(
                        referencedTypeSubject));

        ContractsFixtureHarnessDataSupport.PreparedInput inline =
                harness.prepare(input, inlineVariant, null, true, false);
        ContractsFixtureHarnessDataSupport.PreparedInput referenced =
                harness.prepare(input, referencedVariant, null, true, false);

        ContractsFixtureHarnessDataSupport.DerivedDelivery inlineDelivery =
                inline.derivedDeliveries.get(0);
        ContractsFixtureHarnessDataSupport.DerivedDelivery referencedDelivery =
                referenced.derivedDeliveries.get(0);
        assertEquals(
                referencedDelivery.snapshot.checkpointSubjectBlueId(),
                inlineDelivery.snapshot.checkpointSubjectBlueId());
        assertEquals(
                inlineDelivery.snapshot.checkpointSubjectBlueId(),
                DirectBlueIdCalculator.calculateBlueId(
                        inlineDelivery.checkpointSubjectNode));
    }

    @Test
    void shouldRetainPureCheckpointReferenceWithoutPublishingItAsContent() {
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        ObjectNode input = fixtureInputWithExactDefinition();
        Node exactSubject = new Node()
                .properties("payload", new Node().value("stored-exactly"));
        String subjectBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactSubject);
        putProviderNode(input, subjectBlueId, exactSubject);
        ObjectNode variant = UncheckedObjectMapper.JSON_MAPPER
                .createObjectNode();
        variant.putObject("checkpointSubject")
                .put("blueId", subjectBlueId);

        ContractsFixtureHarnessDataSupport.PreparedInput prepared =
                harness.prepare(input, variant, null, true, false);

        ContractsFixtureHarnessDataSupport.DerivedDelivery delivery =
                prepared.derivedDeliveries.get(0);
        assertEquals(
                subjectBlueId,
                delivery.snapshot.checkpointSubjectBlueId());
        assertTrue(delivery.checkpointSubjectNode.isReferenceOnly());
        Node retainedProviderContent = prepared.providerNodes.get(subjectBlueId);
        assertFalse(retainedProviderContent.isReferenceOnly());
        assertEquals(
                subjectBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        retainedProviderContent));
    }

    @Test
    void shouldUseCanonicalRootIdentityInPreinitializedMarker() {
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        ObjectNode referencedInput = fixtureInputWithExactDefinition();
        Node rootType = new Node().name("Fixture Root Type");
        String rootTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(rootType);
        putProviderNode(referencedInput, rootTypeBlueId, rootType);
        ((ObjectNode) referencedInput.path("root"))
                .putObject("type")
                .put("blueId", rootTypeBlueId);
        ObjectNode inlineInput = referencedInput.deepCopy();
        ((ObjectNode) inlineInput.path("root")).set(
                "type",
                ContractsFixtureFeederEnvironment.compactFixtureObject(
                        rootType));

        ContractsFixtureHarnessDataSupport.PreparedInput referenced =
                harness.prepare(
                        referencedInput, null, null, true, true);
        ContractsFixtureHarnessDataSupport.PreparedInput inline =
                harness.prepare(
                        inlineInput, null, null, true, true);

        assertEquals(
                referenced.rootJson.path("contracts")
                        .path("initialized")
                        .path("document")
                        .path("blueId")
                        .asText(),
                inline.rootJson.path("contracts")
                        .path("initialized")
                        .path("document")
                        .path("blueId")
                        .asText());
    }

    private static ObjectNode fixtureInputWithExactDefinition() {
        ObjectNode input = (ObjectNode) ContractsFixtureHarnessDataSupport
                .readYaml(FEED_FIXTURE)
                .path("input")
                .deepCopy();
        Node exactDefinition = exactDefinition();
        String definitionBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactDefinition);
        ObjectNode channel = (ObjectNode) input
                .path("root")
                .path("contracts")
                .path("in");
        channel.put("dependencyMode", "catalog");
        channel.putObject("definition")
                .put("blueId", definitionBlueId);
        ObjectNode provider = (ObjectNode) input.path("provider");
        provider.putObject("nodes").set(
                definitionBlueId,
                ContractsFixtureFeederEnvironment.compactFixtureObject(
                        exactDefinition));
        return input;
    }

    private static Node exactDefinition() {
        return new Node()
                .name("Provider-backed exact Channel definition")
                .properties("meaning", new Node().value("opaque"));
    }

    private static void putProviderNode(
            ObjectNode input,
            String blueId,
            Node exactNode) {
        ObjectNode provider = (ObjectNode) input.path("provider");
        ObjectNode nodes = provider.has("nodes")
                ? (ObjectNode) provider.path("nodes")
                : provider.putObject("nodes");
        nodes.set(
                blueId,
                ContractsFixtureFeederEnvironment.compactFixtureObject(
                        exactNode));
    }
}
