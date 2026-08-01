package blue.buildlogic;

import blue.buildlogic.tasks.VerifyReleaseEnvironmentTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.plugins.signing.SigningExtension;

/** Provides Maven/JReleaser publication conventions guarded by release validation. */
public final class JReleaserPublishingPlugin implements Plugin<Project> {

    private static final String CI_ENVIRONMENT_VARIABLE = "CI";
    private static final String MAVEN_JAVA_PUBLICATION = "mavenJava";
    private static final String STAGING_REPOSITORY_NAME = "staging";
    private static final String STAGING_REPOSITORY_DIRECTORY = "staging-deploy";
    private static final String JAVA_COMPONENT = "java";
    private static final String JAVA_LIBRARY_PLUGIN = "java-library";
    private static final String MAVEN_PUBLISH_PLUGIN = "maven-publish";
    private static final String SIGNING_PLUGIN = "signing";
    private static final String POM_DEFAULT_DESCRIPTION =
            "Java client library for Blue Language";
    private static final String PROJECT_URL = "https://timeline.blue";
    private static final String LICENSE_NAME = "MIT license";
    private static final String LICENSE_URL =
            "https://github.com/bluecontract/blue-language-java/blob/master/LICENSE";
    private static final String DEVELOPER_NAME = "Blue";
    private static final String DEVELOPER_EMAIL = "devsupport@timeline.blue";
    private static final String SCM_URL =
            "https://github.com/bluecontract/blue-language-java.git";
    private static final String SCM_CONNECTION =
            "scm:git:git@github.com:bluecontract/blue-language-java.git";
    private static final String JRELEASER_TASK_PREFIX = "jreleaser";
    private static final String PUBLISH_TASK_PREFIX = "publish";
    private static final String SIGN_TASK_PREFIX = "sign";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(MAVEN_PUBLISH_PLUGIN);
        project.getPluginManager().apply(SIGNING_PLUGIN);
        project.getPluginManager().apply("org.jreleaser");
        TaskProvider<VerifyReleaseEnvironmentTask> verification = project.getTasks().register(
                "verifyReleaseEnvironment", VerifyReleaseEnvironmentTask.class, task -> {
                    task.setGroup("verification");
                    task.setDescription("Validates release channel, version, and SOURCE_DATE_EPOCH.");
                    task.getVersionValue().convention(project.provider(
                            () -> project.getVersion().toString()));
                    task.getReleaseChannel().convention(project.getProviders()
                            .environmentVariable("BLUE_RELEASE_CHANNEL"));
                    task.getSourceDateEpoch().convention(project.getProviders()
                            .environmentVariable("SOURCE_DATE_EPOCH").orElse("0"));
                });
        project.getTasks().configureEach(task -> {
            if (isPublicationEntryPoint(task.getName())) {
                task.dependsOn(verification);
            }
        });
        project.getPluginManager().withPlugin(
                JAVA_LIBRARY_PLUGIN,
                ignored -> configureJavaLibraryPublication(project));
    }

    /** Creates one conventional publication without resolving credentials or contacting a server. */
    private static void configureJavaLibraryPublication(Project project) {
        PublishingExtension publishing =
                project.getExtensions().getByType(PublishingExtension.class);
        SoftwareComponent javaComponent = project.getComponents().getByName(JAVA_COMPONENT);
        MavenPublication publication = publishing.getPublications().maybeCreate(
                MAVEN_JAVA_PUBLICATION, MavenPublication.class);
        publication.setArtifactId(project.getName());
        publication.from(javaComponent);
        configurePom(project, publication);

        if (publishing.getRepositories().findByName(STAGING_REPOSITORY_NAME) == null) {
            publishing.getRepositories().maven(repository -> {
                repository.setName(STAGING_REPOSITORY_NAME);
                repository.setUrl(project.getLayout().getBuildDirectory()
                        .dir(STAGING_REPOSITORY_DIRECTORY));
            });
        }

        SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
        signing.setRequired(project.getProviders()
                .environmentVariable(CI_ENVIRONMENT_VARIABLE)
                .map(value -> !value.trim().isEmpty())
                .orElse(false));
        signing.sign(publication);
    }

    /** Supplies complete Maven Central metadata with late-bound project description support. */
    private static void configurePom(Project project, MavenPublication publication) {
        publication.getPom().getName().convention(project.provider(
                () -> displayName(project.getName())));
        publication.getPom().getDescription().convention(project.provider(() -> {
            String description = project.getDescription();
            return description == null || description.trim().isEmpty()
                    ? POM_DEFAULT_DESCRIPTION
                    : description;
        }));
        publication.getPom().getUrl().convention(PROJECT_URL);
        publication.getPom().licenses(licenses -> licenses.license(license -> {
            license.getName().set(LICENSE_NAME);
            license.getUrl().set(LICENSE_URL);
        }));
        publication.getPom().developers(developers -> developers.developer(developer -> {
            developer.getName().set(DEVELOPER_NAME);
            developer.getEmail().set(DEVELOPER_EMAIL);
        }));
        publication.getPom().scm(scm -> {
            scm.getUrl().set(SCM_URL);
            scm.getConnection().set(SCM_CONNECTION);
            scm.getDeveloperConnection().set(SCM_CONNECTION);
        });
    }

    private static boolean isPublicationEntryPoint(String taskName) {
        return taskName.startsWith(JRELEASER_TASK_PREFIX)
                || taskName.startsWith(PUBLISH_TASK_PREFIX)
                || taskName.startsWith(SIGN_TASK_PREFIX);
    }

    /** Turns a conventional artifact id into stable, readable POM display text. */
    private static String displayName(String projectName) {
        StringBuilder displayName = new StringBuilder();
        for (String word : projectName.split("-")) {
            if (word.isEmpty()) {
                continue;
            }
            if (displayName.length() > 0) {
                displayName.append(' ');
            }
            displayName.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1));
        }
        if (!projectName.endsWith("-java")) {
            displayName.append(" Java");
        }
        return displayName.append(" Library").toString();
    }
}
