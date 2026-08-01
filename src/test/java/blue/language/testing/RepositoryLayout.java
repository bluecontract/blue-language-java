package blue.language.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Defines the checked-in repository layout used by source-level tests.
 *
 * <p>The production modules are deliberately listed rather than discovered
 * from the file system. This keeps architecture checks deterministic and
 * makes adding or removing a production module an explicit test change.</p>
 */
public final class RepositoryLayout {

    private static final Path REPOSITORY_ROOT = checkedRepositoryRoot();
    private static final List<String> PRODUCTION_MODULES =
            Collections.unmodifiableList(Arrays.asList(
                    "blue-language-model",
                    "blue-language-core",
                    "blue-language-mapping",
                    "blue-language-ipfs",
                    "blue-contracts-core",
                    "blue-conformance",
                    "blue-language-java",
                    "examples"));
    private static final List<Path> PRODUCTION_JAVA_ROOTS =
            conventionalRoots("src/main/java");
    private static final List<Path> PRODUCTION_RESOURCE_ROOTS =
            conventionalRoots("src/main/resources");
    private static final List<Path> BENCHMARK_JAVA_ROOTS =
            benchmarkJavaRootsInLayout();

    private RepositoryLayout() {
    }

    /** Returns the explicit root of the repository under test. */
    public static Path repositoryRoot() {
        return REPOSITORY_ROOT;
    }

    /** Returns existing conventional Java roots in declared module order. */
    public static List<Path> productionJavaRoots() {
        return PRODUCTION_JAVA_ROOTS;
    }

    /** Returns existing conventional resource roots in declared module order. */
    public static List<Path> productionResourceRoots() {
        return PRODUCTION_RESOURCE_ROOTS;
    }

    /** Returns existing conventional JMH roots in repository order. */
    public static List<Path> benchmarkJavaRoots() {
        return BENCHMARK_JAVA_ROOTS;
    }

    /** Returns a module's conventional production Java root. */
    public static Path productionJavaRoot(String module) {
        requireProductionModule(module);
        return REPOSITORY_ROOT.resolve(module).resolve("src/main/java");
    }

    /** Returns a module's conventional production resource root. */
    public static Path productionResourceRoot(String module) {
        requireProductionModule(module);
        return REPOSITORY_ROOT.resolve(module).resolve("src/main/resources");
    }

    private static List<Path> conventionalRoots(String relativeRoot) {
        List<Path> roots = new ArrayList<>();
        for (String module : PRODUCTION_MODULES) {
            Path root = REPOSITORY_ROOT.resolve(module).resolve(relativeRoot);
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        return Collections.unmodifiableList(roots);
    }

    private static List<Path> benchmarkJavaRootsInLayout() {
        List<Path> roots = new ArrayList<>();
        Path legacyRoot = REPOSITORY_ROOT.resolve("src/jmh/java");
        if (Files.isDirectory(legacyRoot)) {
            roots.add(legacyRoot);
        }
        for (String module : PRODUCTION_MODULES) {
            Path root = REPOSITORY_ROOT.resolve(module)
                    .resolve("src/jmh/java");
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        return Collections.unmodifiableList(roots);
    }

    private static Path checkedRepositoryRoot() {
        Path root = Paths.get("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            throw new IllegalStateException(
                    "Tests must run from the blue-language-java repository root: "
                            + root);
        }
        return root;
    }

    private static void requireProductionModule(String module) {
        if (!PRODUCTION_MODULES.contains(module)) {
            throw new IllegalArgumentException(
                    "Unknown production module: " + module);
        }
    }
}
