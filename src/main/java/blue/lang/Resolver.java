package blue.lang;

import blue.lang.model.BlueObject;
import blue.lang.model.Limits;

@FunctionalInterface
public interface Resolver {
    Node resolve(BlueObject object, Limits limits);
}
