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

    private static final Path CONTRACTS_CORE_MAIN_JAVA =
            moduleMainJava("blue-contracts-core");
    private static final Path PROCESSOR_MAIN = CONTRACTS_CORE_MAIN_JAVA.resolve(
            Paths.get("blue", "language", "processor"));
    private static final Path CONFORMANCE_MAIN_JAVA =
            moduleMainJava("blue-conformance");
    private static final Path CONTRACTS_CONFORMANCE_MAIN =
            CONFORMANCE_MAIN_JAVA.resolve(
                    Paths.get("blue", "language", "conformance", "contracts"));
    private static final Path CONTRACTS_CONFORMANCE_SUITE =
            CONTRACTS_CONFORMANCE_MAIN.resolve("ContractsConformanceSuite.java");
    private static final Path SCRIPTED_CONTRACTS_RUNTIME =
            CONTRACTS_CONFORMANCE_MAIN.resolve("ScriptedContractsRuntime.java");

    @Test
    void shouldVerifyNoCoreProcessorManagedTypeUsesDisplayNameAsBlueId() throws IOException {
        // given
        List<String> offenders = new ArrayList<>();
        // when
        for (Path file : javaFiles(CONTRACTS_CORE_MAIN_JAVA)) {
            String source = read(file);
            if (source.contains("PROCESSOR_MANAGED_TYPE_BLUE_IDS")) {
                offenders.add(file + ": PROCESSOR_MANAGED_TYPE_BLUE_IDS");
            }
        }

        // then
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    @Test
    void shouldVerifyNoRuntimeRegistryDummyNodeProviderInCorePath() throws IOException {
        // given
        List<String> offenders = new ArrayList<>();
        // when
        for (Path file : javaFiles(CONTRACTS_CORE_MAIN_JAVA)) {
            String source = read(file);
            if (source.contains("new Node().name(type.getSimpleName())")) {
                offenders.add(file + ": fabricated type node from Java simple name");
            }
        }

        // then
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    @Test
    void shouldVerifyRuntimePointerComparisonsUsePointerUtils() throws IOException {
        // given
        List<String> offenders = new ArrayList<>();
        // when
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

        // then
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    @Test
    void shouldVerifyOnlyAllowedDirectWriteCallSitesUseDirectWrite() throws IOException {
        // given
        List<String> offenders = new ArrayList<>();
        // when
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
                        || relative.equals("ScopeLifecycleExecutor.java")
                        || (relative.equals("DocumentProcessingRuntime.java") && line.contains("void directWrite("));
                if (!allowed) {
                    offenders.add(file + ":" + (i + 1) + ": " + line.trim());
                }
            }
        }

        // then
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    @Test
    void shouldVerifyInitializationMarkerUsesTheNormativeDirectWrite() throws IOException {
        // given
        String source = read(PROCESSOR_MAIN.resolve(
                "ScopeLifecycleExecutor.java"));

        // when
        boolean usesDirectWrite = source.contains(
                "runtime.directWrite(pointer, marker.toNode())");

        // then
        assertTrue(usesDirectWrite);
    }

    @Test
    void shouldVerifyCheckpointUsesDirectWrite() throws IOException {
        // given
        String source = read(
                PROCESSOR_MAIN.resolve("CheckpointManager.java"));

        // when
        boolean usesDirectWrite =
                source.contains("runtime.directWrite(");

        // then
        assertTrue(usesDirectWrite);
    }

    @Test
    void shouldVerifyTerminationUsesDirectWrite() throws IOException {
        // given
        String source = read(
                PROCESSOR_MAIN.resolve("TerminationService.java"));

        // when
        boolean usesDirectWrite =
                source.contains("runtime.directWrite(");

        // then
        assertTrue(usesDirectWrite);
    }

    @Test
    void shouldVerifyContractsConformanceRunnerDoesNotNormalizeOfficialFixtureResults() throws IOException {
        // given
        String source = read(CONTRACTS_CONFORMANCE_SUITE);

        // when
        List<String> offenders = presentFragments(
                source,
                "normalizeOfficialFixtureResult",
                "applyExpectedDocumentShape",
                "safeOfficialInitialDocument",
                "isOfficialProcessFixture",
                "forcedFatalResult",
                "preValidateProcessDocument");

        // then
        assertTrue(
                offenders.isEmpty(),
                () -> String.join("\n", offenders));
    }

    @Test
    void shouldVerifyContractsConformanceRunnerDoesNotSynthesizeExpectedGasOrEvents() throws IOException {
        // given
        String source = read(CONTRACTS_CONFORMANCE_SUITE);

        // when
        List<String> offenders = presentFragments(
                source,
                "expectedGas(",
                "expectedRootEvents(");

        // then
        assertTrue(
                offenders.isEmpty(),
                () -> String.join("\n", offenders));
    }

    @Test
    void shouldVerifyContractsConformanceRunnerUsesTypedStatusAndErrorCategories() throws IOException {
        // given
        String source = read(CONTRACTS_CONFORMANCE_SUITE);

        // when
        List<String> offenders = presentFragments(
                source,
                "actualStatus(JsonNode",
                "actualErrorCategory(JsonNode",
                "fixtureId.contains",
                "expectedStatus\")\n                &&",
                "contracts/terminated/cause");

        // then
        assertTrue(
                offenders.isEmpty(),
                () -> String.join("\n", offenders));
    }

    @Test
    void shouldVerifyBatchPatchTransactionDoesNotDependOnScriptedContractsRuntime() throws IOException {
        // given
        String source = read(PROCESSOR_MAIN.resolve("BatchPatchTransaction.java"));

        // when
        boolean runtimeIndependent =
                !source.contains("ScriptedContractsRuntime");

        // then
        assertTrue(runtimeIndependent);
    }

    @Test
    void shouldVerifyContractsConformanceRunnerDoesNotContainLegacyOrderLogTraceMethod() throws IOException {
        // given
        String source = read(SCRIPTED_CONTRACTS_RUNTIME);

        // when
        boolean legacyMethodAbsent =
                !source.contains("appendOrderLog");

        // then
        assertTrue(legacyMethodAbsent);
    }

    @Test
    void shouldVerifyDispatchSnapshotDoesNotSkipReplacedLaterHandler() throws IOException {
        // given
        String source = read(PROCESSOR_MAIN.resolve("ChannelRunner.java"));

        // when
        boolean replacementGuardAbsent =
                !source.contains("handlerWasReplaced");

        // then
        assertTrue(replacementGuardAbsent);
    }

    @Test
    void shouldVerifyScriptedRuntimeDoesNotMutateDocumentForTraceCollection() throws IOException {
        // given
        String source = read(SCRIPTED_CONTRACTS_RUNTIME);

        // when
        List<String> offenders = presentFragments(
                source,
                "recordDocumentVisibleOrder",
                "/orderLog");

        // then
        assertTrue(
                offenders.isEmpty(),
                () -> String.join("\n", offenders));
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Expected source directory is missing: " + root);
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> sources = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
            if (sources.isEmpty()) {
                throw new IOException("Expected Java sources under: " + root);
            }
            return sources;
        }
    }

    private static String read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Expected source file is missing: " + path);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path moduleMainJava(String moduleName) {
        return Paths.get(moduleName, "src", "main", "java");
    }

    private static List<String> presentFragments(
            String source,
            String... forbiddenFragments) {
        List<String> result = new ArrayList<>();
        for (String fragment : forbiddenFragments) {
            if (source.contains(fragment)) {
                result.add(fragment);
            }
        }
        return result;
    }
}
