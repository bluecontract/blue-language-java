package blue.language;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueContractsPackageIntegrityTest {

    @Test
    void malformedOrEmptyInventoryFailsClosed() {
        ObjectNode missing = JSON_MAPPER.createObjectNode();
        assertThrows(IllegalStateException.class,
                () -> BlueContractsConformanceReport.loadFixtureInventory(
                        missing, ignored -> fixture("c-gas-01")));

        ObjectNode empty = JSON_MAPPER.createObjectNode();
        empty.putArray("files");
        assertThrows(IllegalStateException.class,
                () -> BlueContractsConformanceReport.loadFixtureInventory(
                        empty, ignored -> fixture("c-gas-01")));
    }

    @Test
    void duplicateExecutablePathOrIdFailsClosed() {
        ObjectNode duplicatePath = manifest(
                file("same.yaml", "behavior-fixture"),
                file("same.yaml", "gas-fixture"));
        assertThrows(IllegalStateException.class,
                () -> BlueContractsConformanceReport.loadFixtureInventory(
                        duplicatePath, ignored -> fixture("c-gas-01")));

        ObjectNode duplicateId = manifest(
                file("one.yaml", "behavior-fixture"),
                file("two.yaml", "gas-fixture"));
        assertThrows(IllegalStateException.class,
                () -> BlueContractsConformanceReport.loadFixtureInventory(
                        duplicateId, ignored -> fixture("c-gas-01")));
    }

    @Test
    void missingMachineResultIsRejected() {
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

        assertThrows(IllegalArgumentException.class,
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
    }

    @Test
    void exactExecutableInventoryIsNonVacuousAndUnique() {
        assertEquals(127,
                BlueContractsConformanceReport
                        .requiredFixtureIdsForContracts10().size());
        assertEquals(127,
                new java.util.LinkedHashSet<>(
                        BlueContractsConformanceReport
                                .requiredFixtureIdsForContracts10()).size());
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
