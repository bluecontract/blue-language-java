package blue.language.conformance.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Independent charge-point oracle for the ordinary C-EVT-COLLECTION-07 path. */
final class C07CollectionMeteringOracleTest {

    @Test
    void shouldDeriveEscapedCollectionPathChargesForEveryPhysicalVariant()
            throws IOException {
        // given
        ObjectNode fixture = fixtureWithoutMeterAssertions();
        /*
         * Delivery-snapshot revalidation reads the declared one-segment
         * collection path and the exact two-segment source path. Four
         * effective-surface recognition passes each validate both Collection
         * Channel headers. Event routing then tests both channel candidates.
         */
        long snapshotEntries = 2L;
        long snapshotSegments = 3L;
        long surfacePasses = 4L;
        long collectionChannels = 2L;
        long runtimeCandidates = 2L;
        long expectedEntries = snapshotEntries
                + surfacePasses * collectionChannels
                + runtimeCandidates;
        long expectedSegments = snapshotSegments
                + surfacePasses * collectionChannels
                + runtimeCandidates;

        // when
        ContractsConformanceProjection projection =
                new ContractsFixtureHarness().execute(fixture, false);
        Map<String, ChargeSummary> charges = new LinkedHashMap<>();
        for (Map.Entry<String, ContractsConformanceProjection> variant
                : projection.variants().entrySet()) {
            charges.put(variant.getKey(), summarize(variant.getValue()));
        }

        // then
        assertEquals(4, charges.size());
        for (ChargeSummary charge : charges.values()) {
            assertEquals(expectedEntries, charge.entryReads);
            assertEquals(expectedSegments, charge.segmentValidations);
            assertEquals(11L, charge.collectionPathEntryReads);
            assertEquals(11L, charge.collectionPathSegmentValidations);
            assertEquals(1L, charge.sourcePathEntryReads);
            assertEquals(2L, charge.sourcePathSegmentValidations);
        }
    }

    private ObjectNode fixtureWithoutMeterAssertions() throws IOException {
        InputStream stream = Objects.requireNonNull(
                getClass().getResourceAsStream(
                        "/blue-contracts-1.0/fixtures/evt/"
                                + "c-evt-collection-07.yaml"),
                "C-EVT-COLLECTION-07 fixture");
        try (InputStream input = stream) {
            ObjectNode fixture = (ObjectNode) new ObjectMapper(
                    new YAMLFactory()).readTree(input);
            ArrayNode assertions = ((ObjectNode) fixture.get("expected"))
                    .putArray("assertions");
            ObjectNode status = assertions.addObject();
            status.put("actual", "result.status");
            status.put("op", "equals");
            status.put("expected", "success");
            return fixture;
        }
    }

    private ChargeSummary summarize(ContractsConformanceProjection value) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) value.project(
                        "trace.namedEntries").getValue();
        ChargeSummary result = new ChargeSummary();
        for (Map<String, Object> entry : entries) {
            String counter = String.valueOf(entry.get("counter"));
            String logicalPath = String.valueOf(entry.get("logicalPath"));
            long quantity = ((Number) entry.get("quantity")).longValue();
            if ("embeddedPathEntryRead".equals(counter)) {
                result.entryReads += quantity;
                if ("/a~1b".equals(logicalPath)) {
                    result.collectionPathEntryReads += quantity;
                } else if ("/a~1b/a~0b".equals(logicalPath)) {
                    result.sourcePathEntryReads += quantity;
                }
            } else if ("embeddedPathSegmentValidated".equals(counter)) {
                result.segmentValidations += quantity;
                if ("/a~1b".equals(logicalPath)) {
                    result.collectionPathSegmentValidations += quantity;
                } else if ("/a~1b/a~0b".equals(logicalPath)) {
                    result.sourcePathSegmentValidations += quantity;
                }
            }
        }
        return result;
    }

    private static final class ChargeSummary {
        private long entryReads;
        private long segmentValidations;
        private long collectionPathEntryReads;
        private long collectionPathSegmentValidations;
        private long sourcePathEntryReads;
        private long sourcePathSegmentValidations;
    }
}
