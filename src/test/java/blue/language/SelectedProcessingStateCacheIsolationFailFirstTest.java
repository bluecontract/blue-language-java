package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Language 1.0 treats materialization and cache state as out-of-band. There is
 * no independently meaningful "selected processing graph".
 */
class SelectedProcessingStateCacheIsolationFailFirstTest {

    @Test
    void pureReferenceAndVerifiedInlineMaterializationHaveOneIdentity() {
        ExactNodeFixture fixture = new ExactNodeFixture();
        Node collapsed = fixture.collapsedDocument();
        Node inline = fixture.inlineDocument();

        assertEquals(fixture.blue.calculateBlueId(collapsed),
                fixture.blue.calculateBlueId(inline));

        ResolvedSnapshot collapsedSnapshot =
                fixture.blue.resolveToSnapshot(collapsed);
        ResolvedSnapshot inlineSnapshot =
                fixture.blue.resolveToSnapshot(inline);

        assertEquivalentMeaning(collapsedSnapshot, inlineSnapshot, fixture.blue);
        assertEquals("present",
                fixture.blue.expand(collapsedSnapshot.resolvedRoot())
                        .getAsText("/subject/payload"));
    }

    @Test
    void cacheHistoryCannotChangeReferenceVersusInlineMeaning() {
        ExactNodeFixture referenceFirst = new ExactNodeFixture();
        ResolvedSnapshot collapsedFirst = referenceFirst.blue.resolveToSnapshot(
                referenceFirst.collapsedDocument());
        ResolvedSnapshot inlineSecond = referenceFirst.blue.resolveToSnapshot(
                referenceFirst.inlineDocument());

        ExactNodeFixture inlineFirst = new ExactNodeFixture();
        ResolvedSnapshot inlineFirstSnapshot = inlineFirst.blue.resolveToSnapshot(
                inlineFirst.inlineDocument());
        ResolvedSnapshot collapsedSecond = inlineFirst.blue.resolveToSnapshot(
                inlineFirst.collapsedDocument());

        assertEquivalentMeaning(collapsedFirst, inlineSecond, referenceFirst.blue);
        assertEquivalentMeaning(collapsedFirst, inlineFirstSnapshot, referenceFirst.blue);
        assertEquivalentMeaning(collapsedFirst, collapsedSecond, referenceFirst.blue);
    }

    @Test
    void ordinaryTransportsPreserveCollapsedReferenceMeaning() {
        ExactNodeFixture fixture = new ExactNodeFixture();
        Node collapsed = fixture.collapsedDocument();
        List<Node> forms = Arrays.asList(
                collapsed,
                collapsed.clone(),
                fixture.blue.jsonToNode(fixture.blue.nodeToJson(collapsed)),
                fixture.blue.yamlToNode(fixture.blue.nodeToYaml(collapsed)));
        ResolvedSnapshot expected = fixture.blue.resolveToSnapshot(collapsed);

        for (Node form : forms) {
            assertTrue(form.getAsNode("/subject").isReferenceOnly());
            ResolvedSnapshot actual = fixture.blue.resolveToSnapshot(form);
            assertEquivalentMeaning(expected, actual, fixture.blue);
        }
    }

    private static void assertEquivalentMeaning(ResolvedSnapshot expected,
                                                ResolvedSnapshot actual,
                                                Blue renderer) {
        assertEquals(expected.blueId(), actual.blueId());
        assertEquals(
                renderer.nodeToJson(
                        renderer.expand(expected.resolvedRoot())),
                renderer.nodeToJson(
                        renderer.expand(actual.resolvedRoot())));
    }

    private static final class ExactNodeFixture {
        private final BasicNodeProvider provider;
        private final String subjectBlueId;
        private final Node inlineSubject;
        private final Blue blue;

        private ExactNodeFixture() {
            Node subject = new Node()
                    .name("Exact cache-invariant subject")
                    .properties("payload", new Node().value("present"));
            provider = new BasicNodeProvider(subject);
            subjectBlueId = provider.getBlueIdByName(subject.getName());
            inlineSubject = provider.fetchFirstByBlueId(subjectBlueId).clone();
            if (inlineSubject.getBlueId() != null) {
                inlineSubject.blueId(null);
            }
            blue = new Blue(provider);
        }

        private Node collapsedDocument() {
            return new Node().properties(
                    "subject", new Node().blueId(subjectBlueId));
        }

        private Node inlineDocument() {
            return new Node().properties("subject", inlineSubject.clone());
        }
    }
}
