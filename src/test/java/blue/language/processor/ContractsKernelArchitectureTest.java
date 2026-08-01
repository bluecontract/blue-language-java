package blue.language.processor;

import blue.language.testing.RepositoryLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level guards for the final generic Contracts composition. */
final class ContractsKernelArchitectureTest {

    private static final Path PROCESSOR_SOURCE =
            RepositoryLayout.productionJavaRoot("blue-contracts-core")
                    .resolve("blue/language/processor");
    private static final int MAX_IMPLEMENTATION_LINES = 800;
    private static final int MAX_COMPOSITION_ROOT_LINES = 250;
    private static final int MAX_PUBLIC_SERVICE_METHODS = 30;

    @Test
    void shouldKeepContractsImplementationClassesWithinBudget()
            throws IOException {
        // given
        List<String> oversized = new ArrayList<>();

        // when
        for (Path source : directProcessorSources()) {
            long lines;
            try (Stream<String> content = Files.lines(
                    source, StandardCharsets.UTF_8)) {
                lines = content.count();
            }
            if (lines > MAX_IMPLEMENTATION_LINES) {
                oversized.add(source.getFileName() + "=" + lines);
            }
        }

        // then
        assertTrue(oversized.isEmpty(),
                "Contracts implementation sources exceed "
                        + MAX_IMPLEMENTATION_LINES + " lines: " + oversized);
    }

    @Test
    void shouldKeepEngineCompositionTypesOutOfPublicApi()
            throws IOException {
        // given
        List<String> internalTypes = Arrays.asList(
                "ProcessorEngine",
                "ProcessorInvocationState",
                "DocumentProcessingRuntime",
                "ContractLoader",
                "ScopeExecutor",
                "ChannelRunner",
                "ProcessingSession",
                "ProcessingPhasePipeline");
        List<String> leaks = new ArrayList<>();

        // when
        for (String type : internalTypes) {
            Path source = PROCESSOR_SOURCE.resolve(type + ".java");
            String code = new String(Files.readAllBytes(source),
                    StandardCharsets.UTF_8);
            if (code.matches("(?s).*\\bpublic\\s+(?:final\\s+)?class\\s+"
                    + type + "\\b.*")) {
                leaks.add(type);
            }
        }

        // then
        assertTrue(leaks.isEmpty(),
                "Engine composition types leaked into public API: " + leaks);
    }

    @Test
    void shouldKeepProcessorEngineAsShortCompositionRoot()
            throws IOException {
        // given
        Path engineSource = PROCESSOR_SOURCE.resolve(
                "ProcessorEngine.java");

        // when
        long lineCount;
        try (Stream<String> lines = Files.lines(
                engineSource, StandardCharsets.UTF_8)) {
            lineCount = lines.count();
        }

        // then
        assertTrue(lineCount <= MAX_COMPOSITION_ROOT_LINES,
                "ProcessorEngine has " + lineCount
                        + " lines; composition-root budget is "
                        + MAX_COMPOSITION_ROOT_LINES);
    }

    @Test
    void shouldUseOnlyTypedObserverInContractsProductionCode()
            throws IOException {
        // given
        Path legacySink = PROCESSOR_SOURCE.resolve(
                "Processing" + "MetricsSink.java");
        List<String> references = new ArrayList<>();

        // when
        for (Path source : directProcessorSources()) {
            String code = new String(Files.readAllBytes(source),
                    StandardCharsets.UTF_8);
            if (code.contains("Processing" + "MetricsSink")) {
                references.add(source.getFileName().toString());
            }
        }

        // then
        assertFalse(Files.exists(legacySink),
                "The 178-method legacy metrics interface must be removed");
        assertTrue(references.isEmpty(),
                "Contracts core still references the legacy metrics sink: "
                        + references);
    }

    @Test
    void shouldKeepHandlerExecutionContextWithinPublicServiceBudget() {
        // given
        Class<ProcessorExecutionContext> publicService =
                ProcessorExecutionContext.class;

        // when
        long publicMethodCount = Arrays.stream(
                        publicService.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(
                        method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .count();
        boolean withinBudget =
                publicMethodCount <= MAX_PUBLIC_SERVICE_METHODS;

        // then
        assertTrue(withinBudget,
                "ProcessorExecutionContext exposes "
                        + publicMethodCount
                        + " public methods; budget is "
                        + MAX_PUBLIC_SERVICE_METHODS);
    }

    private static List<Path> directProcessorSources() throws IOException {
        try (Stream<Path> sources = Files.list(PROCESSOR_SOURCE)) {
            return sources
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
