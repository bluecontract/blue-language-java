package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.Map;
import java.util.function.Function;

/**
 * @deprecated Schema wire projection is owned by
 * {@link blue.language.model.wire.SchemaWireForm}.
 */
@Deprecated
public final class SchemaToMapListOrValue {

    private SchemaToMapListOrValue() {
    }

    public static Map<String, Object> get(
            Schema schema, Function<Node, Object> nodeConverter) {
        return blue.language.model.wire.SchemaWireForm.get(
                schema, nodeConverter);
    }
}
