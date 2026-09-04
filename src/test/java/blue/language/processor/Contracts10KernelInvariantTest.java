package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.model.JsonPatch;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.identity.DirectBlueIdCalculator;
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

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Contracts10KernelInvariantTest {

    @Test
    void shouldVerifyResultOwnsDefensiveRootAndEventSnapshots() {
        // given
        Node root = new Node().properties(
                "value", new Node().value(1));
        Node event = new Node().properties(
                "id", new Node().value("E1"));
        DocumentProcessingResult result =
                DocumentProcessingResult.of(
                        root,
                        Collections.singletonList(event),
                        7L);

        // when
        root.properties("later", new Node().value(true));
        event.properties("later", new Node().value(true));
        Node firstRoot = result.document();
        Node firstEvent = result.events().get(0);
        firstRoot.properties("consumerMutation", new Node().value(true));
        firstEvent.properties("consumerMutation", new Node().value(true));

        // then
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
    void shouldVerifyManifestFormulaParametersDriveSemanticQuantities()
            throws Exception {
        // given
        GasSchedule baseline = GasSchedule.contracts10();
        Map<String, Object> manifest = loadGasManifest();
        @SuppressWarnings("unchecked")
        Map<String, Object> formulas =
                (Map<String, Object>) manifest.get("formulas");
        @SuppressWarnings("unchecked")
        Map<String, Object> text =
                (Map<String, Object>) formulas.get("textBlocks");

        // when
        text.put("blockCodePoints", 8);
        manifest.put("packageIdentity", packageIdentity(manifest));

        GasSchedule altered = GasSchedule.load(new ByteArrayInputStream(
                UncheckedObjectMapper.YAML_MAPPER
                        .writeValueAsBytes(manifest)));
        GasMeter meter = new GasMeter(altered);
        meter.semantic().textCodePointsExamined(
                9L, GasChargeContext.reason("test"));

        // then
        assertEquals(
                GasSchedule.CONTRACTS_1_0_PACKAGE_IDENTITY,
                baseline.packageIdentity());
        assertEquals(64L,
                baseline.formulaParameter("textBlockCodePoints"));
        assertEquals(9L,
                baseline.formulaParameter("identityHashDomainBytes"));
        assertEquals(8L,
                altered.formulaParameter("textBlockCodePoints"));
        assertEquals(2L, meter.trace().get(0).quantity());
    }

    @Test
    void shouldVerifyAlteredManifestWithoutRebindingIdentityIsRejected()
            throws Exception {
        // given
        Map<String, Object> manifest = loadGasManifest();
        manifest.put("maxProcessGas", 99999);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> GasSchedule.load(new ByteArrayInputStream(
                        UncheckedObjectMapper.YAML_MAPPER
                                .writeValueAsBytes(manifest))));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectZeroWeightGasManifestCounterAfterIdentityRebinding()
            throws Exception {
        // given
        Map<String, Object> manifest = loadGasManifest();
        @SuppressWarnings("unchecked")
        Map<String, Object> namespaces =
                (Map<String, Object>) manifest.get(
                        GasScheduleConstants.ManifestField
                                .NAMESPACES);
        @SuppressWarnings("unchecked")
        Map<String, Object> processor =
                (Map<String, Object>) namespaces.get(
                        GasScheduleConstants.Namespace
                                .PROCESSOR);
        @SuppressWarnings("unchecked")
        Map<String, Object> counters =
                (Map<String, Object>) processor.get(
                        GasScheduleConstants.ManifestField
                                .COUNTERS);
        counters.put(
                GasScheduleConstants.ProcessorCounter
                        .PROCESS_INVOCATION,
                0L);
        manifest.put(
                GasScheduleConstants.ManifestField
                        .PACKAGE_IDENTITY,
                packageIdentity(manifest));

        // when
        IllegalArgumentException failure =
                captureFailure(
                        () -> GasSchedule.load(
                                new ByteArrayInputStream(
                                        UncheckedObjectMapper
                                                .YAML_MAPPER
                                                .writeValueAsBytes(
                                                        manifest))));

        // then
        assertTrue(failure != null);
        assertTrue(
                failure.getMessage()
                        .contains("must be positive"));
    }

    @Test
    void shouldVerifyRuntimeCountersAreNamedAndChildLedgerMergesOnce() {
        // given
        GasMeter meter = new GasMeter();
        Map<String, Long> weights = new LinkedHashMap<>();
        weights.put("instruction", 3L);
        GasMeter.ChildGasLedger child =
                meter.childLedger("test-runtime", weights);
        child.charge(
                "instruction",
                2L,
                GasChargeContext.reason("before-runtime-work"));
        // when
        meter.merge(child);
        Throwable secondMergeFailure = FailureCapture.captureFailure(
                () -> meter.merge(child));
        Throwable postMergeChargeFailure =
                FailureCapture.captureFailure(
                        () -> child.charge("instruction", 1L));

        // then
        assertEquals(1, meter.trace().size());
        assertEquals("test-runtime",
                meter.trace().get(0).namespace());
        assertEquals("instruction",
                meter.trace().get(0).counter());
        assertEquals(6L, meter.totalGas());
        assertTrue(secondMergeFailure instanceof IllegalStateException);
        assertTrue(postMergeChargeFailure instanceof IllegalStateException);
    }

    @Test
    void shouldVerifyPatchIdentityWorkIsMetered() {
        // given
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node().value(0));

        // when
        runtime.applyPatch(
                "/",
                JsonPatch.replace(
                        "/value", new Node().value(1)));

        // then
        assertTrue(runtime.conformanceTrace().counterQuantity(
                "semantic", "nodeIdentityEstablished") > 0L);
        assertTrue(runtime.conformanceTrace().counterQuantity(
                "semantic", "objectMemberRebuilt") > 0L);
        assertTrue(runtime.conformanceTrace().counterQuantity(
                "semantic", "directIdentityHashBlock") > 0L);
    }

    @Test
    void shouldVerifyChangedSubscriptionValidationIsLocalAndMissingChildrenAreInactive() {
        // given
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

        // when
        SubscriptionDelta local =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext.builder(
                                        rootWithReservedMissingChild,
                                        afterUnrelatedChange,
                                        Collections.singleton("/value"),
                                        GasSchedule.contracts10())
                                .build());
        SubscriptionDelta changedDeclaration =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext.builder(
                                        rootWithReservedMissingChild,
                                        afterUnrelatedChange,
                                        Collections.singleton(
                                                "/contracts/embedded/paths"),
                                        GasSchedule.contracts10())
                                .build());

        // then
        assertTrue(local.isEmpty());
        assertTrue(changedDeclaration.isEmpty());
    }

    @Test
    void shouldVerifyNewlyReachableSubscriptionBranchIsValidatedAsAWhole() {
        // given
        Node before = new Node()
                .contracts(new Node().properties(
                        "embedded",
                        new Node()
                                .type(reference(
                                        blue.language.processor.registry
                                                .RuntimeBlueIds
                                                .PROCESS_EMBEDDED))
                                .properties("paths", new Node().items(
                                        new Node().value("/reserved")))));
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

        // when
        SubscriptionSurfaceInvalidException failure = captureFailure(
                () -> DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext.builder(
                                        before,
                                        after,
                                        Collections.singleton(
                                                "/contracts/embedded/paths"),
                                        GasSchedule.contracts10())
                                .build()));

        // then
        assertEquals(SubscriptionSurfaceInvalidException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "finite non-empty subscription key set"),
                failure.getMessage());
    }

    @Test
    void shouldVerifyProcessAttemptCompletesInvalidEvidenceBeforeReportingResources() {
        // given
        Node root = Nodes.emptyObject();
        Node event = new Node().value("event");
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(
                                DirectBlueIdCalculator.calculateBlueId(
                                        new Node().name("forged root")),
                                DirectBlueIdCalculator.calculateBlueId(event))
                        .revisions(3L, 3L)
                        .runtimeRegistryIdentity(
                                blue.language.processor.registry.RuntimeBlueIds
                                        .REGISTRY_PACKAGE_IDENTITY)
                        .eventOrderKey(ExternalOrderKey.of(
                                java.util.Arrays.asList(1, "source", 1)))
                        .requiredExactNode(
                                DirectBlueIdCalculator.calculateBlueId(
                                        new Node().name(
                                                "missing exact node")))
                        .build();

        // when
        ProcessAttemptResult attempt =
                new DocumentProcessor().processAttempt(
                        root, event, evidence);

        // then
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
