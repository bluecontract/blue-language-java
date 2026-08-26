package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps bounded admission as one explicit compatibility proof only. */
final class BoundedAdmissionApiArchitectureTest {

    private static final Pattern ADMISSION_CALL = Pattern.compile(
            "\\.admitClosure\\s*\\(");

    @Test
    void onlyRequirement17ExecutesTheBoundedCompatibilityApi()
            throws IOException {
        Path testRoot = testSourceRoot();
        List<BoundedUse> boundedUses = new ArrayList<BoundedUse>();
        try (Stream<Path> paths = Files.walk(testRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals(
                            "BoundedAdmissionApiArchitectureTest.java"))
                    .sorted()
                    .forEach(path -> collectBoundedUses(
                            testRoot, path, boundedUses));
        }

        assertEquals(1, boundedUses.size(), boundedUses.toString());
        BoundedUse boundedUse = boundedUses.get(0);
        assertEquals(
                "blue/language/processor/closure/"
                        + "FullLifecycleAdmissionTest.java",
                boundedUse.source);

        String fullLifecycleSource = new String(Files.readAllBytes(
                testRoot.resolve(boundedUse.source)),
                StandardCharsets.UTF_8);
        int requirement = fullLifecycleSource.indexOf(
                "void requirement17ExistingAdmitClosureRemainsExplicitlyBounded()");
        assertTrue(requirement >= 0, "Requirement 17 compatibility test is absent");
        int nextTest = fullLifecycleSource.indexOf("@Test", requirement + 1);
        assertTrue(boundedUse.offset > requirement,
                "Bounded execution must remain inside Requirement 17");
        assertTrue(nextTest < 0 || boundedUse.offset < nextTest,
                "Bounded execution must remain inside Requirement 17");
    }

    private static void collectBoundedUses(
            Path testRoot,
            Path source,
            List<BoundedUse> boundedUses) {
        final String text;
        try {
            text = new String(Files.readAllBytes(source),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Cannot read " + source, exception);
        }
        Matcher matcher = ADMISSION_CALL.matcher(text);
        while (matcher.find()) {
            String prefix = text.substring(0, matcher.start());
            if (prefix.endsWith("ClosureInvocationInput")
                    || prefix.endsWith("ClosureEvidenceFactory")) {
                continue;
            }
            boundedUses.add(new BoundedUse(
                    testRoot.relativize(source).toString(), matcher.start()));
        }
    }

    private static Path testSourceRoot() {
        Path workingDirectory = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(workingDirectory.resolve("src/test/java"));
        candidates.add(workingDirectory.resolve(
                "blue-contracts-core/src/test/java"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Cannot locate blue-contracts-core tests");
    }

    private static final class BoundedUse {
        private final String source;
        private final int offset;

        private BoundedUse(String source, int offset) {
            this.source = source;
            this.offset = offset;
        }

        @Override
        public String toString() {
            return source + "@" + offset;
        }
    }
}
