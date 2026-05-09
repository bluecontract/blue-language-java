package blue.language.provider;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

class ProviderCanonicalIngestionTest {

    @Test
    void storesMigratedCanonicalContentUnderCorrectedHash() {
        String legacyDoc = "name: Legacy\n" +
                "constraints:\n" +
                "  minLength: 2";

        Node canonicalNode = YAML_MAPPER.readValue(legacyDoc, Node.class);
        String canonicalHash = BlueIdCalculator.calculateBlueId(canonicalNode);

        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(legacyDoc);

        assertEquals(canonicalHash, provider.getBlueIdByName("Legacy"));

        List<Node> fetched = provider.fetchByBlueId(canonicalHash);
        assertEquals(1, fetched.size());
        assertNotNull(fetched.get(0).getSchema());
        assertEquals(2, fetched.get(0).getSchema().getMinLengthValue());
        assertNull(fetched.get(0).getProperties());
    }
}
