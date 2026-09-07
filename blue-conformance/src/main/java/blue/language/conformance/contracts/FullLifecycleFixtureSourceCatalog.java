package blue.language.conformance.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.require;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.requiredObject;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.text;

/** Closed source-file catalog for the full-lifecycle fixture exporter. */
final class FullLifecycleFixtureSourceCatalog {

    private FullLifecycleFixtureSourceCatalog() {
    }

    static Map<String, String> expectedSourceIds() {
        LinkedHashMap<String, String> values =
                new LinkedHashMap<String, String>();
        values.put("fl-adm-01-root-patch-event.yaml",
                "fl-adm-01-root-patch-event");
        values.put("fl-adm-02-duplicate-equal-events.yaml",
                "fl-adm-02-duplicate-equal-events");
        values.put("fl-adm-03-non-public-containing-route.yaml",
                "fl-adm-03-non-public-containing-route");
        values.put("fl-adm-04-document-update-continuation.yaml",
                "fl-adm-04-document-update-continuation");
        values.put("fl-adm-05-graceful-termination.yaml",
                "fl-adm-05-graceful-termination");
        values.put("fl-adm-06-canonical-order-representation-parity.yaml",
                "fl-adm-06-order-representation");
        values.put("fl-adm-07-finite-cyclic-route.yaml",
                "fl-adm-07-finite-cyclic-route");
        values.put("fl-adm-08-infinite-cycle-gas-retry.yaml",
                "fl-adm-08-infinite-cycle-gas-retry");
        values.put("fl-adm-09-late-member-rollback.yaml",
                "fl-adm-09-late-member-rollback");
        values.put("fl-adm-10-unknown-occurrence.yaml",
                "fl-adm-10-unknown-occurrence");
        values.put("c-evo-18-missing-exact-node.yaml",
                "c-evo-18-missing-exact-node");
        values.put("c-evo-19-missing-occurrence-evidence.yaml",
                "c-evo-19-missing-occurrence-evidence");
        values.put("c-evo-20-canonical-demand-order.yaml",
                "c-evo-20-canonical-demand-order");
        values.put("c-evo-21-retry-determinism.yaml",
                "c-evo-21-retry-determinism");
        values.put("c-evo-22-low-gas-expanded-evidence.yaml",
                "c-evo-22-low-gas-expanded-evidence");
        values.put("c-evo-23-automatic-explicit-retry-parity.yaml",
                "c-evo-23-automatic-explicit-retry-parity");
        values.put("c-emb-empty-05-prospective-activation.yaml",
                "c-emb-empty-05-prospective-activation");
        values.put("c-evt-collection-07-closure-work-order.yaml",
                "c-evt-collection-07-closure-work-order");
        return Collections.unmodifiableMap(values);
    }

    static void validateSourceFamily(
            Path sourceFile,
            JsonNode value,
            Map<String, String> expectedSourceIds) {
        String fileName = sourceFile.getFileName().toString();
        String expectedId = expectedSourceIds.get(fileName);
        require(expectedId != null,
                "unexpected full-lifecycle source file " + fileName);
        ObjectNode source = requiredObject(value, "source");
        require(expectedId.equals(text(source, "id")),
                fileName + " must declare id " + expectedId);
        String expectedScenario;
        if (fileName.startsWith("c-evo-")) {
            expectedScenario = "C-EVO-" + fileName.substring(6, 8);
        } else if (fileName.startsWith("c-emb-empty-")) {
            expectedScenario = "C-EMB-EMPTY-"
                    + fileName.substring(12, 14);
        } else if (fileName.startsWith("c-evt-collection-")) {
            expectedScenario = "C-EVT-COLLECTION-"
                    + fileName.substring(17, 19);
        } else {
            expectedScenario = "FL-ADM-" + fileName.substring(7, 9);
        }
        require(expectedScenario.equals(text(source, "scenario")),
                fileName + " must declare scenario " + expectedScenario);
    }
}
