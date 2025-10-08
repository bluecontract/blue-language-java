package blue.language.processor.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("CutOffProbe")
public class CutOffProbe extends HandlerContract {

    private boolean emitBefore;
    private String preEmitKind;
    private String patchPointer;
    private Integer patchValue;
    private boolean emitAfter;
    private String postEmitKind;
    private String postPatchPointer;
    private Integer postPatchValue;

    public boolean isEmitBefore() {
        return emitBefore;
    }

    public CutOffProbe emitBefore(boolean emitBefore) {
        this.emitBefore = emitBefore;
        return this;
    }

    public void setEmitBefore(boolean emitBefore) {
        this.emitBefore = emitBefore;
    }

    public String getPreEmitKind() {
        return preEmitKind;
    }

    public CutOffProbe preEmitKind(String preEmitKind) {
        this.preEmitKind = preEmitKind;
        return this;
    }

    public void setPreEmitKind(String preEmitKind) {
        this.preEmitKind = preEmitKind;
    }

    public String getPatchPointer() {
        return patchPointer;
    }

    public CutOffProbe patchPointer(String patchPointer) {
        this.patchPointer = patchPointer;
        return this;
    }

    public void setPatchPointer(String patchPointer) {
        this.patchPointer = patchPointer;
    }

    public Integer getPatchValue() {
        return patchValue;
    }

    public CutOffProbe patchValue(Integer patchValue) {
        this.patchValue = patchValue;
        return this;
    }

    public void setPatchValue(Integer patchValue) {
        this.patchValue = patchValue;
    }

    public boolean isEmitAfter() {
        return emitAfter;
    }

    public CutOffProbe emitAfter(boolean emitAfter) {
        this.emitAfter = emitAfter;
        return this;
    }

    public void setEmitAfter(boolean emitAfter) {
        this.emitAfter = emitAfter;
    }

    public String getPostEmitKind() {
        return postEmitKind;
    }

    public CutOffProbe postEmitKind(String postEmitKind) {
        this.postEmitKind = postEmitKind;
        return this;
    }

    public void setPostEmitKind(String postEmitKind) {
        this.postEmitKind = postEmitKind;
    }

    public String getPostPatchPointer() {
        return postPatchPointer;
    }

    public CutOffProbe postPatchPointer(String postPatchPointer) {
        this.postPatchPointer = postPatchPointer;
        return this;
    }

    public void setPostPatchPointer(String postPatchPointer) {
        this.postPatchPointer = postPatchPointer;
    }

    public Integer getPostPatchValue() {
        return postPatchValue;
    }

    public CutOffProbe postPatchValue(Integer postPatchValue) {
        this.postPatchValue = postPatchValue;
        return this;
    }

    public void setPostPatchValue(Integer postPatchValue) {
        this.postPatchValue = postPatchValue;
    }
}
