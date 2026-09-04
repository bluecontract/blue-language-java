package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

/** Converter selected exclusively for a host-absent {@code null} node. */
public class NullConverter implements Converter<Object> {

    /** Creates a stateless null converter. */
    public NullConverter() {
    }

    @Override
    public Object convert(Node node, Type targetType) {
        if (node != null) {
            throw MappingPayload.failure(
                    "null mapping",
                    "NullConverter accepts only a null Node representing "
                            + "host-side absence");
        }
        return null;
    }
}
