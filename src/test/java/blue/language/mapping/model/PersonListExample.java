package blue.language.mapping.model;

import blue.language.model.*;

import java.util.List;

@TypeBlueId("PersonList-BlueId")
public class PersonListExample {
    public List<? extends Person> team1;
    @BlueName("team2")
    public String team2Name;
    @BlueDescription("team2")
    public String team2Description;
    public List<? extends Person> team2;
//    public PersonList team3;
//    public Map<String, Object> team4;
    public Node team3;
    /**
     * team:
     *   - type:
     *       blueId: Doctor-BlueId
     *     name: Adam
     *     specialization: surgeon
     *   - type:
     *       blueId: Nurse-BlueId
     *     name: Betty
     *     yearsOfExperience: 12
     */
    public static class PersonList {
        public String name;
        public String description;
        public List<? extends Person> value;
    }
}
