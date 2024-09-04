package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

public class NullConverter implements Converter<Object> {
    @Override
    public Object convert(Node node, Type targetType) {
        return null;
    }
}
