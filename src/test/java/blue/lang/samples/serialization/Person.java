package blue.lang.samples.serialization;

import blue.lang.model.BlueId;

import java.util.List;

@BlueId("FGoRCi7Zy6hZyTyDyAy4PB3UKAckCryj944YUdfw8tF9")
public class Person {
    private String name;
    private String surname;
    private Integer age;
    private List<Pet> pets;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSurname() {
        return surname;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public List<Pet> getPets() {
        return pets;
    }

    public void setPets(List<Pet> pets) {
        this.pets = pets;
    }
}