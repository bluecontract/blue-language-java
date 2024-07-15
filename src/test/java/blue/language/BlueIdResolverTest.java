package blue.language;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static java.util.Collections.singletonList;

public class BlueIdResolverTest {

    @Test
    public void testMinLengthPositive() throws Exception {
        Node node = new Node().name("abc").type(new Node().blueId("123"));
        Blue blue = new Blue(blueId -> singletonList(new Node().name("from " + blueId)));
        Node result = blue.resolve(node);
        System.out.println(YAML_MAPPER.writeValueAsString(result));
    }

}
