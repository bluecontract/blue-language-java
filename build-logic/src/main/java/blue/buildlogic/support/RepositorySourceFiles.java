package blue.buildlogic.support;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;

/** Defines the repository files that can affect a clean build or public source release. */
public final class RepositorySourceFiles {

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
        "**/*.tar.gz",
        "**/*.tgz"
    };

    private RepositorySourceFiles() {}

    /** Returns a new file tree containing all build-relevant repository inputs. */
    public static ConfigurableFileTree create(Project project) {
        ConfigurableFileTree files = project.fileTree(project.getRootDir());
        files.include("**/*");
        files.exclude(LOCAL_AND_GENERATED_EXCLUDES);
        return files;
    }

    /** Returns build-relevant files except metadata regenerated inside the release archive. */
    public static ConfigurableFileTree createForSourceRelease(Project project) {
        ConfigurableFileTree files = create(project);
        files.exclude(".cz.toml");
        return files;
    }
}
