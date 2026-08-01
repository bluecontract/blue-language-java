package blue.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.buildlogic.tasks.CompareApiBaselineTask;
import blue.buildlogic.tasks.CompareArchiveReplicasTask;
import blue.buildlogic.tasks.GenerateAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.GenerateFileIdentityTask;
import blue.buildlogic.tasks.GenerateJavaApiInventoryTask;
import blue.buildlogic.tasks.GenerateJavaModuleInventoryTask;
import blue.buildlogic.tasks.GenerateReleaseEvidenceTask;
import blue.buildlogic.tasks.VerifyAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.VerifyInputIdentityTask;
import blue.buildlogic.tasks.VerifyJavaPackageCyclesTask;
import blue.buildlogic.tasks.VerifyJavaModuleStructureTask;
import blue.buildlogic.tasks.VerifyReleaseEnvironmentTask;
import blue.buildlogic.tasks.VerifyReproducibleArchivesTask;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConventionPluginsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldConfigureJavaEightAndReproducibleArchives() {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();

        // when
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);
        project.getPluginManager().apply(ReproducibleArchivesPlugin.class);

        // then
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        JavaCompile compileJava = (JavaCompile) project.getTasks().getByName("compileJava");
        Jar jar = (Jar) project.getTasks().getByName("jar");
        assertEquals(JavaVersion.VERSION_1_8, java.getSourceCompatibility());
        assertEquals(8, compileJava.getOptions().getRelease().get());
        assertFalse(jar.isPreserveFileTimestamps());
        assertTrue(jar.isReproducibleFileOrder());
        assertTrue(project.getTasks().getByName("verifyReproducibleArchives")
                instanceof VerifyReproducibleArchivesTask);
        assertTrue(project.getTasks().getByName("compareArchiveReplicas")
                instanceof CompareArchiveReplicasTask);
        assertTrue(project.getTasks().getByName("generateModuleStructureInventory")
                instanceof GenerateJavaModuleInventoryTask);
        assertTrue(project.getTasks().getByName("verifyJavaPackageCycles")
                instanceof VerifyJavaPackageCyclesTask);
        assertTrue(project.getTasks().getByName("check")
                .getTaskDependencies()
                .getDependencies(null)
                .stream()
                .anyMatch(task -> task.getName().equals("verifyJavaPackageCycles")));
    }

    @Test
    void shouldRegisterTypedVerificationTasksWithoutExecutingThem() {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();

        // when
        project.getPluginManager().apply(ApiBaselinePlugin.class);
        project.getPluginManager().apply(ConformancePackagePlugin.class);
        project.getPluginManager().apply(ReleaseEvidencePlugin.class);

        // then
        assertTrue(project.getTasks().getByName("apiBaselineDiff")
                instanceof CompareApiBaselineTask);
        assertTrue(project.getTasks().getByName("generatePublicApiInventory")
                instanceof GenerateJavaApiInventoryTask);
        assertTrue(project.getTasks().getByName("generatePublicApiUnion")
                instanceof GenerateJavaApiInventoryTask);
        assertTrue(project.getTasks().getByName("generateConformancePackageIdentity")
                instanceof GenerateFileIdentityTask);
        assertTrue(project.getTasks().getByName("generateReleaseEvidence")
                instanceof GenerateReleaseEvidenceTask);
        assertTrue(project.getTasks().getByName("verifyReleaseEvidenceInputs")
                instanceof VerifyInputIdentityTask);
        assertTrue(project.getTasks().getByName("generateAggregateReleaseReceipt")
                instanceof GenerateAggregateReleaseReceiptTask);
        assertTrue(project.getTasks().getByName("verifyAggregateReleaseReceipt")
                instanceof VerifyAggregateReleaseReceiptTask);
        assertTrue(project.getTasks().getByName("verifyModuleStructure")
                instanceof VerifyJavaModuleStructureTask);
        assertNotNull(project.getTasks().getByName("generateReleaseEvidence")
                .getGroup());
    }

    @Test
    void shouldApplyThePinnedJmhPluginThroughItsConvention() throws Exception {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(Files.createDirectories(
                        temporaryDirectory.resolve("jmh-project")).toFile())
                .build();

        // when
        project.getPluginManager().apply(JmhConventionsPlugin.class);

        // then
        assertTrue(project.getPluginManager().hasPlugin("me.champeau.jmh"));
        assertNotNull(project.getTasks().findByName("jmh"));
    }

    @Test
    void shouldGuardJreleaserTasksWithTypedEnvironmentValidation() throws Exception {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(Files.createDirectories(
                        temporaryDirectory.resolve("jreleaser-project")).toFile())
                .build();
        project.setVersion("1.0.0");

        // when
        project.getPluginManager().apply(JReleaserPublishingPlugin.class);

        // then
        assertTrue(project.getPluginManager().hasPlugin("org.jreleaser"));
        assertTrue(project.getTasks().getByName("verifyReleaseEnvironment")
                instanceof VerifyReleaseEnvironmentTask);
        assertTrue(project.getTasks().getByName("jreleaserConfig")
                .getTaskDependencies()
                .getDependencies(null)
                .stream()
                .anyMatch(task -> task.getName().equals("verifyReleaseEnvironment")));
    }
}
