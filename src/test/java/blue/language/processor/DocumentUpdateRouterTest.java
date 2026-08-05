package blue.language.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DocumentUpdateRouterTest {

    private static final String CHILD_SCOPE = "/lessons/lesson-a";
    private static final String EMBEDDED_CONTRACT =
            CHILD_SCOPE + "/contracts/embedded";
    private static final String EMBEDDED_PATHS =
            EMBEDDED_CONTRACT + "/paths";
    private static final String EMBEDDED_COLLECTION_PATHS =
            EMBEDDED_CONTRACT + "/collectionPaths";

    @Test
    void shouldClassifyExactPathDeclarationUpdatesAsSurfaceChanges() {
        // given
        String changedPath = EMBEDDED_PATHS + "/0";

        // when
        boolean affectsSurface =
                DocumentUpdateRouter.affectsEmbeddedSubscriptionSurface(
                        CHILD_SCOPE, changedPath);

        // then
        assertTrue(affectsSurface);
    }

    @Test
    void shouldClassifyCollectionPathDeclarationUpdatesAsSurfaceChanges() {
        // given
        String changedPath = EMBEDDED_COLLECTION_PATHS + "/0";

        // when
        boolean affectsSurface =
                DocumentUpdateRouter.affectsEmbeddedSubscriptionSurface(
                        CHILD_SCOPE, changedPath);

        // then
        assertTrue(affectsSurface);
    }

    @Test
    void shouldClassifyWholeEmbeddedMarkerReplacementAsSurfaceChange() {
        // given
        String changedPath = EMBEDDED_CONTRACT;

        // when
        boolean affectsSurface =
                DocumentUpdateRouter.affectsEmbeddedSubscriptionSurface(
                        CHILD_SCOPE, changedPath);

        // then
        assertTrue(affectsSurface);
    }

    @Test
    void shouldIgnoreUnrelatedDocumentUpdates() {
        // given
        String changedPath = CHILD_SCOPE + "/progress";

        // when
        boolean affectsSurface =
                DocumentUpdateRouter.affectsEmbeddedSubscriptionSurface(
                        CHILD_SCOPE, changedPath);

        // then
        assertFalse(affectsSurface);
    }
}
