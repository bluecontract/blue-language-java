package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixture parser and facade coverage for retained cyclic revision evidence. */
final class ManagedRevisionCyclicClosureFixtureTest {

    private static final String RELEASED_FIXTURE =
            "c-clo-23-05-a9-to-a10";
    private static final String MASTER =
            "B4s6BMi4HbXS48DC1GTuozEfbSdbBepRnpP5TrsJTdkE";

    // Frozen after the focused fixture is executed once against the release
    // gas schedule; it is intentionally not derived from the implementation.
    // Sequential containing routes add 4; direct joint activation avoids the
    // discarded acyclic ancestor identity/reference work (42).
    private static final long EXPECTED_TOTAL_GAS = 439L;
    private static final long EXPECTED_PROOF_ADMISSION_GAS = 53L;
    private static final int EXPECTED_PROOF_ADMISSION_ENTRIES = 17;

    @Test
    void executesCyclicSuccessorWithExactProofGas() {
        Seed seed = seed();
        Variant variant = variant(
                seed,
                seed.exactAfterDocument,
                MASTER + "#1",
                seed.exactProof);
        ManagedRevisionCause parsedCause = (ManagedRevisionCause)
                variant.input.cause();

        assertTrue(parsedCause.afterCyclicProof().isPresent());
        assertEquals(MASTER + "#1", parsedCause.afterBlueId());

        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(variant.envelope);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor())) {
            attempt = contracts.processClosure(variant.input);
        }

        assertTrue(attempt.isComplete());
        ClosureProcessResult result = attempt.processResult();
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertTrue(result.commits());
        assertEquals(seed.expectedOutputClosureIdentity,
                result.outputClosureIdentity());
        assertEquals(EXPECTED_TOTAL_GAS, result.totalGas());
        assertEquals(EXPECTED_PROOF_ADMISSION_GAS,
                proofAdmissionGas(result.gasTrace()));
        assertEquals(EXPECTED_PROOF_ADMISSION_ENTRIES,
                proofAdmissionEntries(result.gasTrace()));
        assertFalse(hasProofFinalizationOwnership(result.gasTrace()));
    }

    @Test
    void rejectsMissingProofAndProofOnAcyclicSuccessorAtFixtureParsing() {
        Seed seed = seed();
        Variant cyclic = variant(
                seed,
                seed.exactAfterDocument,
                MASTER + "#1",
                seed.exactProof);
        ((ObjectNode) cyclic.envelope.path("input").path("cause"))
                .remove("afterCyclicProof");
        assertThrows(IllegalArgumentException.class,
                () -> parse(seed.entry, cyclic.envelope));

        ObjectNode acyclic = seed.envelope.deepCopy();
        writeProof(
                (ObjectNode) acyclic.path("input").path("cause"),
                seed.exactProof);
        assertThrows(IllegalArgumentException.class,
                () -> parse(seed.entry, acyclic));
    }

    @Test
    void rejectsTamperedProofBodyMasterAndSuffixThroughFacade() {
        Seed seed = seed();

        List<Node> tamperedMembers = new ArrayList<Node>(
                seed.exactProof.declaredPlaceholderSet());
        tamperedMembers.get(0).properties(
                "tampered", new Node().value(Boolean.TRUE));
        assertFacadeRejects(variant(
                seed,
                seed.exactAfterDocument,
                MASTER + "#1",
                CyclicSetProof.fromDeclaredPlaceholderSet(
                        tamperedMembers)));

        assertFacadeRejects(variant(
                seed,
                seed.exactAfterDocument.clone().properties(
                        "tampered", new Node().value(Boolean.TRUE)),
                MASTER + "#1",
                seed.exactProof));

        String anotherMaster = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("another managed-revision master"));
        assertFacadeRejects(variant(
                seed,
                seed.exactAfterDocument,
                anotherMaster + "#1",
                seed.exactProof));

        assertFacadeRejects(variant(
                seed,
                seed.exactAfterDocument,
                MASTER + "#99",
                seed.exactProof));
    }

    private static Variant variant(
            Seed seed,
            Node afterDocument,
            String afterBlueId,
            CyclicSetProof proof) {
        ManagedRevisionCause before = (ManagedRevisionCause)
                seed.baseInput.cause();
        ManagedRevisionCause cause =
                ClosureEvidenceFactory.managedRevisionCause(
                        before.targetOccurrenceIdentity(),
                        before.childDocumentId(),
                        before.fromEpoch(),
                        before.toEpoch(),
                        before.beforeBlueId(),
                        afterBlueId,
                        afterDocument,
                        before.originalSourceCauseIdentity(),
                        proof);
        ClosureInvocationInput derived =
                ClosureEvidenceFactory.processClosure(
                        seed.baseInput.snapshot(),
                        cause,
                        seed.baseInput.directDeliveries(),
                        seed.baseInput.executionPolicy(),
                        seed.baseInput.environment());

        ObjectNode envelope = seed.envelope.deepCopy();
        ObjectNode encodedCause = (ObjectNode) envelope
                .path("input").path("cause");
        encodedCause.put("causeIdentity", cause.causeIdentity());
        encodedCause.put("afterBlueId", cause.afterBlueId());
        encodedCause.set(
                "afterDocument", json(cause.afterDocument()));
        encodedCause.put(
                "sourceRevisionReceiptIdentity",
                cause.sourceRevisionReceiptIdentity());
        writeProof(encodedCause, proof);
        ((ObjectNode) envelope.path("input")).put(
                "invocationIdentity", derived.invocationIdentity());
        return new Variant(
                envelope,
                parse(seed.entry, envelope));
    }

    private static void assertFacadeRejects(Variant variant) {
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(variant.envelope);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor())) {
            assertThrows(IllegalArgumentException.class,
                    () -> contracts.processClosure(variant.input));
        }
    }

    private static ClosureInvocationInput parse(
            ClosureFixtureInventory.Entry entry,
            JsonNode envelope) {
        return new ClosureFixtureParser().parse(entry, envelope).admit();
    }

    private static Seed seed() {
        ClosureFixtureInventory.Entry entry = requireEntry(RELEASED_FIXTURE);
        ObjectNode released = (ObjectNode) ClosureFixtureInventory
                .readFixture(entry).deepCopy();
        JsonNode expected = ClosureFixtureInventory.requiredObject(
                released, "expected");
        JsonNode resultComponent = first(
                ClosureFixtureInventory.requiredArray(
                        expected, "resultingComponents"));
        assertEquals(MASTER, ClosureFixtureInventory.requiredText(
                resultComponent, "masterBlueId"));
        JsonNode proof = ClosureFixtureInventory.requiredObject(
                resultComponent, "completeCyclicProof");
        ArrayList<Node> placeholders = new ArrayList<Node>();
        for (JsonNode placeholder : ClosureFixtureInventory.requiredArray(
                proof, "declaredPlaceholderSet")) {
            placeholders.add(node(placeholder));
        }
        JsonNode resultDocument = document(
                ClosureFixtureInventory.requiredArray(
                        expected, "resultingDocuments"),
                "history-a");
        Node exactAfterDocument = node(
                ClosureFixtureInventory.requiredObject(
                        resultDocument, "document"));
        assertEquals(MASTER + "#1",
                ClosureFixtureInventory.requiredText(
                        resultDocument, "afterBlueId"));

        ObjectNode envelope = released.deepCopy();
        envelope.remove("expected");
        return new Seed(
                entry,
                envelope,
                parse(entry, envelope),
                exactAfterDocument,
                CyclicSetProof.fromDeclaredPlaceholderSet(placeholders),
                ClosureFixtureInventory.requiredText(
                        expected, "outputClosureIdentity"));
    }

    private static void writeProof(
            ObjectNode cause,
            CyclicSetProof proof) {
        ObjectNode encoded = cause.putObject("afterCyclicProof");
        ArrayNode placeholders = encoded.putArray(
                "declaredPlaceholderSet");
        for (Node placeholder : proof.declaredPlaceholderSet()) {
            placeholders.add(json(placeholder));
        }
    }

    private static JsonNode json(Node value) {
        return UncheckedObjectMapper.JSON_MAPPER.valueToTree(value);
    }

    private static Node node(JsonNode value) {
        return UncheckedObjectMapper.JSON_MAPPER.convertValue(
                value, Node.class);
    }

    private static JsonNode first(JsonNode values) {
        if (values.size() != 1) {
            throw new AssertionError("Expected one resulting component");
        }
        return values.get(0);
    }

    private static JsonNode document(
            JsonNode values,
            String documentId) {
        for (JsonNode value : values) {
            if (documentId.equals(ClosureFixtureInventory.requiredText(
                    value, "documentId"))) {
                return value;
            }
        }
        throw new AssertionError("Missing resulting document " + documentId);
    }

    private static ClosureFixtureInventory.Entry requireEntry(String id) {
        for (ClosureFixtureInventory.Entry entry
                : ClosureFixtureInventory.load()) {
            if (id.equals(entry.id())) {
                return entry;
            }
        }
        throw new AssertionError("Missing closure fixture " + id);
    }

    private static long proofAdmissionGas(List<GasTraceEntry> trace) {
        long result = 0L;
        for (GasTraceEntry entry : trace) {
            if (isProofAdmission(entry)) {
                result += entry.subtotal();
            }
        }
        return result;
    }

    private static int proofAdmissionEntries(List<GasTraceEntry> trace) {
        int result = 0;
        for (GasTraceEntry entry : trace) {
            if (isProofAdmission(entry)) {
                result++;
            }
        }
        return result;
    }

    private static boolean hasProofFinalizationOwnership(
            List<GasTraceEntry> trace) {
        for (GasTraceEntry entry : trace) {
            if (isProofAdmission(entry)
                    && ("tentativeComponentFinalization".equals(
                                entry.counter())
                        || "cyclicMemberFinalized".equals(
                                entry.counter())
                        || entry.componentGeneration() != null
                        || entry.workOccurrenceId() != null)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProofAdmission(GasTraceEntry entry) {
        return entry.reason() != null
                && entry.reason().startsWith(
                        "admission.managed-revision.after-document."
                                + "cyclic-proof.");
    }

    private static final class Seed {
        private final ClosureFixtureInventory.Entry entry;
        private final ObjectNode envelope;
        private final ClosureInvocationInput baseInput;
        private final Node exactAfterDocument;
        private final CyclicSetProof exactProof;
        private final String expectedOutputClosureIdentity;

        private Seed(
                ClosureFixtureInventory.Entry entry,
                ObjectNode envelope,
                ClosureInvocationInput baseInput,
                Node exactAfterDocument,
                CyclicSetProof exactProof,
                String expectedOutputClosureIdentity) {
            this.entry = entry;
            this.envelope = envelope;
            this.baseInput = baseInput;
            this.exactAfterDocument = exactAfterDocument;
            this.exactProof = exactProof;
            this.expectedOutputClosureIdentity =
                    expectedOutputClosureIdentity;
        }
    }

    private static final class Variant {
        private final ObjectNode envelope;
        private final ClosureInvocationInput input;

        private Variant(
                ObjectNode envelope,
                ClosureInvocationInput input) {
            this.envelope = envelope;
            this.input = input;
        }
    }
}
