package blue.language.provider;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.UncheckedObjectMapper;
import org.junit.jupiter.api.Test;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderEvidenceVerifierTest {

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
        String registry = BlueCoreTypeRegistry.INSTANCE.packageIdentity();
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
}
