package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

/** Converter selected for absent nodes; every target type receives {@code null}. */
public class NullConverter implements Converter<Object> {

    /** Creates a stateless null converter. */
    public NullConverter() {
    }

    @Override
    public Object convert(Node node, Type targetType) {
        return null;
    }
}
