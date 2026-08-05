package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DocumentationQualityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldGenerateAllReferencesDeterministicallyWithCompleteStatusGuidance()
            throws Exception {
        // given
        List<Path> sources = Arrays.asList(
                write("module/src/main/java/blue/ProcessorStatus.java",
                        "package blue; public enum ProcessorStatus {\n"
                                + "    SUCCESS(\"success\"),\n"
                                + "    NO_MATCH(\"no-match\"),\n"
                                + "    STALE(\"stale\"),\n"
                                + "    TERMINATED(\"terminated\"),\n"
                                + "    INVALID_PROCESSING_DOCUMENT(\"invalid-processing-document\"),\n"
                                + "    CAPABILITY_FAILURE(\"capability-failure\"),\n"
                                + "    RUNTIME_FATAL(\"runtime-fatal\"),\n"
                                + "    GAS_LIMIT_EXCEEDED(\"gas-limit-exceeded\"),\n"
                                + "    PORTABLE_LIMIT_EXCEEDED(\"portable-limit-exceeded\"),\n"
                                + "    SUBSCRIPTION_SURFACE_INVALID(\"subscription-surface-invalid\");\n"
                                + "    private final String value; ProcessorStatus(String value) { this.value = value; }\n}\n"),
                write("module/src/main/java/blue/ProcessorErrorCategory.java",
                        "package blue; public enum ProcessorErrorCategory {\n"
                                + "    InvalidProcessingDocument,\n"
                                + "    SubscriptionSurfaceInvalid,\n"
                                + "    GasLimitExceeded\n}\n"),
                write("module/src/main/java/blue/ProcessorDiagnosticConstants.java",
                        "package blue; public final class ProcessorDiagnosticConstants {\n"
                                + "    public static final String FIELD_LIMIT = \"limit\";\n}\n"),
                write("module/src/main/java/blue/ProcessingMetricId.java",
                        "package blue; public enum ProcessingMetricId {\n"
                                + "    CALLS(\"calls\", ObservationKind.COUNTER_DELTA);\n"
                                + "    private static final int SENTINEL = 1;\n}\n"),
                write("module/src/main/java/blue/RuntimeProvider.java",
                        "package blue; public interface RuntimeProvider {}\n"));
        Path api = write("module/build/reports/api/current-api.txt",
                "# schema: blue-java-public-api/1.0\n# module: module\n# entryCount: 3\n"
                        + "type blue.RuntimeProvider access=public,interface super=java.lang.Object interfaces=- signature=-\n"
                        + "type blue.BlueRuntime access=public,final super=java.lang.Object interfaces=java.lang.AutoCloseable signature=-\n"
                        + "type blue.ProcessorRuntime access=public,abstract super=java.lang.Object interfaces=- signature=-\n");
        Path gas = write("gas.yaml", "schedule: contracts/1.0\nmaxProcessGas: 10\n"
                + "namespaces:\n  processor:\n    counterCount: 1\n    counters:\n"
                + "      call: 2\nportableLimits:\n  scopes: 3\n");
        Path conformance = write("release.json", "{\"schema\":\"blue-language-java-release-conformance-report/1.0\","
                + "\"release\":{\"name\":\"release\",\"packageIdentity\":\"sha256:"
                + repeat('a') + "\"},\"packages\":{},\"specifications\":{},\"fixtures\":[]}");
        Path modules = write("modules.json", "{\"schema\":\"blue-java-module-structure/1.0\","
                + "\"modules\":[\"module\"],\"observedEdges\":[],\"cycles\":[],"
                + "\"splitPackages\":[],\"undeclaredEdges\":[]}");

        // when
        Map<String, String> first = DocumentationReferences.render(
                temporaryDirectory, Collections.singletonList(api), sources, gas, conformance, modules);
        Map<String, String> second = DocumentationReferences.render(
                temporaryDirectory, Collections.singletonList(api), sources, gas, conformance, modules);

        // then
        assertEquals(first, second);
        assertEquals(Set.copyOf(DocumentationReferences.OUTPUT_PATHS), first.keySet());
        String statuses = first.get("reference/statuses-and-diagnostics.md");
        assertTrue(statuses.contains("debugging-and-diagnostics.md"));
        assertTrue(statuses.contains("PORTABLE_LIMIT_EXCEEDED"));
        assertTrue(statuses.contains("SUBSCRIPTION_SURFACE_INVALID"));
        assertTrue(statuses.contains("retrying identical input cannot change"));
        String runtimeSpi = first.get("reference/runtime-spi.md");
        assertTrue(runtimeSpi.contains("`blue.RuntimeProvider`"));
        assertTrue(runtimeSpi.contains("`blue.ProcessorRuntime`"));
        assertTrue(!runtimeSpi.contains("`blue.BlueRuntime`"));
    }

    @Test
    void shouldBindJavaFencesToCompiledExampleRegionsAndIgnoreSupportClasses()
            throws Exception {
        // given
        Path readme = write("README.md", "# Read me\n\n```java\nint stale = 1;\n```\n");
        Path api = write("module/src/main/java/blue/utils/ExampleApi.java",
                "package blue.utils;\npublic final class ExampleApi {}\n");
        Path support = write("examples/src/main/java/example/ExampleSupport.java",
                "package example; final class ExampleSupport {}\n");
        Path runnable = write("examples/src/main/java/example/RealExample.java",
                "package example; public final class RealExample {\n"
                        + "  public static Object run() { return null; }\n"
                        + "  public static void main(String[] args) { run(); }\n}\n");
        Path exampleTest = write("examples/src/test/java/example/RealExampleTest.java",
                "package example; final class RealExampleTest { Object value = RealExample.run(); }\n");
        Path languageSpec = write("language.md", "language\n");
        Path contractsSpec = write("contracts.md", "contracts\n");
        Path release = write("release.json", conformance(
                bare(languageSpec), bare(contractsSpec)));
        Path ledger = write("ledger.json", "{\"types\":[]}");

        // when
        Map<String, Object> report = DocumentationVerification.analyze(
                new DocumentationVerification.Inputs(
                        temporaryDirectory,
                        Collections.singletonList(readme),
                        temporaryDirectory.resolve("generated"),
                        Collections.singletonList(api),
                        Arrays.asList(support, runnable),
                        Collections.singletonList(exampleTest),
                        release,
                        languageSpec,
                        contractsSpec,
                        ledger,
                        0,
                        0));

        // then
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) report.get("violations");
        Set<Object> codes = violations.stream()
                .map(value -> value.get("code"))
                .collect(Collectors.toSet());
        assertTrue(codes.contains("UNBOUND_JAVA_SNIPPET"));
        assertTrue(codes.contains("MISSING_PACKAGE_INFO"));
        assertTrue(codes.contains("FORBIDDEN_PUBLIC_PACKAGE_NAME"));
        assertTrue(codes.contains("INSUFFICIENT_RUNNABLE_EXAMPLES"));
        @SuppressWarnings("unchecked")
        Map<String, Object> examples = (Map<String, Object>) report.get("examples");
        assertEquals(1, examples.get("sourceCount"));
    }

    @Test
    void shouldIgnoreRemovedApiNamesInsideGeneratedInventories() throws Exception {
        // given
        Path generated = write(
                "docs/reference/public-api.md",
                DocumentationReferences.MARKER + "\n\n`blue.removed.LegacyType`\n");
        Path languageSpec = write("language.md", "language\n");
        Path contractsSpec = write("contracts.md", "contracts\n");
        Path release = write("release.json", conformance(
                bare(languageSpec), bare(contractsSpec)));
        Path ledger = write(
                "ledger.json",
                "{\"types\":[{\"type\":\"blue.removed.LegacyType\","
                        + "\"classification\":\"internal-type-removed-from-public-surface\","
                        + "\"previousTypes\":[]}]}");

        // when
        Map<String, Object> report = DocumentationVerification.analyze(
                new DocumentationVerification.Inputs(
                        temporaryDirectory,
                        Collections.singletonList(generated),
                        temporaryDirectory.resolve("generated"),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        release,
                        languageSpec,
                        contractsSpec,
                        ledger,
                        0,
                        0));

        // then
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) report.get("violations");
        assertTrue(violations.stream().noneMatch(value ->
                "REMOVED_PUBLIC_API_REFERENCE".equals(value.get("code"))));
    }

    private String conformance(String languageHash, String contractsHash) {
        return "{\"schema\":\"blue-language-java-release-conformance-report/1.0\","
                + "\"packages\":{\"fixtures\":\"sha256:" + repeat('b') + "\"},"
                + "\"specifications\":{\"languageSha256\":\"" + languageHash
                + "\",\"contractsSha256\":\"" + contractsHash + "\"},"
                + "\"fixtures\":[]}";
    }

    private static String bare(Path file) {
        return DeterministicHashing.sha256(file).substring("sha256:".length());
    }

    private static String repeat(char value) {
        return String.valueOf(value).repeat(64);
    }

    private Path write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
