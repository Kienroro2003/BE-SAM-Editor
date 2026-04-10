package com.sam.besameditor.repositories;

import com.sam.besameditor.models.EmailVerificationCode;
import com.sam.besameditor.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findTopByUserAndUsedFalseOrderByCreatedAtDesc(User user);

    @Modifying
    @Transactional
    @Query("UPDATE EmailVerificationCode e SET e.used = true WHERE e.user = :user")
    void markAllUsedByUser(User user);
}
