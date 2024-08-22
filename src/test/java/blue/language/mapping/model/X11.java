package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

import java.util.List;
import java.util.Map;

@TypeBlueId("X11-BlueId")
public class X11 extends X1 {
    public List<List<String>> nestedListField;
    public Map<String, List<Integer>> complexMapField;
}