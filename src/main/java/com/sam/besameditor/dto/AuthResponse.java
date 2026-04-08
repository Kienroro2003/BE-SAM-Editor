package com.sam.besameditor.dto;

public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private String email;
    private String fullName;

    public AuthResponse(String accessToken, String email, String fullName) {
        this(accessToken, null, email, fullName);
    }

    public AuthResponse(String accessToken, String refreshToken, String email, String fullName) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.email = email;
        this.fullName = fullName;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }
}
