package blue.language.conformance.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the conformance adapter as a pure public-Language delegation. */
final class LanguageProcessingScopeSnapshotManagerArchitectureTest {

    @Test
    void adapterDelegatesEverySnapshotCapabilityWithoutProviderOrIdentityLogic()
            throws IOException {
        String relative = "src/main/java/blue/language/conformance/contracts/"
                + "LanguageProcessingScopeSnapshotManager.java";
        String source = readModuleSource(relative);

        for (String delegation : Arrays.asList(
                "scope.resolve(document)",
                "scope.resolveTransient(document)",
                "scope.resolveTransientForCanonicalIdentity(",
                "scope.resolvePreservingPaths(document, preservedPaths)",
                "scope.resolveTransientPreservingPaths(",
                "scope.materializeVerifiedExactReference(reference)",
                "scope.transientSequence()",
                "scope.forkTransientSequence()",
                "scope.retainTransientState(canonicalRoot, resolvedRoot)",
                "scope.close()",
                "scope.isTransientStateCurrent()",
                "scope.supportsIncrementalValueResolution()",
                "scope.supportsIncrementalValueResolution(request)",
                "scope.transientConformanceEngine(conformanceEngine)",
                "scope.applyPatch(snapshot, patch)",
                "scope.publish(snapshot)")) {
            assertTrue(source.contains(delegation),
                    "missing strict Language scope delegation: " + delegation);
        }

        for (String forbidden : Arrays.asList(
                "NodeProvider",
                "ExactResolutionOverlay.from",
                "fetchResultByBlueId",
                "getNodeProvider",
                "DirectBlueIdCalculator",
                "import blue.language.identity.BlueIds",
                "calculateBlueId")) {
            assertFalse(source.contains(forbidden),
                    "adapter must not own provider/identity semantics: "
                            + forbidden);
        }
    }

    @Test
    void candidateRegistryLoadCannotEagerlyConstructTheClasspathDefault()
            throws IOException {
        String relative = "src/main/java/blue/language/conformance/contracts/"
                + "ContractsFixtureHarnessDataSupport.java";
        String source = readModuleSource(relative);

        assertTrue(source.contains(
                "return RegistryEnvironment.load().idByKey.get(key);"),
                "outer registry lookup must use the lazy default accessor");
        assertTrue(source.contains(
                "static RegistryEnvironment load() {\n"
                        + "            return DefaultHolder.INSTANCE;\n"
                        + "        }"),
                "classpath default must be reached only through DefaultHolder");
        assertTrue(source.contains(
                "private static final class DefaultHolder {\n"
                        + "            private static final RegistryEnvironment INSTANCE"
                        + " = loadInternal();\n"
                        + "        }"),
                "default environment must be initialized inside the lazy holder");

        int environment = source.indexOf(
                "static final class RegistryEnvironment");
        int holder = source.indexOf(
                "private static final class DefaultHolder", environment);
        assertTrue(environment >= 0 && holder > environment,
                "cannot locate RegistryEnvironment/DefaultHolder boundary");
        String eagerRegion = source.substring(environment, holder);
        assertFalse(eagerRegion.contains(
                "static final RegistryEnvironment INSTANCE"),
                "candidate load would initialize a classpath default eagerly");
    }

    private static String readModuleSource(String relative)
            throws IOException {
        return new String(
                Files.readAllBytes(modulePath(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path modulePath(String relative) {
        Path working = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        Path direct = working.resolve(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromRoot = working.resolve("blue-conformance")
                .resolve(relative);
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new AssertionError("Cannot locate blue-conformance/" + relative);
    }
}
