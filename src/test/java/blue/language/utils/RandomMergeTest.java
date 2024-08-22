package blue.language.utils;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RandomMergeTest {

    @Test
    public void testBlueIdMerging() throws Exception {

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
        nodeProvider.addSingleDocs(b);

        Node bNode = nodeProvider.getNodeByName("B");
        
        Blue blue = new Blue(nodeProvider);
        Node resolved = blue.resolve(bNode);

        Map<String, Object> resolvedMap = (Map) NodeToMapListOrValue.get(resolved);

        assertTrue(resolvedMap.containsKey("timeline"));
        Map<String, Object> timelineMap = (Map<String, Object>) resolvedMap.get("timeline");
        assertEquals("aaa", timelineMap.get("description"));
        assertEquals("abc-id", timelineMap.get("blueId"));

        assertTrue(timelineMap.containsKey("asdf"));
        Map<String, Object> asdfMap = (Map<String, Object>) timelineMap.get("asdf");
        assertEquals("xyz", asdfMap.get("value"));

    }

}
