package blue.language.mapping.model;

import blue.language.model.*;

import java.util.Map;

@TypeBlueId("PersonDictionary-BlueId")
public class PersonDictionaryExample {
    public Map<String, ? extends Person> team1;
    public Map<String, Person> team2;
    public Map<Integer, Person> team3;
}