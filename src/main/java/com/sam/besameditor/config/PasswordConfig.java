package com.sam.besameditor.config;

import com.sam.besameditor.models.*;
import com.sam.besameditor.repositories.*;
import com.sam.besameditor.services.*;
import com.sam.besameditor.controllers.*;
import com.sam.besameditor.security.*;
import com.sam.besameditor.config.*;
import com.sam.besameditor.exceptions.*;
import com.sam.besameditor.dto.*;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
