package blue.language.provider;

import blue.language.Blue;
import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.utils.UncheckedObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderEvidenceVerifierTest {

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
    void shouldRequireExactReleaseRegistryEnvironmentAndSnapshotBindingsInSourceMode() {
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

        // then
        assertTrue(evidenceFailure instanceof IllegalArgumentException);
        assertTrue(sourceFailure instanceof IllegalArgumentException);
        assertTrue(registryFailure instanceof IllegalArgumentException);
        assertTrue(preprocessingFailure instanceof IllegalArgumentException);
        assertTrue(releaseFailure instanceof IllegalArgumentException);
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
}
