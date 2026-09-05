package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.merge.ResolvedSnapshot;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
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
    void shouldPreserveNoMatchWithExplicitEmptyContractsAcrossSelectionForms() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node wideType = new Node().name("Wide Processing Selection Type");
        for (int index = 0; index < 128; index++) {
            wideType.properties("field" + index, new Node().value(index));
        }
        provider.addSingleNodes(wideType);
        Blue blue = new Blue(provider);
        Node compact = new Node()
                .type(new Node().blueId(provider.getBlueIdByName(
                        "Wide Processing Selection Type")))
                .contracts(Nodes.emptyObject());
        DocumentProcessingResult initialized = blue.initializeDocument(compact);
        Node selected = initialized.document();
        String sourceIdentity = blue.calculateSourceDocumentBlueId(selected);
        ResolvedSnapshot snapshot = blue.loadSnapshot(selected);
        Node event = new Node().properties("kind", new Node().value("noop"));

        // when
        DocumentProcessingResult[] results = {
                blue.processDocument(selected, event),
                blue.processDocument(selected.clone(), event),
                blue.processDocument(snapshot, event),
                blue.processDocument(selected, event)
        };

        // then
        assertEquals(ProcessorStatus.SUCCESS, initialized.status());
        assertEquals(sourceIdentity, snapshot.blueId());
        for (DocumentProcessingResult result : results) {
            assertEquals(ProcessorStatus.NO_MATCH, result.status());
            assertFalse(result.commits());
            assertTrue(result.events().isEmpty());
            assertEquals(sourceIdentity,
                    blue.calculateSourceDocumentBlueId(result.document()));
        }
    }

    @Test
    void shouldKeepCanonicalIdentityAndResolvedMeaningDistinctInSnapshot() {
        // given
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        Node source = fixture.compact();

        // when
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(source);

        // then
        assertEquals(snapshot.blueId(), blue.calculateSourceDocumentBlueId(source));
        assertFalse(hasContract(snapshot.canonicalRoot(), "audit"));
        assertTrue(hasContract(snapshot.resolvedRoot(), "audit"));
        assertEquals("materialized",
                snapshot.resolvedRoot().getAsText("/materializedField"));
    }

    @Test
    void shouldNotCreateAnotherSelectionFormForRedundantInlineTypeContributions() {
        // given
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());

        ResolvedSnapshot compact = blue.resolveToSnapshot(fixture.compact());
        // when
        ResolvedSnapshot redundant =
                blue.resolveToSnapshot(fixture.materializedSource());

        // then
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
