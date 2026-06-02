package com.otapp.hmis.support;

import com.otapp.hmis.iam.domain.User;
import com.otapp.hmis.iam.domain.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test fixture that (re)sets the seeded {@code admin} user's password to a known value using the
 * application's real {@link PasswordEncoder}. This makes the login integration tests independent of
 * the exact static BCrypt hash committed in {@code V2__seed_iam.sql}.
 */
@Component
public class AdminCredentialsFixture {

    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_PASSWORD = "password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminCredentialsFixture(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void ensureKnownAdminPassword() {
        User admin = userRepository.findByUsername(ADMIN_USERNAME)
                .orElseThrow(() -> new IllegalStateException("admin user was not seeded"));
        admin.changePasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        userRepository.save(admin);
    }
}
