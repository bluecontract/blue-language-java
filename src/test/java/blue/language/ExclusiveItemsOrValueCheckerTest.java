package blue.language;

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
        assertDoesNotThrow(() -> processor.process(target, source, null, null));
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
        assertDoesNotThrow(() -> processor.process(target, source, null, null));
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
        assertThrows(IllegalArgumentException.class, () -> processor.process(target, source, null, null));
    }

    @Test
    public void shouldAcceptNodeWithNeitherItemsNorValue() {
        // given
        Node source = new Node();
        Node target = new Node();
        // when
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        // then
        assertDoesNotThrow(() -> processor.process(target, source, null, null));
    }
}
