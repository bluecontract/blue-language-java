package blue.language.conformance.api;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlueContractsPackageIntegrityTest {

    @Test
    void shouldFailClosedForMalformedOrEmptyInventory() {
        // given
        ObjectNode missing = JSON_MAPPER.createObjectNode();
        ObjectNode empty = JSON_MAPPER.createObjectNode();
        empty.putArray("files");

        // when
        Throwable missingFailure = captureFailure(
                () -> BlueContractsConformanceReport.loadFixtureInventory(
                        missing, ignored -> fixture("c-gas-01")));
        Throwable emptyFailure = captureFailure(
                () -> BlueContractsConformanceReport.loadFixtureInventory(
                        empty, ignored -> fixture("c-gas-01")));

        // then
        assertEquals(IllegalStateException.class,
                missingFailure.getClass());
        assertEquals(IllegalStateException.class,
                emptyFailure.getClass());
    }

    @Test
    void shouldFailClosedForDuplicateExecutablePathOrId() {
        // given
        ObjectNode duplicatePath = manifest(
                file("same.yaml", "behavior-fixture"),
                file("same.yaml", "gas-fixture"));
        ObjectNode duplicateId = manifest(
                file("one.yaml", "behavior-fixture"),
                file("two.yaml", "gas-fixture"));

        // when
        Throwable duplicatePathFailure = captureFailure(
                () -> BlueContractsConformanceReport.loadFixtureInventory(
                        duplicatePath, ignored -> fixture("c-gas-01")));
        Throwable duplicateIdFailure = captureFailure(
                () -> BlueContractsConformanceReport.loadFixtureInventory(
                        duplicateId, ignored -> fixture("c-gas-01")));

        // then
        assertEquals(IllegalStateException.class,
                duplicatePathFailure.getClass());
        assertEquals(IllegalStateException.class,
                duplicateIdFailure.getClass());
    }

    @Test
    void shouldRejectMissingMachineResult() {
        // given
        Map<String, BlueContractsFixtureCategory> categories =
                new LinkedHashMap<>();
        categories.put("one", BlueContractsFixtureCategory.GAS);
        categories.put("two", BlueContractsFixtureCategory.GAS);
        BlueContractsFixtureResult onlyOne =
                new BlueContractsFixtureResult(
                        "one",
                        "one.yaml",
                        "gas-fixture",
                        BlueContractsFixtureCategory.GAS,
                        "gas-micro",
                        Collections.singletonList("C-GAS-01"),
                        BlueContractsFixtureResult.Status.PASS,
                        null);

        // when
        Throwable failure = captureFailure(
                () -> new BlueContractsConformanceReport(
                        "1.0",
                        BlueContractsConformanceReport.RELEASE_NAME,
                        BlueContractsConformanceReport
                                .RELEASE_PACKAGE_IDENTITY,
                        BlueContractsConformanceReport
                                .LANGUAGE_REGISTRY_PACKAGE_IDENTITY,
                        BlueContractsConformanceReport
                                .LANGUAGE_FIXTURE_PACKAGE_IDENTITY,
                        BlueContractsConformanceReport
                                .CONTRACTS_REGISTRY_PACKAGE_IDENTITY,
                        BlueContractsConformanceReport
                                .CONTRACTS_GAS_PACKAGE_IDENTITY,
                        BlueContractsConformanceReport
                                .CONTRACTS_FIXTURE_PACKAGE_IDENTITY,
                        Arrays.asList("one", "two"),
                        Arrays.asList("one", "two"),
                        Collections.emptyList(),
                        categories,
                        Collections.emptyList(),
                        Collections.singletonList(onlyOne)));

        // then
        assertEquals(IllegalArgumentException.class,
                failure.getClass());
    }

    @Test
    void shouldRequireExactExecutableInventoryToBeNonVacuousAndUnique() {
        // given
        // The required fixture inventory is defined by the package report.

        // when
        int requiredCount =
                BlueContractsConformanceReport
                        .requiredFixtureIdsForContracts10().size();
        int uniqueCount =
                new java.util.LinkedHashSet<>(
                        BlueContractsConformanceReport
                                .requiredFixtureIdsForContracts10()).size();

        // then
        assertEquals(140, requiredCount);
        assertEquals(140, uniqueCount);
    }

    private static ObjectNode manifest(ObjectNode... files) {
        ObjectNode manifest = JSON_MAPPER.createObjectNode();
        ArrayNode list = manifest.putArray("files");
        for (ObjectNode file : files) {
            list.add(file);
        }
        return manifest;
    }

    private static ObjectNode file(String path, String role) {
        ObjectNode file = JSON_MAPPER.createObjectNode();
        file.put("path", path);
        file.put("role", role);
        return file;
    }

    private static JsonNode fixture(String id) {
        ObjectNode fixture = JSON_MAPPER.createObjectNode();
        fixture.put("id", id);
        fixture.put("category", "gas");
        fixture.put("operation", "gas-micro");
        fixture.putArray("vectors").add("C-GAS-01");
        return fixture;
    }
}
