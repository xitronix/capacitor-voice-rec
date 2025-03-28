package com.xitronix.capacitorvoicerec;

import com.getcapacitor.JSObject;

public class RecordData {

    private String mimeType;
    private long msDuration;
    private String filePath;

    public RecordData() {}

    public RecordData(long msDuration, String mimeType, String filePath) {
        this.msDuration = msDuration;
        this.mimeType = mimeType;
        this.filePath = filePath;
    }

    public long getMsDuration() {
        return msDuration;
    }

    public void setMsDuration(long msDuration) {
        this.msDuration = msDuration;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public JSObject toJSObject() {
        JSObject toReturn = new JSObject();
        toReturn.put("msDuration", msDuration);
        toReturn.put("mimeType", mimeType);
        toReturn.put("filePath", filePath);
        return toReturn;
    }
}
