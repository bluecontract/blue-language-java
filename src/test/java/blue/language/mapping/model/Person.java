package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("Person-BlueId")
public class Person {
    private String name;
    private String surname;
    private Integer age;

    public String getName() {
        return name;
    }

    public Person name(String name) {
        this.name = name;
        return this;
    }

    public String getSurname() {
        return surname;
    }

    public Person surname(String surname) {
        this.surname = surname;
        return this;
    }

    public Integer getAge() {
        return age;
    }

    public Person age(Integer age) {
        this.age = age;
        return this;
    }
}
