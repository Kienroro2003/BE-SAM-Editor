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
import jakarta.validation.constraints.Size;

public class RegisterRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String fullName;

    @NotBlank
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
