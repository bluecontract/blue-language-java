package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("Z1-BlueId")
public class Z1 extends Z {
    public String z1SpecificField;

    @Override
    public String getAbstractMethod() {
        return "Z1 implementation";
    }
}