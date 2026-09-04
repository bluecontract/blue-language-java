package blue.buildlogic.tasks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.work.DisableCachingByDefault;

/** Bounded development tests whose results cannot be mistaken for release evidence. */
@DisableCachingByDefault(because = "Arbitrary selected tests may consume external state")
public abstract class DevelopmentTest extends Test {

    private final List<String> inventory = new ArrayList<>();

    @Input
    public abstract Property<Boolean> getSelectionRequired();

    @Input
    public abstract ListProperty<String> getCommandLineSelection();

    @Override
    public Test setTestNameIncludePatterns(List<String> patterns) {
        getCommandLineSelection().set(patterns);
        return super.setTestNameIncludePatterns(patterns);
    }

    @OutputFile
    public abstract RegularFileProperty getInventoryFile();

    public DevelopmentTest() {
        getSelectionRequired().convention(false);
        getCommandLineSelection().convention(java.util.Collections.emptyList());
        var directory = getProject().getLayout().getBuildDirectory()
                .dir("development-verification/" + getName());
        getInventoryFile().convention(directory.map(value -> value.file("inventory.txt")));
        getReports().getJunitXml().getOutputLocation().set(directory.map(value -> value.dir("xml")));
        getReports().getHtml().getOutputLocation().set(directory.map(value -> value.dir("html")));
        getBinaryResultsDirectory().set(directory.map(value -> value.dir("binary")));
        getReports().getJunitXml().getRequired().set(true);
        getReports().getHtml().getRequired().set(true);
        getFilter().setFailOnNoMatchingTests(true);
        getFailOnNoDiscoveredTests().set(true);
        setMaxParallelForks(1);
        setMaxHeapSize("512m");
        setForkEvery(0);
        useJUnitPlatform();
        systemProperty("junit.jupiter.execution.parallel.enabled", "false");
        // Opt-in --build-cache must not restore results of unaudited arbitrary tests.
        getOutputs().doNotCacheIf("Development test external-state isolation is not universal", task -> true);
        // onlyIf runs before Gradle's @SkipWhenEmpty handling. Otherwise an empty
        // test source set could report NO-SOURCE success without reaching the action.
        onlyIf("A development selection must have executable candidates", task -> {
            validateSelector();
            if (getCandidateClassFiles().isEmpty()) {
                reject("Development verification found no test candidate classes");
            }
            return true;
        });
        addTestListener(new TestListener() {
            @Override public void beforeSuite(TestDescriptor descriptor) {}
            @Override public void beforeTest(TestDescriptor descriptor) {}
            @Override public void afterTest(TestDescriptor descriptor, TestResult result) {
                String line = (result.getResultType() == TestResult.ResultType.SKIPPED
                        ? "skipped=" : "executed=") + descriptor.getClassName() + "#"
                        + descriptor.getDisplayName() + " " + result.getResultType();
                inventory.add(line);
                getLogger().lifecycle(line);
            }
            @Override public void afterSuite(TestDescriptor descriptor, TestResult result) {
                if (descriptor.getParent() != null) {
                    return;
                }
                long executed = result.getSuccessfulTestCount() + result.getFailedTestCount();
                inventory.add("totals: discovered=" + result.getTestCount()
                        + " executed=" + executed + " passed=" + result.getSuccessfulTestCount()
                        + " failed=" + result.getFailedTestCount() + " skipped=" + result.getSkippedTestCount());
                writeInventory(String.join("\n", inventory) + "\n");
                if (executed == 0 || result.getSkippedTestCount() != 0) {
                    throw new GradleException("Development verification requires nonzero execution and no skipped selected tests");
                }
            }
        });
    }

    @Override
    @TaskAction
    public void executeTests() {
        inventory.clear();
        // Validation belongs inside the action: constructor doFirst callbacks can be
        // ordered behind Gradle's annotated test action during task decoration.
        writeInventory("DEVELOPMENT ONLY; RUNNING\n");
        List<String> selectors = getCommandLineSelection().get();
        validateSelector();
        inventory.add("DEVELOPMENT ONLY; not release verification");
        inventory.add("task=" + getPath());
        inventory.add("selected=" + getFilter().getIncludePatterns());
        inventory.add("commandLineSelected=" + selectors);
        inventory.add("fixtureCases=" + getSystemProperties().getOrDefault("blue.fixture.cases", "<none>"));
        inventory.add("testJvm=" + getJavaLauncher().get().getExecutablePath().getAsFile());
        inventory.forEach(line -> getLogger().lifecycle(line));
        super.executeTests();
    }

    private void validateSelector() {
        List<String> selectors = getCommandLineSelection().get();
        if ((getSelectionRequired().get() && selectors.isEmpty())
                || selectors.stream().anyMatch(value -> value.trim().isEmpty())) {
            reject("focusedTest requires a non-empty --tests selector");
        }
    }

    private void reject(String reason) {
        // A rejected attempt must not leave a previous successful HTML/XML report.
        getFileSystemOperations().delete(spec -> spec.delete(
                getReports().getJunitXml().getOutputLocation(),
                getReports().getHtml().getOutputLocation(), getBinaryResultsDirectory()));
        writeInventory("DEVELOPMENT ONLY; REJECTED: " + reason + "\n");
        throw new GradleException(reason);
    }

    private void writeInventory(String value) {
        Path path = getInventoryFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new GradleException("Cannot write development test inventory", exception);
        }
    }
}
