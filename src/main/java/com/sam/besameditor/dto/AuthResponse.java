package com.sam.besameditor.dto;

import com.sam.besameditor.models.*;
import com.sam.besameditor.repositories.*;
import com.sam.besameditor.services.*;
import com.sam.besameditor.controllers.*;
import com.sam.besameditor.security.*;
import com.sam.besameditor.config.*;
import com.sam.besameditor.exceptions.*;
import com.sam.besameditor.dto.*;


public class AuthResponse {
    private String accessToken;
    private String tokenType = "Bearer";
    private String email;
    private String fullName;

    public AuthResponse(String accessToken, String email, String fullName) {
        this.accessToken = accessToken;
        this.email = email;
        this.fullName = fullName;
    }

    public String getAccessToken() {
        return accessToken;
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
