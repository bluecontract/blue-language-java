package blue.language.utils;

import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class RandomMergeTest {

    @Test
    public void shouldRejectMergingBlueIdWithSiblingContent() throws Exception {

        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\n" +
                   "timeline:\n" +
                   "  description: aaa";
        nodeProvider.addSingleDocs(a);

        // when
        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "timeline:\n" +
                   "  blueId: abc-id\n" +
                   "  asdf: xyz";
        // then
        assertThrows(RuntimeException.class, () -> nodeProvider.addSingleDocs(b));
    }

}
