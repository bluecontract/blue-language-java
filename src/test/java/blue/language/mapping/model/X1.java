package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

import java.util.List;
import java.util.Set;

@TypeBlueId("X1-BlueId")
public class X1 extends X {
    public int[] intArrayField;
    public List<String> stringListField;
    public Set<Integer> integerSetField;
}
