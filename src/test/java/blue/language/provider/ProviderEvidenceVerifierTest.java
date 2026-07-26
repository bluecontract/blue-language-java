package blue.language.provider;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.UncheckedObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderEvidenceVerifierTest {

    @Test
    void sourceModeRequiresExactReleaseRegistryEnvironmentAndSnapshotBindings() {
        Node source = UncheckedObjectMapper.YAML_MAPPER.readValue(
                "blue:\n"
                        + "  imports: {}\n"
                        + "value: wanted",
                Node.class);
        Blue blue = new Blue();
        String requested = blue.calculateSemanticBlueId(source);
        String preprocessing =
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(blue);
        String evidence =
                ProviderEvidenceVerifier.sourceEvidenceIdentity(source);
        String registry = BlueCoreTypeRegistry.INSTANCE.packageIdentity();
        SourceProviderEnvironment exact = environment(
                blue, preprocessing, registry, evidence);

        assertDoesNotThrow(() -> ProviderEvidenceVerifier.verify(
                requested, source, ProviderMode.SOURCE_DOCUMENT, blue, exact));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        environment(blue, preprocessing, registry,
                                evidence + "-tampered")));
        Node alteredSource = source.clone().value("altered");
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEvidenceVerifier.verify(
                        requested, alteredSource, ProviderMode.SOURCE_DOCUMENT,
                        blue, exact));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        environment(blue, preprocessing,
                                registry + "-tampered", evidence)));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        environment(blue, preprocessing + "-tampered",
                                registry, evidence)));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        new SourceProviderEnvironment(
                                blue.languageVersion(),
                                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY
                                        + "-tampered",
                                preprocessing,
                                registry,
                                evidence)));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEvidenceVerifier.verify(
                        requested, source, ProviderMode.SOURCE_DOCUMENT, blue,
                        new SourceProviderEnvironment("1.0", "ambient-label")));
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
