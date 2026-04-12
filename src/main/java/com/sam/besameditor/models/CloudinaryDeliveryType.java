package com.sam.besameditor.models;

public enum CloudinaryDeliveryType {
    UPLOAD("upload"),
    PRIVATE("private");

    private final String apiValue;

    CloudinaryDeliveryType(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static CloudinaryDeliveryType resolve(CloudinaryDeliveryType deliveryType) {
        return deliveryType == null ? UPLOAD : deliveryType;
    }
}
