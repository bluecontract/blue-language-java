package blue.language.api;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.patching.ImmutableBluePatch;
import blue.language.snapshot.CanonicalPatchResult;
import org.junit.jupiter.api.Test;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class BlueLanguageCompositionTest {

    @Test
    void shouldExposeFocusedServicesOverOneRuntimeConfiguration() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();

        // when
        Object[] services = {
                language.codec(),
                language.preprocessing(),
                language.graph(),
                language.resolution(),
                language.identity(),
                language.snapshots(),
                language.matching(),
                language.patching()
        };

        // then
        for (Object service : services) {
            assertNotNull(service);
        }
        language.close();
    }

    @Test
    void shouldCalculateSourceIdentityThroughCanonicalDirectPath() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    "type: Text\nvalue: hello", BlueFormat.YAML);

            // when
            Node canonical = language.identity()
                    .canonicalIdentityInput(source);
            String sourceBlueId = language.identity()
                    .sourceDocumentBlueId(source);
            String directBlueId = language.identity()
                    .directBlueId(canonical);

            // then
            assertEquals(TEXT_TYPE_BLUE_ID,
                    canonical.getType().getBlueId());
            assertEquals(directBlueId, sourceBlueId);
        }
    }

    @Test
    void shouldKeepCanonicalPatchingInsideLanguageService() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node canonical = new Node()
                    .properties("left", new Node().value("before"));

            // when
            CanonicalPatchResult result = language.patching().apply(
                    canonical,
                    ImmutableBluePatch.replace(
                            "/left", new Node().value("after")));

            // then
            assertEquals("after",
                    result.root().property("left").getValue());
            assertFalse(result.blueId().isEmpty());
        }
    }
}
