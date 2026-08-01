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
    void shouldVerifyNoCoreProcessorManagedTypeUsesDisplayNameAsBlueId() throws IOException {
        // given
        List<String> offenders = new ArrayList<>();
        // when
        for (Path file : javaFiles(MAIN)) {
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
        for (Path file : javaFiles(MAIN)) {
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
        String source = read(Paths.get("src/main/java/blue/language/BlueContractsConformanceSuiteRunner.java"));

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
        String source = read(Paths.get("src/main/java/blue/language/BlueContractsConformanceSuiteRunner.java"));

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
        String source = read(Paths.get("src/main/java/blue/language/BlueContractsConformanceSuiteRunner.java"));

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
        String source = read(Paths.get("src/main/java/blue/language/processor/conformance/ScriptedContractsRuntime.java"));

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
        String source = read(Paths.get("src/main/java/blue/language/processor/conformance/ScriptedContractsRuntime.java"));

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
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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
