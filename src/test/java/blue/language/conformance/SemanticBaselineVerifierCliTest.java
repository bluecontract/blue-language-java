package blue.language.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SemanticBaselineVerifierCliTest {
    @Test
    void shouldAcceptTheCompleteDefinitionAwareIdentityPipeline() throws Exception {
        // given
        String runtime = runtime();
        String builder = builder();
        // when
        Executable verification = () -> SemanticBaselineVerifierCli
                .verifyRuntimeCanonicalization(runtime, builder);
        // then
        assertDoesNotThrow(verification);
    }

    @Test
    void shouldRejectBypassedDefinitionEvidenceAndChangedCanonicalProjection()
            throws Exception {
        // given
        String runtime = runtime();
        String builder = builder();
        String[] requiredSteps = {
            "canonicalizeWithEvidence(source).canonicalRoot()",
            "rawPreprocess(",
            "preprocessed.isReferenceOnly()",
            "new ResolvedSnapshot(reference, reference, reference.blueId())",
            ".resolveTypeDeclarationEvidence(preprocessed, NO_LIMITS)",
            "definition.resolvedRoot().toNode(), preprocessed,",
            "definition.canonicalTypeIdentities()",
            "FrozenNode.fromNode(canonical), definition.resolvedRoot(),"
        };
        // when
        java.util.List<Executable> corruptions = new java.util.ArrayList<>();
        for (String step : requiredSteps) {
            String changed = runtime.replace(step, "bypassedIdentityStep()");
            corruptions.add(() -> SemanticBaselineVerifierCli
                    .verifyRuntimeCanonicalization(changed, builder));
        }
        // then
        for (Executable corruption : corruptions) {
            assertThrows(IllegalStateException.class, corruption);
        }
    }

    @Test
    void shouldRejectMissingCoverageAndMinimizationInTheIdentityPath()
            throws Exception {
        // given
        String runtime = runtime();
        String builder = builder();
        String unchecked = builder.replace(".requireCompleteCoverage()", "");
        String minimized = runtime.replace(
                "return canonicalizeWithEvidence(source).canonicalRoot();",
                "minimize(source); return canonicalizeWithEvidence(source).canonicalRoot();");
        // when
        Executable missingCoverage = () -> SemanticBaselineVerifierCli
                .verifyRuntimeCanonicalization(runtime, unchecked);
        Executable unwantedMinimization = () -> SemanticBaselineVerifierCli
                .verifyRuntimeCanonicalization(minimized, builder);
        // then
        assertThrows(IllegalStateException.class, missingCoverage);
        assertThrows(IllegalStateException.class, unwantedMinimization);
    }

    private static String runtime() throws Exception {
        return source("runtime/BlueLanguageRuntime.java");
    }

    private static String builder() throws Exception {
        return source("identity/CanonicalIdentityInputBuilder.java");
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "blue-language-core/src/main/java/blue/language/" + path)),
                StandardCharsets.UTF_8);
    }
}
