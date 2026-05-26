package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.BlueContractsConformanceFailure;
import blue.language.BlueContractsConformanceReport;
import blue.language.BlueContractsConformanceSuiteRunner;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceFixtureTest {

    @Test
    void blueContractsConformanceSuitePassesFixtures() {
        BlueContractsConformanceReport report = new Blue().runContractsConformanceSuite();
        Map<String, BlueContractsConformanceFailure> failuresById = report.getFailures().stream()
                .collect(Collectors.toMap(BlueContractsConformanceFailure::getFixtureId, Function.identity()));

        assertTrue(report.getFailures().isEmpty(), () -> failuresById.values().stream()
                .map(this::failureMessage)
                .collect(Collectors.joining("\n")));
        assertEquals(report.getFixtureIds(), report.getPassedFixtureIds());
    }

    @Test
    void contractsConformanceManifestIdentityMatchesFixtureFiles() {
        assertEquals(BlueContractsConformanceReport.computeFixturePackageIdentity(),
                new Blue().contractsConformanceReport().getFixturePackageIdentity());
        assertTrue(BlueContractsConformanceReport.fixturePackageIdentityMatchesFixtureFiles());
    }

    @Test
    void contractsRequiredFixtureCoverageIsReported() {
        assertTrue(BlueContractsConformanceReport.requiredFixtureIdsForContracts10()
                .contains("T078_direct_write_termination_costs_configured_amount"));
        assertTrue(new Blue().contractsConformanceReport().hasRequiredFixtureCoverage());
    }

    @Test
    void contractsRequiredFixtureCoverageAllowsSuperset() {
        List<String> ids = new ArrayList<>(BlueContractsConformanceReport.requiredFixtureIdsForContracts10());
        ids.add("T999_extra_contract_fixture");
        BlueContractsConformanceReport report = reportWithFixtureIds(ids);

        assertTrue(report.hasRequiredFixtureCoverage());
    }

    @Test
    void contractsExactRequiredFixtureSetRejectsExtraOrMissing() {
        List<String> ids = new ArrayList<>(BlueContractsConformanceReport.requiredFixtureIdsForContracts10());
        BlueContractsConformanceReport exact = reportWithFixtureIds(ids);
        assertTrue(exact.hasRequiredFixtureCoverage());
        assertTrue(exact.hasExactRequiredFixtureSet());

        List<String> withExtra = new ArrayList<>(ids);
        withExtra.add("T999_extra_contract_fixture");
        BlueContractsConformanceReport extra = reportWithFixtureIds(withExtra);
        assertTrue(extra.hasRequiredFixtureCoverage());
        assertFalse(extra.hasExactRequiredFixtureSet());

        List<String> missing = Collections.singletonList(ids.get(0));
        BlueContractsConformanceReport incomplete = reportWithFixtureIds(missing);
        assertFalse(incomplete.hasRequiredFixtureCoverage());
        assertFalse(incomplete.hasExactRequiredFixtureSet());
    }

    @Test
    void contractsExactRequiredFixtureSetAcceptsCurrentManifest() {
        assertTrue(new Blue().contractsConformanceReport().hasExactRequiredFixtureSet());
    }

    @Test
    void contractsManifestAndRequiredFixtureSetAligned() throws Exception {
        JsonNode manifest = readFixture("manifest.yaml");
        Set<String> required = new HashSet<>(BlueContractsConformanceReport.requiredFixtureIdsForContracts10());
        Set<String> manifestIds = new HashSet<>();
        Set<String> manifestPaths = new HashSet<>();
        Path fixtureRoot = Paths.get("src/test/resources/blue-contracts-1.0/fixtures");
        for (JsonNode fixture : manifest.get("fixtures")) {
            String id = fixture.get("id").asText();
            String path = fixture.get("path").asText();
            manifestIds.add(id);
            manifestPaths.add(path);
            assertTrue(Files.exists(fixtureRoot.resolve(path)),
                    "Missing fixture file " + path);
            assertEquals(id, readFixture(path).get("id").asText());
        }
        assertEquals(required, manifestIds);

        Set<String> yamlFiles = Files.walk(fixtureRoot)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".yaml"))
                .map(path -> fixtureRoot.relativize(path).toString())
                .filter(path -> !"manifest.yaml".equals(path))
                .collect(Collectors.toSet());
        assertEquals(manifestPaths, yamlFiles);
    }

    @Test
    void contractsFixtureMetadataIsValid() throws Exception {
        JsonNode manifest = readFixture("manifest.yaml");
        for (JsonNode fixture : manifest.get("fixtures")) {
            String path = fixture.get("path").asText();
            BlueContractsConformanceSuiteRunner.validateFixtureMetadataForTest(readFixture(path));
        }
    }

    @Test
    void contractsFixtureWithoutMeaningfulAssertionFails() {
        JsonNode spec = fixtureSpec(
                "id: local_no_assertion\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void contractsFixtureExpectedCapabilityFailureFalseAloneIsNotMeaningful() {
        JsonNode spec = fixtureSpec(
                "id: local_capability_false_only\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "expectedCapabilityFailure: false\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void contractsFixtureExpectedCapabilityFailureTrueRequiresNoMutationOrReason() {
        JsonNode spec = fixtureSpec(
                "id: local_capability_true_only\n" +
                "category: MustUnderstand\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "expectedCapabilityFailure: true\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void contractsFixtureExpectedCapabilityFailureWithNoMutationIsMeaningful() {
        JsonNode spec = fixtureSpec(
                "id: local_capability_true_with_no_mutation\n" +
                "category: MustUnderstand\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "expectedCapabilityFailure: true\n" +
                "expectedNoDocumentMutation: true\n");

        BlueContractsConformanceSuiteRunner.validateFixtureMetadataForTest(spec);
    }

    @Test
    void contractsFixtureUnknownExpectedFieldFailsMetadataValidation() {
        JsonNode spec = fixtureSpec(
                "id: local_unknown_expected\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "expectedDocument: {}\n" +
                "expectedNotARealField: true\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void contractsFixtureUnknownProcessorCapabilityFails() {
        JsonNode spec = fixtureSpec(
                "id: local_unknown_capability\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "processorCapabilities:\n" +
                "  - blue-contracts-fixture-missing-v1\n" +
                "initialDocument: {}\n" +
                "expectedDocument: {}\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void contractsFixtureExpectedStatusIsChecked() {
        JsonNode spec = fixtureSpec(
                "id: local_expected_status\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "event:\n" +
                "  value: event\n" +
                "expectedStatus: runtime-fatal\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void contractsFixtureExpectedErrorCategoryIsChecked() {
        JsonNode spec = fixtureSpec(
                "id: local_expected_error_category\n" +
                "category: ContractKey\n" +
                "operation: processDocument\n" +
                "initialDocument:\n" +
                "  contracts:\n" +
                "    \"\": {}\n" +
                "expectedStatus: runtime-fatal\n" +
                "expectedErrorCategory: UnsupportedContract\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void contractsFixtureExpectedErrorCategoriesAcceptsAnyListedCategory() {
        JsonNode spec = fixtureSpec(
                "id: local_expected_error_categories\n" +
                "category: ContractKey\n" +
                "operation: processDocument\n" +
                "initialDocument:\n" +
                "  contracts:\n" +
                "    \"\": {}\n" +
                "expectedStatus: runtime-fatal\n" +
                "expectedErrorCategories: [UnsupportedContract, InvalidRuntimePointer]\n");

        BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec);
    }

    @Test
    void contractsFixtureExpectedDocumentIsCompared() {
        JsonNode spec = fixtureSpec(
                "id: local_expected_document\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "event:\n" +
                "  value: event\n" +
                "expectedDocument: {}\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void contractsFixtureExpectedAbsentPathIsChecked() {
        JsonNode spec = fixtureSpec(
                "id: local_absent_path\n" +
                "category: Patching\n" +
                "operation: processDocument\n" +
                "initialDocument:\n" +
                "  present: true\n" +
                "event:\n" +
                "  value: event\n" +
                "expectedAbsentDocumentPaths:\n" +
                "  - /present\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void contractsFixtureExpectedRootEventsCompared() {
        JsonNode spec = fixtureSpec(
                "id: local_root_events\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "event:\n" +
                "  value: event\n" +
                "expectedRootEvents: []\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void expectedRootEventsFailsWhenExtraRootEventExists() {
        JsonNode spec = fixtureSpec(
                emitScalarFixture("local_exact_root_events") +
                "expectedRootEvents:\n" +
                "  - value: emitted-scalar\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void expectedRootEventSuffixWorksOnlyWhenExplicitlyRequested() {
        JsonNode spec = fixtureSpec(
                emitScalarFixture("local_root_event_suffix") +
                "expectedRootEventSuffix:\n" +
                "  - value: emitted-scalar\n");

        BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec);
    }

    @Test
    void runtimeInsertionEventIndexIsZeroBasedFromBeginning() {
        JsonNode spec = fixtureSpec(
                emitScalarFixture("local_event_index") +
                "expectedRuntimeInsertionNormalizedValues:\n" +
                "  - eventIndex: 0\n" +
                "    selectedDocumentForm:\n" +
                "      value: emitted-scalar\n" +
                "      type:\n" +
                "        blueId: GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void runtimeInsertionEventIndexFromEndRequiresExplicitField() {
        JsonNode spec = fixtureSpec(
                emitScalarFixture("local_event_index_from_end") +
                "expectedRuntimeInsertionNormalizedValues:\n" +
                "  - eventIndexFromEnd: 0\n" +
                "    selectedDocumentForm:\n" +
                "      value: emitted-scalar\n" +
                "      type:\n" +
                "        blueId: GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC\n");

        BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec);
    }

    @Test
    void dispatchSnapshotDoesNotSkipReplacedLaterHandler() throws Exception {
        BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(readFixture(
                "dispatch-snapshot/T065_replacing_later_handler_does_not_affect_current_delivery_content.yaml"));
    }

    @Test
    void contractsFixtureExpectedDocumentPathValuesCompared() {
        JsonNode spec = fixtureSpec(
                "id: local_path_values\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument:\n" +
                "  present:\n" +
                "    value: true\n" +
                "event:\n" +
                "  value: event\n" +
                "expectedDocumentPathValues:\n" +
                "  - path: /present\n" +
                "    value:\n" +
                "      value: false\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void contractsFixtureExpectedRootEventPathValuesCompared() {
        JsonNode spec = fixtureSpec(
                "id: local_root_event_path_values\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "event:\n" +
                "  value: event\n" +
                "expectedRootEventPathValues:\n" +
                "  - index: 0\n" +
                "    path: /documentId\n" +
                "    value:\n" +
                "      value: not-the-document-id\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void contractsFixtureExpectedExactGasCompared() {
        JsonNode spec = fixtureSpec(
                "id: local_exact_gas\n" +
                "category: Initialization\n" +
                "operation: processDocument\n" +
                "initialDocument: {}\n" +
                "event:\n" +
                "  value: event\n" +
                "expectedExactGas: 999999\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void contractsFixtureCheckpointLastEventsCompared() {
        JsonNode spec = fixtureSpec(
                "id: local_checkpoint_last_events\n" +
                "category: Checkpoint\n" +
                "operation: processDocument\n" +
                "initialDocument:\n" +
                "  contracts:\n" +
                "    channel:\n" +
                "      type:\n" +
                "        blueId: 9XJaukZBmGUkFJ5TD3mrEnj98A6UfXXhzXGtwTJapmZi\n" +
                "event:\n" +
                "  kind: checkpoint\n" +
                "expectedCheckpointLastEvents:\n" +
                "  channel:\n" +
                "    kind: different\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    @Test
    void contractsFixtureFailureReasonChecked() {
        JsonNode spec = fixtureSpec(
                "id: local_failure_reason\n" +
                "category: ProcessingDocument\n" +
                "operation: processDocument\n" +
                "initialDocument:\n" +
                "  value: scalar-root\n" +
                "event:\n" +
                "  value: event\n" +
                "expectedCapabilityFailure: true\n" +
                "expectedFailureReasonContains: not-the-reason\n");

        assertThrows(AssertionError.class,
                () -> BlueContractsConformanceSuiteRunner.runFixtureSpecForTest(spec));
    }

    private JsonNode readFixture(String path) throws Exception {
        String resource = "blue-contracts-1.0/fixtures/" + path;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing fixture resource: " + resource);
            }
            return UncheckedObjectMapper.YAML_MAPPER.readTree(input);
        }
    }

    private JsonNode fixtureSpec(String yaml) {
        return UncheckedObjectMapper.YAML_MAPPER.readTree(yaml);
    }

    private String emitScalarFixture(String id) {
        return "id: " + id + "\n" +
                "category: Normalization\n" +
                "operation: processDocument\n" +
                "processorCapabilities:\n" +
                "  - blue-contracts-fixture-scripted-runtime-v1\n" +
                "initialDocument:\n" +
                "  contracts:\n" +
                "    incoming:\n" +
                "      type:\n" +
                "        blueId: C37UoAfTNUnoxkB2CdEE7BfHJwYqTNiWzQb5xuRMkBzm\n" +
                "    emitter:\n" +
                "      type:\n" +
                "        blueId: 3rHWt14WhTvmBBQ6Cr1Mb263KuxSdwqvb2jD7oPbkNL3\n" +
                "      channel: incoming\n" +
                "event:\n" +
                "  kind: emit-bare-scalar\n" +
                "mockRuntime:\n" +
                "  channels:\n" +
                "    - contract: /contracts/incoming\n" +
                "      calls:\n" +
                "        - when:\n" +
                "            event:\n" +
                "              kind: emit-bare-scalar\n" +
                "          accepted: true\n" +
                "          payload:\n" +
                "            kind: emit-bare-scalar\n" +
                "  handlers:\n" +
                "    - contract: /contracts/emitter\n" +
                "      calls:\n" +
                "        - when:\n" +
                "            channelKey: incoming\n" +
                "          result:\n" +
                "            triggeredEvents:\n" +
                "              - emitted-scalar\n" +
                "expectedStatus: success\n";
    }

    private BlueContractsConformanceReport reportWithFixtureIds(List<String> ids) {
        return new BlueContractsConformanceReport(
                "1.0",
                "sha256:test",
                ids,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyList());
    }

    private String failureMessage(BlueContractsConformanceFailure failure) {
        return failure.getFixtureId()
                + " [" + failure.getCategory() + "/" + failure.getOperation() + "] "
                + failure.getExceptionClass()
                + ": " + failure.getMessage();
    }
}
