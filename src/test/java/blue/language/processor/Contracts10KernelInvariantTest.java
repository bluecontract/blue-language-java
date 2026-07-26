package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.utils.UncheckedObjectMapper;
import blue.language.utils.BlueIdCalculator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Contracts10KernelInvariantTest {

    @Test
    void resultOwnsDefensiveRootAndEventSnapshots() {
        Node root = new Node().properties(
                "value", new Node().value(1));
        Node event = new Node().properties(
                "id", new Node().value("E1"));
        DocumentProcessingResult result =
                DocumentProcessingResult.of(
                        root,
                        Collections.singletonList(event),
                        7L);

        root.properties("later", new Node().value(true));
        event.properties("later", new Node().value(true));
        Node firstRoot = result.document();
        Node firstEvent = result.events().get(0);
        firstRoot.properties("consumerMutation", new Node().value(true));
        firstEvent.properties("consumerMutation", new Node().value(true));

        assertFalse(result.document().getProperties()
                .containsKey("later"));
        assertFalse(result.document().getProperties()
                .containsKey("consumerMutation"));
        assertFalse(result.events().get(0).getProperties()
                .containsKey("later"));
        assertFalse(result.events().get(0).getProperties()
                .containsKey("consumerMutation"));
        assertNotSame(firstRoot, result.document());
        assertNotSame(firstEvent, result.events().get(0));
    }

    @Test
    void manifestFormulaParametersDriveSemanticQuantities()
            throws Exception {
        GasSchedule baseline = GasSchedule.contracts10();
        assertEquals(
                GasSchedule.CONTRACTS_1_0_PACKAGE_IDENTITY,
                baseline.packageIdentity());
        assertEquals(64L,
                baseline.formulaParameter("textBlockCodePoints"));
        assertEquals(9L,
                baseline.formulaParameter("identityHashDomainBytes"));

        Map<String, Object> manifest = loadGasManifest();
        @SuppressWarnings("unchecked")
        Map<String, Object> formulas =
                (Map<String, Object>) manifest.get("formulas");
        @SuppressWarnings("unchecked")
        Map<String, Object> text =
                (Map<String, Object>) formulas.get("textBlocks");
        text.put("blockCodePoints", 8);
        manifest.put("packageIdentity", packageIdentity(manifest));

        GasSchedule altered = GasSchedule.load(new ByteArrayInputStream(
                UncheckedObjectMapper.YAML_MAPPER
                        .writeValueAsBytes(manifest)));
        GasMeter meter = new GasMeter(altered);
        meter.semantic().textCodePointsExamined(
                9L, GasChargeContext.reason("test"));

        assertEquals(8L,
                altered.formulaParameter("textBlockCodePoints"));
        assertEquals(2L, meter.trace().get(0).quantity());
    }

    @Test
    void alteredManifestWithoutRebindingIdentityIsRejected()
            throws Exception {
        Map<String, Object> manifest = loadGasManifest();
        manifest.put("maxProcessGas", 99999);
        assertThrows(IllegalArgumentException.class,
                () -> GasSchedule.load(new ByteArrayInputStream(
                        UncheckedObjectMapper.YAML_MAPPER
                                .writeValueAsBytes(manifest))));
    }

    @Test
    void runtimeCountersAreNamedAndChildLedgerMergesOnce() {
        GasMeter meter = new GasMeter();
        Map<String, Long> weights = new LinkedHashMap<>();
        weights.put("instruction", 3L);
        GasMeter.ChildGasLedger child =
                meter.childLedger("test-runtime", weights);
        child.charge(
                "instruction",
                2L,
                GasChargeContext.reason("before-runtime-work"));
        meter.merge(child);

        assertEquals(1, meter.trace().size());
        assertEquals("test-runtime",
                meter.trace().get(0).namespace());
        assertEquals("instruction",
                meter.trace().get(0).counter());
        assertEquals(6L, meter.totalGas());
        assertThrows(IllegalStateException.class,
                () -> meter.merge(child));
        assertThrows(IllegalStateException.class,
                () -> child.charge("instruction", 1L));
    }

    @Test
    void anonymousGasIsRejectedAndPatchIdentityWorkIsMetered() {
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node().value(0));
        assertThrows(UnsupportedOperationException.class,
                () -> runtime.addGas(1L));

        runtime.applyPatch(
                "/",
                JsonPatch.replace(
                        "/value", new Node().value(1)));

        assertTrue(runtime.conformanceTrace().counterQuantity(
                "semantic", "nodeIdentityEstablished") > 0L);
        assertTrue(runtime.conformanceTrace().counterQuantity(
                "semantic", "objectMemberRebuilt") > 0L);
        assertTrue(runtime.conformanceTrace().counterQuantity(
                "semantic", "directIdentityHashBlock") > 0L);
    }

    @Test
    void changedSubscriptionValidationIsLocalAndMissingChildrenAreInactive() {
        Node rootWithReservedMissingChild = new Node()
                .properties("value", new Node().value(0))
                .contracts(new Node().properties(
                        "embedded",
                        new Node()
                                .type(reference(
                                        blue.language.processor.registry
                                                .RuntimeBlueIds
                                                .PROCESS_EMBEDDED))
                                .properties(
                                        "paths",
                                        new Node().items(
                                                new Node().value(
                                                        "/missing")))));
        Node afterUnrelatedChange = rootWithReservedMissingChild.clone();
        afterUnrelatedChange.getProperties().get("value").value(1);

        SubscriptionDelta local =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        rootWithReservedMissingChild,
                        afterUnrelatedChange,
                        Collections.singleton("/value"),
                        GasSchedule.contracts10());
        assertTrue(local.isEmpty());

        SubscriptionDelta changedDeclaration =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        rootWithReservedMissingChild,
                        afterUnrelatedChange,
                        Collections.singleton(
                                "/contracts/embedded/paths"),
                        GasSchedule.contracts10());
        assertTrue(changedDeclaration.isEmpty());
    }

    @Test
    void newlyReachableSubscriptionBranchIsValidatedAsAWhole() {
        Node before = new Node()
                .contracts(new Node().properties(
                        "embedded",
                        new Node()
                                .type(reference(
                                        blue.language.processor.registry
                                                .RuntimeBlueIds
                                                .PROCESS_EMBEDDED))
                                .properties("paths", new Node().items())));
        Node after = before.clone();
        after.getContracts()
                .getProperties().get("embedded")
                .properties("paths",
                        new Node().items(
                                new Node().value("/child")));
        after.properties("child",
                new Node().contracts(new Node().properties(
                        "out",
                        new Node()
                                .type(reference(
                                        blue.language.processor.registry
                                                .RuntimeBlueIds
                                                .SCRIPTED_EXTERNAL_CHANNEL))
                                .properties("checkpointDomain",
                                        new Node().value("domain")))));

        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        before,
                        after,
                        Collections.singleton(
                                "/contracts/embedded/paths"),
                        GasSchedule.contracts10()));

        assertTrue(failure.getMessage().contains(
                "finite non-empty subscription key set"));
    }

    @Test
    void processAttemptCompletesInvalidEvidenceBeforeReportingResources() {
        Node root = new Node();
        Node event = new Node().value("event");
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(
                                "forged-root",
                                BlueIdCalculator.calculateBlueId(event))
                        .revisions(3L, 3L)
                        .runtimeRegistryIdentity(
                                blue.language.processor.registry.RuntimeBlueIds
                                        .REGISTRY_PACKAGE_IDENTITY)
                        .eventOrderKey(ExternalOrderKey.of(
                                java.util.Arrays.asList(1, "source", 1)))
                        .requiredExactNode("missing-exact-node")
                        .build();

        ProcessAttemptResult attempt =
                new DocumentProcessor().processAttempt(
                        root, event, evidence);

        assertTrue(attempt.isComplete());
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                attempt.processResult().status());
        assertTrue(attempt.requiredExactBlueIds().isEmpty());
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private Map<String, Object> loadGasManifest()
            throws Exception {
        try (InputStream input =
                     getClass().getClassLoader().getResourceAsStream(
                             GasSchedule.CONTRACTS_1_0_RESOURCE)) {
            return UncheckedObjectMapper.YAML_MAPPER.readValue(
                    input,
                    new TypeReference<Map<String, Object>>() { });
        }
    }

    private String packageIdentity(Map<String, Object> source)
            throws Exception {
        byte[] serialized = UncheckedObjectMapper.YAML_MAPPER
                .writeValueAsBytes(source);
        Map<String, Object> payload =
                UncheckedObjectMapper.YAML_MAPPER.readValue(
                        serialized,
                        new TypeReference<Map<String, Object>>() { });
        payload.put("packageIdentity", null);
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        byte[] canonical = new JsonCanonicalizer(
                mapper.writeValueAsString(payload)).getEncodedUTF8();
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return "sha256:" + hex;
    }
}
