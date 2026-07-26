package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProcessorStaticSafetyTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final Path PROCESSOR_MAIN = Paths.get("src/main/java/blue/language/processor");
    @Test
    void noCoreProcessorManagedTypeUsesDisplayNameAsBlueId() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String source = read(file);
            if (source.contains("PROCESSOR_MANAGED_TYPE_BLUE_IDS")) {
                offenders.add(file + ": PROCESSOR_MANAGED_TYPE_BLUE_IDS");
            }
        }

        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    @Test
    void noRuntimeRegistryDummyNodeProviderInCorePath() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String source = read(file);
            if (source.contains("new Node().name(type.getSimpleName())")) {
                offenders.add(file + ": fabricated type node from Java simple name");
            }
        }

        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    @Test
    void runtimePointerComparisonsUsePointerUtils() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(PROCESSOR_MAIN)) {
            String relative = PROCESSOR_MAIN.relativize(file).toString();
            if (relative.equals("util/PointerUtils.java")
                    || relative.startsWith("conformance/")) {
                continue;
            }
            String source = read(file);
            if (source.contains(".startsWith(")) {
                offenders.add(file + ": raw startsWith pointer comparison");
            }
        }

        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    @Test
    void onlyAllowedDirectWriteCallSitesUseDirectWrite() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(PROCESSOR_MAIN)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.contains("directWrite(")) {
                    continue;
                }
                String relative = PROCESSOR_MAIN.relativize(file).toString();
                boolean allowed = relative.equals("CheckpointManager.java")
                        || relative.equals("TerminationService.java")
                        || relative.equals("ScopeExecutor.java")
                        || (relative.equals("DocumentProcessingRuntime.java") && line.contains("void directWrite("));
                if (!allowed) {
                    offenders.add(file + ":" + (i + 1) + ": " + line.trim());
                }
            }
        }

        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    @Test
    void initializationMarkerUsesTheNormativeDirectWrite() throws IOException {
        String source = read(PROCESSOR_MAIN.resolve("ScopeExecutor.java"));

        assertTrue(source.contains(
                "runtime.directWrite(pointer, marker.toNode())"));
    }

    @Test
    void checkpointAndTerminationUseDirectWrite() throws IOException {
        assertTrue(read(PROCESSOR_MAIN.resolve("CheckpointManager.java")).contains("runtime.directWrite("));
        assertTrue(read(PROCESSOR_MAIN.resolve("TerminationService.java")).contains("runtime.directWrite("));
    }

    @Test
    void contractsConformanceRunnerDoesNotNormalizeOfficialFixtureResults() throws IOException {
        String source = read(Paths.get("src/main/java/blue/language/BlueContractsConformanceSuiteRunner.java"));

        assertTrue(!source.contains("normalizeOfficialFixtureResult"));
        assertTrue(!source.contains("applyExpectedDocumentShape"));
        assertTrue(!source.contains("safeOfficialInitialDocument"));
        assertTrue(!source.contains("isOfficialProcessFixture"));
        assertTrue(!source.contains("forcedFatalResult"));
        assertTrue(!source.contains("preValidateProcessDocument"));
    }

    @Test
    void contractsConformanceRunnerDoesNotSynthesizeExpectedGasOrEvents() throws IOException {
        String source = read(Paths.get("src/main/java/blue/language/BlueContractsConformanceSuiteRunner.java"));

        assertTrue(!source.contains("expectedGas("));
        assertTrue(!source.contains("expectedRootEvents("));
    }

    @Test
    void contractsConformanceRunnerUsesTypedStatusAndErrorCategories() throws IOException {
        String source = read(Paths.get("src/main/java/blue/language/BlueContractsConformanceSuiteRunner.java"));

        assertTrue(!source.contains("actualStatus(JsonNode"));
        assertTrue(!source.contains("actualErrorCategory(JsonNode"));
        assertTrue(!source.contains("fixtureId.contains"));
        assertTrue(!source.contains("expectedStatus\")\n                &&"));
        assertTrue(!source.contains(
                "contracts/terminated/cause"));
    }

    @Test
    void batchPatchTransactionDoesNotDependOnScriptedContractsRuntime() throws IOException {
        String source = read(PROCESSOR_MAIN.resolve("BatchPatchTransaction.java"));

        assertTrue(!source.contains("ScriptedContractsRuntime"));
    }

    @Test
    void contractsConformanceRunnerDoesNotContainLegacyOrderLogTraceMethod() throws IOException {
        String source = read(Paths.get("src/main/java/blue/language/processor/conformance/ScriptedContractsRuntime.java"));

        assertTrue(!source.contains("appendOrderLog"));
    }

    @Test
    void dispatchSnapshotDoesNotSkipReplacedLaterHandler() throws IOException {
        String source = read(PROCESSOR_MAIN.resolve("ChannelRunner.java"));

        assertTrue(!source.contains("handlerWasReplaced"));
    }

    @Test
    void scriptedRuntimeDoesNotMutateDocumentForTraceCollection() throws IOException {
        String source = read(Paths.get("src/main/java/blue/language/processor/conformance/ScriptedContractsRuntime.java"));

        assertTrue(!source.contains("recordDocumentVisibleOrder"));
        assertTrue(!source.contains("/orderLog"));
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
