package blue.language;

import blue.language.model.Node;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resolved form is carried by {@link ResolvedSnapshot}; it is not a second
 * authored "selected graph" whose materialization changes semantics.
 */
class ResolvedProcessingSelectionCorrectnessTest {

    @Test
    void snapshotKeepsCanonicalIdentityAndResolvedMeaningDistinct() {
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        Node source = fixture.compact();

        ResolvedSnapshot snapshot = blue.resolveToSnapshot(source);

        assertEquals(snapshot.blueId(), blue.calculateSemanticBlueId(source));
        assertFalse(hasContract(snapshot.canonicalRoot(), "audit"));
        assertTrue(hasContract(snapshot.resolvedRoot(), "audit"));
        assertEquals("materialized",
                snapshot.resolvedRoot().getAsText("/materializedField"));
    }

    @Test
    void redundantInlineTypeContributionsDoNotCreateAnotherSelectionForm() {
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());

        ResolvedSnapshot compact = blue.resolveToSnapshot(fixture.compact());
        ResolvedSnapshot redundant =
                blue.resolveToSnapshot(fixture.materializedSource());

        assertEquals(compact.blueId(), redundant.blueId());
        assertEquals(blue.nodeToJson(compact.canonicalRoot()),
                blue.nodeToJson(redundant.canonicalRoot()));
        assertEquals(blue.nodeToJson(compact.resolvedRoot()),
                blue.nodeToJson(redundant.resolvedRoot()));
    }

    private static boolean hasContract(Node document, String key) {
        return document != null
                && document.getContracts() != null
                && document.getContracts().getProperties() != null
                && document.getContracts().getProperties().containsKey(key);
    }
}
