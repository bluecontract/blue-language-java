package blue.buildlogic;

import blue.buildlogic.tasks.GenerateReleaseEvidenceTask;
import blue.buildlogic.tasks.VerifyInputIdentityTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

/** Adds generation and stale-input verification for deterministic release evidence. */
public final class ReleaseEvidencePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        ConfigurableFileTree sourceInputs = project.fileTree(project.getRootDir());
        sourceInputs.include("**/*");
        sourceInputs.exclude(
                "**/.git/**",
                "**/.gradle/**",
                "**/build/**",
                "**/.DS_Store",
                "**/._*",
                "**/*.jfr",
                "**/*.hprof",
                "**/*.heapdump",
                "**/*.db",
                "**/*.sqlite*",
                "**/node_modules/**",
                "**/__pycache__/**",
                "**/*.pyc",
                "**/*.pyo",
                "**/*.zip",
                "**/*.tar",
                "**/*.tar.gz",
                "**/*.tgz");

        Provider<String> gitCommit = project.getProviders()
                .environmentVariable("GIT_COMMIT")
                .orElse(project.getProviders().exec(spec -> {
                    spec.setWorkingDir(project.getRootDir());
                    spec.commandLine("git", "rev-parse", "--verify", "HEAD^{commit}");
                }).getStandardOutput().getAsText().map(String::trim));
        Provider<String> sourceDateEpoch = project.getProviders()
                .environmentVariable("SOURCE_DATE_EPOCH")
                .orElse("0");
        Provider<RegularFile> evidenceFile = project.getLayout().getBuildDirectory()
                .file("reports/release-evidence/source-input.json");

        project.getTasks().register("generateReleaseEvidence", GenerateReleaseEvidenceTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Generates deterministic source input release evidence.");
                    task.getSourceFiles().from(sourceInputs);
                    task.getSourceRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getSourceCommit().convention(gitCommit);
                    task.getSourceDateEpoch().convention(sourceDateEpoch);
                    task.getMetadata().put("projectPath", project.getPath());
                    task.getMetadata().put("projectVersion", project.provider(
                            () -> project.getVersion().toString()));
                    task.getOutputFile().set(evidenceFile);
                });

        project.getTasks().register("verifyReleaseEvidenceInputs", VerifyInputIdentityTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Fails when release evidence no longer matches source inputs.");
                    task.getSourceFiles().from(sourceInputs);
                    task.getSourceRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getEvidenceFile().set(evidenceFile);
                });
    }
}
