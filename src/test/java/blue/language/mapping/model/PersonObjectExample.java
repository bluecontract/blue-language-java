package blue.language.mapping.model;

import blue.language.model.BlueId;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;

import java.util.Map;

@TypeBlueId("PersonObject-BlueId")
public class PersonObjectExample {
    @BlueId
    public String alice1;
    public Node alice2;
    public Map<String, Object> alice3;
    public Person alice4;
    public Person alice5;
}
