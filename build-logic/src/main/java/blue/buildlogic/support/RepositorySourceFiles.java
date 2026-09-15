package blue.buildlogic.support;

import java.util.Locale;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;

/** Defines the repository files that can affect a clean build or public source release. */
public final class RepositorySourceFiles {

    private static final Set<String> REQUIRED_VERIFICATION_ARCHIVES = Set.of(
            "blue-conformance/src/main/tools/migration/classify-retired-slot-reviewed-after.tar.gz",
            "blue-conformance/src/main/tools/migration/classify-legal-detached-retarget-reviewed-after.tar.gz",
            "blue-conformance/src/main/tools/migration/classify-baseline-reconciliation-reviewed-inputs.tar.gz");

    private static final String[] LOCAL_AND_GENERATED_EXCLUDES = {
        "**/.git/**",
        "**/.gradle/**",
        "**/.idea/**",
        "**/.vscode/**",
        "**/.fleet/**",
        "**/.agents/**",
        "**/.codex/**",
        "**/.jqwik-database/**",
        "**/.DS_Store",
        "**/._*",
        "**/build/**",
        "**/out/**",
        "**/target/**",
        "**/node_modules/**",
        "**/__pycache__/**",
        "**/*.jfr",
        "**/*.hprof",
        "**/*.heapdump",
        "**/*.db",
        "**/*.sqlite*",
        "**/*.pyc",
        "**/*.pyo",
        "**/*.zip",
        "**/*.tar",
        "**/*.tgz"
    };

    private RepositorySourceFiles() {}

    /** Returns a new file tree containing all build-relevant repository inputs. */
    public static ConfigurableFileTree create(Project project) {
        ConfigurableFileTree files = project.fileTree(project.getRootDir());
        files.include("**/*");
        files.exclude(LOCAL_AND_GENERATED_EXCLUDES);
        files.exclude(element -> {
            String path = element.getRelativePath().getPathString();
            return path.toLowerCase(Locale.ROOT).endsWith(".tar.gz")
                    && (element.isDirectory() || !isRequiredVerificationArchive(path));
        });
        return files;
    }

    /** Exact committed byte images needed by mandatory transition verification. */
    static boolean isRequiredVerificationArchive(String relativePath) {
        return REQUIRED_VERIFICATION_ARCHIVES.contains(relativePath);
    }

    /** Returns build-relevant files except metadata regenerated inside the release archive. */
    public static ConfigurableFileTree createForSourceRelease(Project project) {
        ConfigurableFileTree files = create(project);
        files.exclude(".cz.toml");
        return files;
    }
}
