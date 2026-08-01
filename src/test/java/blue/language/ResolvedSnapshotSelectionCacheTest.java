package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cache state is an implementation detail. These assertions compare the
 * semantic result across warm, cold, cloned, and differently ordered inputs.
 */
class ResolvedSnapshotSelectionCacheTest {

    @Test
    void shouldWarmAndFreshResolutionProduceTheSameSnapshotMeaning() {
        // given
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        Blue warmBlue = fixture.newBlue(new AtomicInteger());
        Node source = fixture.compact();

        ResolvedSnapshot first = warmBlue.resolveToSnapshot(source);
        ResolvedSnapshot warm = warmBlue.resolveToSnapshot(source.clone());
        // when
        ResolvedSnapshot fresh =
                fixture.newBlue(new AtomicInteger()).resolveToSnapshot(source.clone());

        // then
        assertEquivalent(first, warm, warmBlue);
        assertEquivalent(first, fresh, warmBlue);
    }

    @Test
    void shouldChangeIdentityAfterInputMutationWithoutLosingInheritedMeaning() {
        // given
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        Node source = fixture.compact();
        ResolvedSnapshot original = blue.resolveToSnapshot(source);

        Node mutated = source.clone();
        mutated.properties("selectedOnly", new Node().value("changed"));
        // when
        ResolvedSnapshot changed = blue.resolveToSnapshot(mutated);

        // then
        assertNotEquals(original.blueId(), changed.blueId());
        assertEquals("compact", original.resolvedRoot().getAsText("/selectedOnly"));
        assertEquals("changed", changed.resolvedRoot().getAsText("/selectedOnly"));
        assertTrue(hasContract(original.resolvedRoot(), "audit"));
        assertTrue(hasContract(changed.resolvedRoot(), "audit"));
    }

    @Test
    void shouldPreventResolutionOrderFromMakingRepresentationHistoryObservable() {
        // given
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        Node compact = fixture.compact();
        Node redundant = fixture.materializedSource();

        Blue compactFirstBlue = fixture.newBlue(new AtomicInteger());
        ResolvedSnapshot compactFirst =
                compactFirstBlue.resolveToSnapshot(compact);
        ResolvedSnapshot redundantSecond =
                compactFirstBlue.resolveToSnapshot(redundant);

        Blue redundantFirstBlue = fixture.newBlue(new AtomicInteger());
        ResolvedSnapshot redundantFirst =
                redundantFirstBlue.resolveToSnapshot(redundant.clone());
        // when
        ResolvedSnapshot compactSecond =
                redundantFirstBlue.resolveToSnapshot(compact.clone());

        // then
        assertEquivalent(compactFirst, redundantSecond, compactFirstBlue);
        assertEquivalent(compactFirst, redundantFirst, compactFirstBlue);
        assertEquivalent(compactFirst, compactSecond, compactFirstBlue);
    }

    private static void assertEquivalent(ResolvedSnapshot expected,
                                         ResolvedSnapshot actual,
                                         Blue renderer) {
        assertEquals(expected.blueId(), actual.blueId());
        assertEquals(renderer.nodeToJson(expected.canonicalRoot()),
                renderer.nodeToJson(actual.canonicalRoot()));
        assertEquals(renderer.nodeToJson(expected.resolvedRoot()),
                renderer.nodeToJson(actual.resolvedRoot()));
    }

    private static boolean hasContract(Node document, String key) {
        return document != null
                && document.getContracts() != null
                && document.getContracts().getProperties() != null
                && document.getContracts().getProperties().containsKey(key);
    }
}
