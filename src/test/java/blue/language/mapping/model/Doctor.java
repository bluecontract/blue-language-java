package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("Doctor-BlueId")
public class Doctor extends Person {
    private String specialization;

    public String getSpecialization() {
        return specialization;
    }

    public void setSpecialization(String specialization) {
        this.specialization = specialization;
    }
}
