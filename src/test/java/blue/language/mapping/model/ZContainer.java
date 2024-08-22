package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

import java.util.List;

@TypeBlueId("ZContainer-BlueId")
public class ZContainer {
    public String containerName;
    public List<? extends Z> zList;
}