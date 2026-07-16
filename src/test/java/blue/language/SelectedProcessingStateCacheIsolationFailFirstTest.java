package blue.language;

import blue.language.MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.NodeToMapListOrValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedProcessingStateCacheIsolationFailFirstTest {

    private static final long MATERIALIZED_AUDIT_GAS = 1201L;

    @Test
    void compactAndMaterializedSelectionsHaveTheSameSemanticContentBlueId() {
        AuditFixture fixture = new AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        Node compact = fixture.compact();
        Node materialized = fixture.materializedSource();

        ResolvedSnapshot compactSnapshot = blue.resolveToSnapshot(compact);
        ResolvedSnapshot materializedSnapshot = blue.resolveToSnapshot(materialized);

        assertEquals(compactSnapshot.blueId(), materializedSnapshot.blueId());
        assertEquals(compactSnapshot.blueId(), blue.calculateBlueId(compactSnapshot.canonicalRoot()));
        assertEquals(materializedSnapshot.blueId(), blue.calculateBlueId(materializedSnapshot.canonicalRoot()));
    }

    @Test
    void validMaterializedSourceFormsHaveIdenticalSelectionAndSemanticIdentity() {
        AuditFixture fixture = new AuditFixture();
        Blue producer = fixture.newBlue(new AtomicInteger());
        Node direct = fixture.materializedSource();
        Map<String, Node> forms = sourceEquivalentForms(fixture, producer, direct);
        Set<String> expectedSelectedKeys = selectedContractKeys(direct);
        String expectedSerializedContent = producer.nodeToJson(direct);
        String expectedInputIdentity = producer.calculateSemanticBlueId(direct.clone());

        assertNull(direct.getBlue(), "a Processing Document must already be preprocessed");
        assertPureBlueIdShapes(direct, "/");
        assertEquals(expectedSerializedContent, producer.nodeToJson(producer.preprocess(direct.clone())),
                "the materialized fixture must already be a Preprocessed Document");
        assertEquals(producer.nodeToJson(fixture.compact()),
                producer.nodeToJson(producer.preprocess(fixture.compact())),
                "the compact fixture must already be a Preprocessed Document");
        assertPureBlueIdShapes(fixture.compact(), "/");
        assertEquals(producer.resolveToSnapshot(fixture.compact()).blueId(), expectedInputIdentity,
                "fully type-derived materialization must preserve semantic identity");
        for (Map.Entry<String, Node> form : forms.entrySet()) {
            String label = form.getKey();
            Node selected = form.getValue();
            assertEquals(expectedSelectedKeys, selectedContractKeys(selected), label);
            assertEquals(expectedSerializedContent, producer.nodeToJson(selected), label);
            assertNull(selected.getBlue(), label);
            assertPureBlueIdShapes(selected, "/");
            ResolvedSnapshot snapshot = assertDoesNotThrow(
                    () -> fixture.newBlue(new AtomicInteger()).resolveToSnapshot(selected.clone()), label);
            assertEquals(expectedInputIdentity, snapshot.blueId(), label);
        }
    }

    @Test
    void validMaterializedSourceProcessesDeterministicallyAcrossOrdinaryTransports() {
        AuditFixture fixture = new AuditFixture();
        Blue producer = fixture.newBlue(new AtomicInteger());
        Node direct = fixture.materializedSource();
        Map<String, Node> forms = sourceEquivalentForms(fixture, producer, direct);

        Set<String> expectedSelectedKeys = selectedContractKeys(direct);
        String expectedSerializedContent = producer.nodeToJson(direct);
        String expectedInputIdentity = producer.resolveToSnapshot(direct.clone()).blueId();
        Map<String, TransportOutcome> outcomes = new LinkedHashMap<>();
        String expectedOutputIdentity = null;
        for (Map.Entry<String, Node> form : forms.entrySet()) {
            String label = form.getKey();
            Node selected = form.getValue();
            assertEquals(expectedSelectedKeys, selectedContractKeys(selected), label);
            assertEquals(expectedSerializedContent, producer.nodeToJson(selected), label);

            AtomicInteger executions = new AtomicInteger();
            Blue consumer = fixture.newBlue(executions);
            String inputIdentity = consumer.resolveToSnapshot(selected.clone()).blueId();
            DocumentProcessingResult result = consumer.processDocument(selected, fixture.auditEvent());
            outcomes.put(label, new TransportOutcome(result, executions.get(), inputIdentity, result.blueId()));
        }

        for (Map.Entry<String, TransportOutcome> entry : outcomes.entrySet()) {
            String label = entry.getKey();
            TransportOutcome outcome = entry.getValue();
            assertEquals(ProcessorStatus.SUCCESS, outcome.result.status(), label);
            assertNull(outcome.result.errorCategory(), label);
            assertEquals(1, outcome.executions, label);
            assertEquals(MATERIALIZED_AUDIT_GAS, outcome.result.totalGas(), label);
            assertEquals(expectedInputIdentity, outcome.inputSemanticBlueId, label);
            if (expectedOutputIdentity == null) {
                expectedOutputIdentity = outcome.outputSemanticBlueId;
            } else {
                assertEquals(expectedOutputIdentity, outcome.outputSemanticBlueId, label);
            }
        }

        for (Map.Entry<String, TransportOutcome> entry : outcomes.entrySet()) {
            String label = entry.getKey();
            Node returned = entry.getValue().result.document();
            assertTrue(hasAudit(returned), label);
            assertEquals("materialized", returned.getAsText("/materializedField"), label);
            assertEquals("compact", returned.getAsText("/selectedOnly"), label);
            assertEquals(Boolean.TRUE, returned.get("/auditRan"), label);
        }
    }

    private static Map<String, Node> sourceEquivalentForms(AuditFixture fixture, Blue producer, Node direct) {
        Map<String, Node> forms = new LinkedHashMap<>();
        forms.put("direct Source", direct);
        forms.put("clone", direct.clone());
        forms.put("JSON transport", producer.jsonToNode(producer.nodeToJson(direct)));
        forms.put("YAML transport", producer.yamlToNode(producer.nodeToYaml(direct)));
        forms.put("independent content reconstruction",
                fixture.newBlue(new AtomicInteger()).objectToNode(NodeToMapListOrValue.get(direct)));
        return forms;
    }

    @Test
    void compactThenMaterializedKeepsDifferentDiscoveryOutcomes() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        Node compact = fixture.compact();
        Node materialized = fixture.materializedSource();

        DocumentProcessingResult compactResult = blue.processDocument(compact, fixture.auditEvent());
        int afterCompact = executions.get();
        DocumentProcessingResult materializedResult = blue.processDocument(materialized, fixture.auditEvent());

        assertEquals(0, afterCompact);
        assertEquals(1, executions.get());
        assertFalse(hasAudit(compactResult.document()));
        assertTrue(hasAudit(materializedResult.document()));
    }

    @Test
    void materializedThenCompactKeepsDifferentDiscoveryOutcomesInFreshBlue() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        Node compact = fixture.compact();
        Node materialized = fixture.materializedSource();

        DocumentProcessingResult materializedResult = blue.processDocument(materialized, fixture.auditEvent());
        int afterMaterialized = executions.get();
        DocumentProcessingResult compactResult = blue.processDocument(compact, fixture.auditEvent());

        assertEquals(1, afterMaterialized);
        assertEquals(1, executions.get());
        assertTrue(hasAudit(materializedResult.document()));
        assertFalse(hasAudit(compactResult.document()));
    }

    @Test
    void clonedSelectionsRemainIsolatedInBothOrders() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger firstExecutions = new AtomicInteger();
        Blue first = fixture.newBlue(firstExecutions);
        Node compact = fixture.compact();
        Node materialized = fixture.materializedSource();

        DocumentProcessingResult compactFirst = first.processDocument(compact.clone(), fixture.auditEvent());
        DocumentProcessingResult materializedSecond = first.processDocument(materialized.clone(), fixture.auditEvent());

        AtomicInteger secondExecutions = new AtomicInteger();
        Blue second = fixture.newBlue(secondExecutions);
        DocumentProcessingResult materializedFirst = second.processDocument(materialized.clone(), fixture.auditEvent());
        DocumentProcessingResult compactSecond = second.processDocument(compact.clone(), fixture.auditEvent());

        assertEquals(1, firstExecutions.get());
        assertEquals(1, secondExecutions.get());
        assertFalse(hasAudit(compactFirst.document()));
        assertTrue(hasAudit(materializedSecond.document()));
        assertTrue(hasAudit(materializedFirst.document()));
        assertFalse(hasAudit(compactSecond.document()));
    }

    private static boolean hasAudit(Node document) {
        return document != null
                && document.getContracts() != null
                && document.getContracts().getProperties() != null
                && document.getContracts().getProperties().containsKey("audit");
    }

    private static Set<String> selectedContractKeys(Node document) {
        if (document == null
                || document.getContracts() == null
                || document.getContracts().getProperties() == null) {
            return java.util.Collections.emptySet();
        }
        return new LinkedHashSet<>(document.getContracts().getProperties().keySet());
    }

    private static void assertPureBlueIdShapes(Node node, String path) {
        if (node == null) {
            return;
        }
        if (node.getBlueId() != null) {
            assertTrue(node.isReferenceOnly(), "mixed blueId node at " + path);
            return;
        }
        assertPureBlueIdShapes(node.getType(), path + "/type");
        assertPureBlueIdShapes(node.getItemType(), path + "/itemType");
        assertPureBlueIdShapes(node.getKeyType(), path + "/keyType");
        assertPureBlueIdShapes(node.getValueType(), path + "/valueType");
        assertPureBlueIdShapes(node.getContracts(), path + "/contracts");
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                assertPureBlueIdShapes(node.getItems().get(index), path + "/items/" + index);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
                assertPureBlueIdShapes(entry.getValue(), path + "/" + entry.getKey());
            }
        }
    }

    private static final class TransportOutcome {
        private final DocumentProcessingResult result;
        private final int executions;
        private final String inputSemanticBlueId;
        private final String outputSemanticBlueId;

        private TransportOutcome(DocumentProcessingResult result,
                                 int executions,
                                 String inputSemanticBlueId,
                                 String outputSemanticBlueId) {
            this.result = result;
            this.executions = executions;
            this.inputSemanticBlueId = inputSemanticBlueId;
            this.outputSemanticBlueId = outputSemanticBlueId;
        }
    }
}
