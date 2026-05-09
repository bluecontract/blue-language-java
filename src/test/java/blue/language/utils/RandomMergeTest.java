package blue.language.utils;

import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class RandomMergeTest {

    @Test
    public void testBlueIdCannotBeMergedWithSiblingContent() throws Exception {

        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\n" +
                   "timeline:\n" +
                   "  description: aaa";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "timeline:\n" +
                   "  blueId: abc-id\n" +
                   "  asdf: xyz";
        assertThrows(RuntimeException.class, () -> nodeProvider.addSingleDocs(b));
    }

}
