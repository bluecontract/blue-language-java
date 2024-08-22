package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

import java.util.List;

@TypeBlueId("Y1-BlueId")
public class Y1 extends Y {
    public X11 x11Field;
    public X12 x12Field;
    public List<X11> x11ListField;
}