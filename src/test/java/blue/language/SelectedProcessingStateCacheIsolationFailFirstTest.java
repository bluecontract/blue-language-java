package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Language 1.0 treats materialization and cache state as out-of-band. There is
 * no independently meaningful "selected processing graph".
 */
class SelectedProcessingStateCacheIsolationFailFirstTest {

    @Test
    void shouldAssignOneIdentityToPureReferenceAndVerifiedInlineMaterialization() {
        // given
        ExactNodeFixture fixture = new ExactNodeFixture();
        Node collapsed = fixture.collapsedDocument();
        Node inline = fixture.inlineDocument();

        // when
        String collapsedBlueId =
                fixture.blue.calculateBlueId(collapsed);
        String inlineBlueId =
                fixture.blue.calculateBlueId(inline);
        ResolvedSnapshot collapsedSnapshot =
                fixture.blue.resolveToSnapshot(collapsed);
        ResolvedSnapshot inlineSnapshot =
                fixture.blue.resolveToSnapshot(inline);
        String expandedPayload =
                fixture.blue.expand(collapsedSnapshot.resolvedRoot())
                        .getAsText("/subject/payload");
        String collapsedExpandedJson = expandedJson(collapsedSnapshot, fixture.blue);
        String inlineExpandedJson = expandedJson(inlineSnapshot, fixture.blue);

        // then
        assertEquals(collapsedBlueId, inlineBlueId);
        assertEquals(collapsedSnapshot.blueId(), inlineSnapshot.blueId());
        assertEquals(collapsedExpandedJson, inlineExpandedJson);
        assertEquals("present", expandedPayload);
    }

    @Test
    void shouldKeepReferenceVersusInlineMeaningIndependentOfCacheHistory() {
        // given
        ExactNodeFixture referenceFirst = new ExactNodeFixture();
        Node collapsedReferenceFirst =
                referenceFirst.collapsedDocument();
        Node inlineReferenceSecond =
                referenceFirst.inlineDocument();
        ExactNodeFixture inlineFirst = new ExactNodeFixture();
        Node inlineFirstDocument = inlineFirst.inlineDocument();
        Node collapsedInlineSecond =
                inlineFirst.collapsedDocument();

        // when
        ResolvedSnapshot collapsedFirst = referenceFirst.blue.resolveToSnapshot(
                collapsedReferenceFirst);
        ResolvedSnapshot inlineSecond = referenceFirst.blue.resolveToSnapshot(
                inlineReferenceSecond);
        ResolvedSnapshot inlineFirstSnapshot = inlineFirst.blue.resolveToSnapshot(
                inlineFirstDocument);
        ResolvedSnapshot collapsedSecond = inlineFirst.blue.resolveToSnapshot(
                collapsedInlineSecond);
        List<String> blueIds = Arrays.asList(
                collapsedFirst.blueId(),
                inlineSecond.blueId(),
                inlineFirstSnapshot.blueId(),
                collapsedSecond.blueId());
        List<String> expandedDocuments = Arrays.asList(
                expandedJson(collapsedFirst, referenceFirst.blue),
                expandedJson(inlineSecond, referenceFirst.blue),
                expandedJson(inlineFirstSnapshot, inlineFirst.blue),
                expandedJson(collapsedSecond, inlineFirst.blue));

        // then
        assertEquals(Collections.nCopies(blueIds.size(), blueIds.get(0)), blueIds);
        assertEquals(
                Collections.nCopies(expandedDocuments.size(), expandedDocuments.get(0)),
                expandedDocuments);
    }

    @Test
    void shouldPreserveCollapsedReferenceMeaningAcrossOrdinaryTransports() {
        // given
        ExactNodeFixture fixture = new ExactNodeFixture();
        Node collapsed = fixture.collapsedDocument();

        // when
        List<Node> forms = Arrays.asList(
                collapsed,
                collapsed.clone(),
                fixture.blue.jsonToNode(fixture.blue.nodeToJson(collapsed)),
                fixture.blue.yamlToNode(fixture.blue.nodeToYaml(collapsed)));
        ResolvedSnapshot expected = fixture.blue.resolveToSnapshot(collapsed);
        List<Boolean> referenceOnly =
                new ArrayList<>(forms.size());
        List<String> actualBlueIds =
                new ArrayList<>(forms.size());
        List<String> actualExpandedDocuments =
                new ArrayList<>(forms.size());
        for (Node form : forms) {
            referenceOnly.add(
                    form.getAsNode("/subject").isReferenceOnly());
            ResolvedSnapshot actual = fixture.blue.resolveToSnapshot(form);
            actualBlueIds.add(actual.blueId());
            actualExpandedDocuments.add(expandedJson(actual, fixture.blue));
        }
        String expectedExpandedDocument = expandedJson(expected, fixture.blue);

        // then
        assertEquals(Collections.nCopies(forms.size(), true), referenceOnly);
        assertEquals(Collections.nCopies(forms.size(), expected.blueId()), actualBlueIds);
        assertEquals(
                Collections.nCopies(forms.size(), expectedExpandedDocument),
                actualExpandedDocuments);
    }

    private static String expandedJson(ResolvedSnapshot snapshot, Blue renderer) {
        return renderer.nodeToJson(renderer.expand(snapshot.resolvedRoot()));
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
