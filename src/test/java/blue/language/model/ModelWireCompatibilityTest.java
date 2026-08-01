package blue.language.model;

import blue.language.model.wire.NodeWireForm;
import blue.language.utils.NodeToMapListOrValue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelWireCompatibilityTest {

    @Test
    void shouldKeepLegacyAndModelOwnedOfficialWireFormsEquivalent() {
        // given
        Node node = new Node()
                .name("Subject")
                .schema(new Schema()
                        .required(true)
                        .enumValues(Arrays.asList(
                                new Node().value("open"),
                                new Node().value("closed"))))
                .properties("status", new Node().value("open"));

        // when
        Object legacy = NodeToMapListOrValue.get(node);
        Object modelOwned = NodeWireForm.get(node);

        // then
        assertEquals(legacy, modelOwned);
    }

    @Test
    void shouldKeepLegacyAndModelOwnedSimpleWireFormsEquivalent() {
        // given
        Node node = new Node().items(
                new Node().value("first"),
                new Node().value("second"));

        // when
        Object legacy = NodeToMapListOrValue.get(
                node, NodeToMapListOrValue.Strategy.SIMPLE);
        Object modelOwned = NodeWireForm.get(
                node, NodeWireForm.Strategy.SIMPLE);

        // then
        assertEquals(legacy, modelOwned);
    }
}
