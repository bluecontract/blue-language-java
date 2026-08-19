package blue.language.conformance.contracts.closure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClosureFixtureCorpusSourceSelectionTest {

    private final String originalPackageRoot = System.getProperty(
            ClosureFixtureCorpusSource.PACKAGE_ROOT_PROPERTY);
    private final String originalSpecRoot = System.getProperty(
            ClosureFixtureCorpusSource.SPEC_ROOT_PROPERTY);

    @AfterEach
    void restoreConfiguration() {
        restore(
                ClosureFixtureCorpusSource.PACKAGE_ROOT_PROPERTY,
                originalPackageRoot);
        restore(
                ClosureFixtureCorpusSource.SPEC_ROOT_PROPERTY,
                originalSpecRoot);
    }

    @Test
    void shouldReadTheCompleteClosureCorpusFromTheSlimSpecRoot() {
        System.clearProperty(ClosureFixtureCorpusSource.PACKAGE_ROOT_PROPERTY);
        requireConfiguredSpecRoot();

        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();

        assertEquals(ClosureFixtureInventory.CLOSURE_FIXTURE_COUNT,
                source.entries().size());
    }

    @Test
    void shouldPreferTheCompletePackageOverrideOverTheSpecRoot(
            @TempDir Path incompletePackage) {
        requireConfiguredSpecRoot();
        System.setProperty(
                ClosureFixtureCorpusSource.PACKAGE_ROOT_PROPERTY,
                incompletePackage.toString());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                ClosureFixtureCorpusSource::open);

        assertTrue(failure.getMessage().contains("package-manifest.yaml"));
    }

    private static void requireConfiguredSpecRoot() {
        String root = System.getProperty(
                ClosureFixtureCorpusSource.SPEC_ROOT_PROPERTY);
        if (root == null || root.isEmpty()) {
            throw new IllegalStateException(
                    "The test task did not supply blue.spec.root");
        }
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
