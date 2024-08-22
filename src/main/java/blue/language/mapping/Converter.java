package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

public interface Converter<T> {
    T convert(Node node, Type targetType);
}