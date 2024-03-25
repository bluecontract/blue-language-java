package blue.lang.samples.serialization;

import blue.lang.model.BlueId;

import java.util.Set;

@BlueId("ESXYrxrWjvNT5tgdpHPhVVUAqjDq6dnToxxec5zU65gf")
public class Toy {

    public enum ToyKind {
        CHEW_TOY,
        INTERACTIVE_TOY,
        PLUSH_TOY,
        FETCH_TOY
        
    }

    private String name;
    private ToyKind kind;
    private String color;
    private Set<String> features;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ToyKind getKind() {
        return kind;
    }

    public void setKind(ToyKind kind) {
        this.kind = kind;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public Set<String> getFeatures() {
        return features;
    }

    public void setFeatures(Set<String> features) {
        this.features = features;
    }
}