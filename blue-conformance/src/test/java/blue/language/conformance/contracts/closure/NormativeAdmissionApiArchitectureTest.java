package blue.language.conformance.contracts.closure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the normative fixture runners against the bounded admission API. */
final class NormativeAdmissionApiArchitectureTest {

    private static final Pattern BOUNDED_ADMISSION_CALL = Pattern.compile(
            "\\.admitClosure\\s*\\(");

    private static final List<String> NORMATIVE_RUNNER_SOURCES = Arrays.asList(
            "src/main/java/blue/language/conformance/contracts/closure/"
                    + "ClosureFixtureConformance.java",
            "src/test/java/blue/language/conformance/contracts/closure/"
                    + "FullClosureCorpusConformanceTest.java",
            "src/test/java/blue/language/conformance/contracts/closure/"
                    + "DynamicClosureCorpusConformanceTest.java",
            "src/test/java/blue/language/conformance/contracts/closure/"
                    + "AdmissionClosureFixtureExecutionTest.java",
            "src/test/java/blue/language/conformance/contracts/closure/"
                    + "Cclo32FinalizationRejectionPrefixTest.java");

    @Test
    void normativeAdmissionRunnersUseOnlyTheFullLifecycleApi()
            throws IOException {
        for (String relative : NORMATIVE_RUNNER_SOURCES) {
            Path sourcePath = modulePath(relative);
            String source = new String(
                    Files.readAllBytes(sourcePath),
                    StandardCharsets.UTF_8);

            assertTrue(source.contains(
                            ".admitClosureWithLifecycleQueue(input)"),
                    relative + " must invoke the normative admission API");
            assertFalse(BOUNDED_ADMISSION_CALL.matcher(source).find(),
                    relative + " must not invoke the bounded compatibility API");
        }
    }

    @Test
    void noConformanceSourceExecutesTheBoundedCompatibilityApi()
            throws IOException {
        List<String> boundedUses = new ArrayList<String>();
        for (String sourceRoot : Arrays.asList("src/main/java", "src/test/java")) {
            Path root = modulePath(sourceRoot);
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals(
                                "NormativeAdmissionApiArchitectureTest.java"))
                        .sorted()
                        .forEach(path -> collectBoundedUses(
                                root, path, boundedUses));
            }
        }
        assertTrue(boundedUses.isEmpty(),
                "Conformance sources must execute normative admission only: "
                        + boundedUses);
    }

    private static void collectBoundedUses(
            Path root,
            Path source,
            List<String> boundedUses) {
        final String text;
        try {
            text = new String(Files.readAllBytes(source),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Cannot read " + source, exception);
        }
        java.util.regex.Matcher matcher = BOUNDED_ADMISSION_CALL.matcher(text);
        while (matcher.find()) {
            String prefix = text.substring(0, matcher.start());
            if (prefix.endsWith("ClosureInvocationInput")
                    || prefix.endsWith("ClosureEvidenceFactory")) {
                continue;
            }
            long line = 1L + prefix.chars()
                    .filter(character -> character == '\n')
                    .count();
            boundedUses.add(root.relativize(source) + ":" + line);
        }
    }

    private static Path modulePath(String relative) {
        Path workingDirectory = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        Path direct = workingDirectory.resolve(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromRoot = workingDirectory.resolve("blue-conformance")
                .resolve(relative);
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new AssertionError(
                "Cannot locate blue-conformance source: " + relative);
    }
}
