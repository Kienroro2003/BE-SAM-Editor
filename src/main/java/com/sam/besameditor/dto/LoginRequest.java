package com.sam.besameditor.dto;

import com.sam.besameditor.models.*;
import com.sam.besameditor.repositories.*;
import com.sam.besameditor.services.*;
import com.sam.besameditor.controllers.*;
import com.sam.besameditor.security.*;
import com.sam.besameditor.config.*;
import com.sam.besameditor.exceptions.*;
import com.sam.besameditor.dto.*;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
