package blue.language;

import blue.language.processor.ExclusiveItemsOrValueChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExclusiveItemsOrValueCheckerTest {

    @Test
    public void testNodeWithOnlyItemsShouldPass() {
        Node source = new Node()
                .items(new Node(), new Node());
        Node target = new Node();
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        assertDoesNotThrow(() -> processor.process(target, source, null, null));
    }

    @Test
    public void testNodeWithOnlyValueShouldPass() {
        Node source = new Node()
                .value("Some value");
        Node target = new Node();
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        assertDoesNotThrow(() -> processor.process(target, source, null, null));
    }

    @Test
    public void testNodeWithBothItemsAndValueShouldFail() {
        Node source = new Node()
                .items(new Node(), new Node())
                .value("Some value");
        Node target = new Node();
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        assertThrows(IllegalArgumentException.class, () -> processor.process(target, source, null, null));
    }

    @Test
    public void testNodeWithNeitherItemsNorValueShouldPass() {
        Node source = new Node();
        Node target = new Node();
        MergingProcessor processor = new ExclusiveItemsOrValueChecker();

        assertDoesNotThrow(() -> processor.process(target, source, null, null));
    }
}
