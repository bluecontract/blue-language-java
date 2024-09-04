package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

import java.util.List;
import java.util.Map;
import java.util.Set;

@TypeBlueId("Y-BlueId")
public class Y {
    public String type;
    public X xField;
    public X1 x1Field;
    public X2 x2Field;
    public List<X> xListField;
    public Map<String, X> xMapField;
    public Set<X1> x1SetField;
    public Map<String, X2> x2MapField;
    public X[] xArrayField;
    public List<? extends X> wildcardXListField;
    public List<XSubscription> subscriptions;

}