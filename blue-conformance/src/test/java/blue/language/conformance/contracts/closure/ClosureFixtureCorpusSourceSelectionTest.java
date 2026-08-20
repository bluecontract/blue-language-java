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
    @AfterEach
    void restoreConfiguration() {
        restore(
                ClosureFixtureCorpusSource.PACKAGE_ROOT_PROPERTY,
                originalPackageRoot);
    }

    @Test
    void shouldReadTheCompleteClosureCorpusFromBundledResources() {
        System.clearProperty(ClosureFixtureCorpusSource.PACKAGE_ROOT_PROPERTY);

        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();

        assertEquals(ClosureFixtureInventory.CLOSURE_FIXTURE_COUNT,
                source.entries().size());
    }

    @Test
    void shouldPreferTheCompletePackageOverrideOverBundledResources(
            @TempDir Path incompletePackage) {
        System.setProperty(
                ClosureFixtureCorpusSource.PACKAGE_ROOT_PROPERTY,
                incompletePackage.toString());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                ClosureFixtureCorpusSource::open);

        assertTrue(failure.getMessage().contains("package-manifest.yaml"));
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
