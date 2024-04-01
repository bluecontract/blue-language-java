package blue.language.samples.serialization;

import blue.language.model.BlueId;

import java.util.List;

@BlueId("68Y1GazJSKJL1irezxJ3S4ZE7UCVXVswTRAhJf5RatK8")
public class Pet {
    private String name;
    private Integer age;
    private Double weightKg;
    private List<Toy> favouriteToys;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public Double getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(Double weightKg) {
        this.weightKg = weightKg;
    }

    public List<Toy> getFavouriteToys() {
        return favouriteToys;
    }

    public void setFavouriteToys(List<Toy> favouriteToys) {
        this.favouriteToys = favouriteToys;
    }
}