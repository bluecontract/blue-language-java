package blue.language.provider;

import blue.language.Blue;
import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.codec.jackson.UncheckedObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderEvidenceVerifierTest {

    private static final String SOURCE_PREPROCESSING_BASELINE_IDENTITY =
            "blue-language-source-preprocessing-environment-1.0@"
                    + "sha256:74be75a1b0ca9932b88e00b5010beadf45010d8bf643b52fb83447d4c0a9640e";
    private static final String DEFECTIVE_AGGREGATE_RELEASE_IDENTITY =
            "blue-language-contracts-embedded-modules-collection-paths@"
                    + "sha256:f794dfd2c57969f81025387895e60a0e67919f677ccf7efeeac0f5ac189f938c";

    @Test
    void shouldBindSourceEvidenceToAcyclicPreprocessingBaseline() {
        // given
        String expected = SOURCE_PREPROCESSING_BASELINE_IDENTITY;

        // when
        String actual =
                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY;

        // then
        assertEquals(expected, actual);
        assertNotEquals(DEFECTIVE_AGGREGATE_RELEASE_IDENTITY, actual);
    }

    @Test
    void shouldBindRuntimeAliasesAndImportsAboveTheBaseline() {
        // given
        SourceContentVerificationRuntime baseline = runtime(
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap());
        SourceContentVerificationRuntime aliases = runtime(
                Collections.singletonMap("person", "blue-id"),
                Collections.<String, String>emptyMap());
        SourceContentVerificationRuntime imports = runtime(
                Collections.<String, String>emptyMap(),
                Collections.singletonMap("tenant", "tenant-id"));

        // when
        String baselineIdentity =
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(
                        baseline);
        String aliasIdentity =
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(
                        aliases);
        String importIdentity =
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(
                        imports);

        // then
        assertNotEquals(baselineIdentity, aliasIdentity);
        assertNotEquals(baselineIdentity, importIdentity);
        assertNotEquals(aliasIdentity, importIdentity);
    }

    @Test
    void shouldFailClosedWhenSourceRuntimeOmitsCanonicalRegistryBinding() {
        // given
        Node source = UncheckedObjectMapper.YAML_MAPPER.readValue(
                "blue:\n"
                        + "  imports: {}\n"
                        + "value: wanted",
                Node.class);
        Blue blue = new Blue();
        String requested = blue.calculateSourceDocumentBlueId(source);
        String preprocessing =
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(
                        blue);
        SourceProviderEnvironment exact = environment(
                blue,
                preprocessing,
                blue.canonicalRegistryIdentity(),
                ProviderEvidenceVerifier.sourceEvidenceIdentity(source));
        SourceContentVerificationRuntime legacyRuntime =
                legacyRuntimeWithoutRegistryBinding(blue);

        // when
        UnsupportedOperationException failure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requested,
                        source,
                        ProviderMode.SOURCE_DOCUMENT,
                        legacyRuntime,
                        exact));

        // then
        assertTrue(failure.getMessage().contains(
                "explicit canonical registry identity"));
    }

    @Test
    void shouldVerifyDirectNodeWithoutConsultingSourceRuntimeBindings() {
        // given
        Node direct = new Node().value("direct evidence");
        String requested =
                DirectBlueIdCalculator.calculateBlueId(direct);
        SourceContentVerificationRuntime rejectingSourceRuntime =
                rejectingSourceRuntime();

        // when
        Node verified = ProviderEvidenceVerifier.verify(
                requested,
                direct,
                ProviderMode.DIRECT_NODE,
                rejectingSourceRuntime,
                null);

        // then
        assertEquals(
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(direct),
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(verified));
        assertNotSame(direct, verified);
    }

    @Test
    void shouldExposeExactCanonicalRegistryIdentityFromBothRuntimes() {
        // given
        String expected =
                BlueCoreTypeRegistry.INSTANCE.packageIdentity();
        try (Blue blue = new Blue();
             BlueLanguageRuntime runtime = BlueLanguageRuntime.create(
                     blueId -> null,
                     BlueCachePolicy.boundedDefaults(),
                     Collections.emptyMap())) {

            // when
            String aggregateIdentity = blue.canonicalRegistryIdentity();
            String focusedIdentity = runtime.canonicalRegistryIdentity();

            // then
            assertEquals(expected, aggregateIdentity);
            assertEquals(expected, focusedIdentity);
        }
    }

    @Test
    void shouldRequireExactBaselineRegistryEnvironmentAndSnapshotBindingsInSourceMode() {
        // given
        Node source = UncheckedObjectMapper.YAML_MAPPER.readValue(
                "blue:\n"
                        + "  imports: {}\n"
                        + "value: wanted",
                Node.class);
        Blue blue = new Blue();
        String requested = blue.calculateSourceDocumentBlueId(source);
        String preprocessing =
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(blue);
        String evidence =
                ProviderEvidenceVerifier.sourceEvidenceIdentity(source);
        String registry = blue.canonicalRegistryIdentity();
        SourceProviderEnvironment exact = environment(
                blue, preprocessing, registry, evidence);

        // when
        ProviderEvidenceVerifier.verify(
                requested, source, ProviderMode.SOURCE_DOCUMENT, blue, exact);
        IllegalArgumentException evidenceFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        environment(blue, preprocessing, registry,
                                evidence + "-tampered")));
        Node alteredSource = source.clone().value("altered");
        IllegalArgumentException sourceFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requested, alteredSource, ProviderMode.SOURCE_DOCUMENT,
                        blue, exact));
        IllegalArgumentException registryFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        environment(blue, preprocessing,
                                registry + "-tampered", evidence)));
        IllegalArgumentException preprocessingFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        environment(blue, preprocessing + "-tampered",
                                registry, evidence)));
        IllegalArgumentException releaseFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        new SourceProviderEnvironment(
                                blue.languageVersion(),
                                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY
                                        + "-tampered",
                                preprocessing,
                                registry,
                                evidence)));
        IllegalArgumentException oldAggregateFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        new SourceProviderEnvironment(
                                blue.languageVersion(),
                                DEFECTIVE_AGGREGATE_RELEASE_IDENTITY,
                                preprocessing,
                                registry,
                                evidence)));

        // then
        assertTrue(evidenceFailure instanceof IllegalArgumentException);
        assertTrue(sourceFailure instanceof IllegalArgumentException);
        assertTrue(registryFailure instanceof IllegalArgumentException);
        assertTrue(preprocessingFailure instanceof IllegalArgumentException);
        assertTrue(releaseFailure instanceof IllegalArgumentException);
        assertTrue(oldAggregateFailure instanceof IllegalArgumentException);
    }

    private SourceProviderEnvironment environment(Blue blue,
                                                  String preprocessing,
                                                  String registry,
                                                  String evidence) {
        return new SourceProviderEnvironment(
                blue.languageVersion(),
                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY,
                preprocessing,
                registry,
                evidence);
    }

    private SourceContentVerificationRuntime
    legacyRuntimeWithoutRegistryBinding(Blue blue) {
        return new SourceContentVerificationRuntime() {
            @Override
            public String languageVersion() {
                return blue.languageVersion();
            }

            @Override
            public Map<String, String> preprocessingAliases() {
                return blue.preprocessingAliases();
            }

            @Override
            public Node canonicalizeSourceContent(Node source) {
                return blue.canonicalizeSourceContent(source);
            }
        };
    }

    private SourceContentVerificationRuntime rejectingSourceRuntime() {
        return new SourceContentVerificationRuntime() {
            @Override
            public String languageVersion() {
                throw new AssertionError(
                        "Direct verification consulted languageVersion");
            }

            @Override
            public Map<String, String> preprocessingAliases() {
                throw new AssertionError(
                        "Direct verification consulted preprocessingAliases");
            }

            @Override
            public Node canonicalizeSourceContent(Node source) {
                throw new AssertionError(
                        "Direct verification canonicalized Source content");
            }

            @Override
            public String canonicalRegistryIdentity() {
                throw new AssertionError(
                        "Direct verification consulted the canonical registry");
            }
        };
    }

    private SourceContentVerificationRuntime runtime(
            Map<String, String> aliases,
            Map<String, String> imports) {
        return new SourceContentVerificationRuntime() {
            @Override
            public String languageVersion() {
                return "1.0";
            }

            @Override
            public Map<String, String> preprocessingAliases() {
                return aliases;
            }

            @Override
            public Map<String, String> environmentImports() {
                return imports;
            }

            @Override
            public Node canonicalizeSourceContent(Node source) {
                return source.clone();
            }

            @Override
            public String canonicalRegistryIdentity() {
                return BlueCoreTypeRegistry.INSTANCE.packageIdentity();
            }
        };
    }
}
