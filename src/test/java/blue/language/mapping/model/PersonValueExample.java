package blue.language.mapping.model;

import blue.language.model.*;

@TypeBlueId("PersonValue-BlueId")
public class PersonValueExample {
    public Integer age1;
    @BlueName("age2")
    public String age2Name;
    @BlueDescription("age2")
    public String age2Description;
    public Integer age2;
    public Node age3;

    @Override
    public String toString() {
        return "PersonValueExample{" +
               "age1=" + age1 +
               ", age2Name='" + age2Name + '\'' +
               ", age2Description='" + age2Description + '\'' +
               ", age2=" + age2 +
               ", age3=" + age3 +
               '}';
    }
}