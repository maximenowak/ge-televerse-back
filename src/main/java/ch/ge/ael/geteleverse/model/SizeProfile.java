package ch.ge.ael.geteleverse.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum SizeProfile {
    S, M, L, XL;

    public static final SizeProfile DEFAULT = S;

    @JsonCreator
    public static SizeProfile fromJson(String value) {
        if (value == null || value.isBlank()) return DEFAULT;
        try {
            return SizeProfile.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
