package blue.language.runtime;

import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.codec.BlueFormat;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executable identity inventory, also runnable unchanged against earlier commits. */
final class ContextualReferenceIdentityMatrixTest {

    @Test
    void verifyIndependentCanonicalOraclesAndWriteIdentityInventory() throws Exception {
        JsonNode corpus;
        byte[] corpusBytes;
        try (InputStream input = getClass().getResourceAsStream(
                "/identity/contextual-reference-matrix.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            corpusBytes = bytes.toByteArray();
            corpus = UncheckedObjectMapper.JSON_MAPPER.readTree(corpusBytes);
        }
        List<JsonNode> cases = new ArrayList<>();
        corpus.get("cases").forEach(cases::add);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String mode : new String[] {"cold", "warm-forward", "warm-reverse", "pre-resolved"}) {
            List<JsonNode> ordered = new ArrayList<>(cases);
            if (mode.equals("warm-reverse")) Collections.reverse(ordered);
            if (mode.equals("cold") || mode.equals("pre-resolved")) {
                for (JsonNode fixture : ordered) {
                    run(corpus, Collections.singletonList(fixture), mode, rows, failures);
                }
            } else {
                run(corpus, ordered, mode, rows, failures);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        String revision = System.getenv("BLUE_IDENTITY_MATRIX_REVISION");
        report.put("sourceRevision", revision == null ? "working-tree" : revision);
        report.put("corpusSha256", sha256(corpusBytes));
        report.put("harnessSha256", sha256(Files.readAllBytes(Paths.get(
                "src/test/java/blue/language/runtime/ContextualReferenceIdentityMatrixTest.java"))));
        report.put("oracle", corpus.get("oracle").asText());
        report.put("provider", corpus.get("provider"));
        report.put("rows", rows);
        report.put("oracleMismatches", failures);
        String output = System.getenv("BLUE_IDENTITY_MATRIX_OUTPUT");
        Path receipt = Paths.get(output == null
                ? "build/reports/identity/contextual-reference-matrix.json" : output);
        Files.createDirectories(receipt.getParent());
        UncheckedObjectMapper.JSON_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(receipt.toFile(), report);
        // Historical runs report their differences without changing the oracle.
        if (!"1".equals(System.getenv("BLUE_IDENTITY_MATRIX_REPORT_ONLY"))) {
            assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void run(JsonNode corpus, List<JsonNode> cases, String mode,
                            List<Map<String, Object>> rows, List<String> failures) {
        Map<String, Node> exact = new LinkedHashMap<>();
        List<String> reads = new ArrayList<>();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
            reads.add(id);
            Node value = exact.get(id);
            return value == null ? Collections.emptyList() : Collections.singletonList(value.clone());
        }).build()) {
            for (JsonNode entry : corpus.get("provider")) {
                Node node = language.codec().parseBlueIdInput(entry.get("node").toString(), BlueFormat.JSON);
                String id = entry.get("requestedBlueId").asText();
                if (!id.equals(language.identity().directBlueId(node))) {
                    throw new IllegalStateException("Independent provider oracle mismatch: " + id);
                }
                exact.put(id, node);
            }
            for (JsonNode fixture : cases) {
                String name = fixture.get("name").asText();
                Node source = language.codec().parseSource(fixture.get("source").toString(), BlueFormat.JSON);
                if (mode.equals("pre-resolved")) {
                    try {
                        language.resolution().resolve(source);
                    } catch (RuntimeException expectedInvalidInstance) {
                        // Definition identity is still independently established below.
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("case", name);
                row.put("mode", mode);
                row.put("exactInput", NodeWireForm.get(language.preprocessing().preprocess(source)));
                String directId = language.identity().directBlueId(language.preprocessing().preprocess(source));
                row.put("directBlueId", directId);
                if (!fixture.get("expectedDirectBlueId").asText().equals(directId)) {
                    failures.add(mode + "/" + name + ": direct identity changed");
                }
                reads.clear();
                try {
                    Node canonical = language.identity().canonicalIdentityInput(source);
                    String sourceId = language.identity().sourceDocumentBlueId(source);
                    if (!sourceId.equals(language.identity().directBlueId(canonical))) {
                        failures.add(mode + "/" + name + ": Source ID does not hash canonical input");
                    }
                    row.put("canonicalInput", NodeWireForm.get(canonical));
                    row.put("sourceBlueId", sourceId);
                    row.put("canonicalProviderReads", new ArrayList<>(reads));
                    if (fixture.has("expectedErrorCategory")) {
                        failures.add(mode + "/" + name + ": accepted invalid contribution");
                    } else if (!fixture.get("expectedSourceBlueId").asText().equals(sourceId)) {
                        failures.add(mode + "/" + name + ": expected "
                                + fixture.get("expectedSourceBlueId").asText() + ", got " + sourceId);
                    }
                } catch (RuntimeException error) {
                    String category = BlueLanguageErrorClassifier.classify(error).name();
                    row.put("sourceError", category);
                    row.put("canonicalProviderReads", new ArrayList<>(reads));
                    if (!fixture.has("expectedErrorCategory")
                            || !fixture.get("expectedErrorCategory").asText().equals(category)) {
                        failures.add(mode + "/" + name + ": unexpected " + category + ": " + error.getMessage());
                    }
                }
                reads.clear();
                try {
                    Node resolved = language.resolution().resolve(source);
                    row.put("resolved", NodeWireForm.get(resolved));
                    if (fixture.has("expectedResolutionErrorCategory")) {
                        failures.add(mode + "/" + name + ": resolution accepted invalid contribution");
                    }
                    if (fixture.has("expectedResolvedValues")) {
                        fixture.get("expectedResolvedValues").fields().forEachRemaining(entry -> {
                            if (!entry.getValue().asText().equals(String.valueOf(resolved.get(entry.getKey())))) {
                                failures.add(mode + "/" + name + ": lost resolved value/type at " + entry.getKey());
                            }
                        });
                    }
                } catch (RuntimeException error) {
                    String category = BlueLanguageErrorClassifier.classify(error).name();
                    row.put("resolutionError", category);
                    if (fixture.has("expectedResolutionErrorCategory")
                            && !fixture.get("expectedResolutionErrorCategory").asText().equals(category)) {
                        failures.add(mode + "/" + name + ": unexpected resolution error " + category);
                    }
                    if (fixture.has("expectedResolvedValues")) {
                        failures.add(mode + "/" + name + ": invalid completed value: " + error.getMessage());
                    }
                }
                row.put("resolutionProviderReads", new ArrayList<>(reads));
                rows.add(row);
            }
        }
    }
}
