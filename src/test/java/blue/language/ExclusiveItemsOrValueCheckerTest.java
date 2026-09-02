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
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.provider.NodeProvider;

import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.merge.processor.ExclusiveItemsOrValueChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExclusiveItemsOrValueCheckerTest {

    @Test
    public void shouldAcceptNodeWithOnlyItems() {
        // given
        Node source = new Node()
                .items(new Node(), new Node());
        Node target = new Node();
        // when
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        // then
        assertDoesNotThrow(() -> processor.process(
                target,
                source,
                null,
                null,
                CanonicalTypeIdentityLookup.incomplete()));
    }

    @Test
    public void shouldAcceptNodeWithOnlyValue() {
        // given
        Node source = new Node()
                .value("Some value");
        Node target = new Node();
        // when
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        // then
        assertDoesNotThrow(() -> processor.process(
                target,
                source,
                null,
                null,
                CanonicalTypeIdentityLookup.incomplete()));
    }

    @Test
    public void shouldRejectNodeWithBothItemsAndValue() {
        // given
        Node source = new Node()
                .items(new Node(), new Node())
                .value("Some value");
        Node target = new Node();
        // when
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        // then
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                target,
                source,
                null,
                null,
                CanonicalTypeIdentityLookup.incomplete()));
    }

    @Test
    public void shouldAcceptNodeWithNeitherItemsNorValue() {
        // given
        Node source = new Node();
        Node target = new Node();
        // when
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        // then
        assertDoesNotThrow(() -> processor.process(
                target,
                source,
                null,
                null,
                CanonicalTypeIdentityLookup.incomplete()));
    }
}
