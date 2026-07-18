package com.whatsappmvp.config;

import com.whatsappmvp.infrastructure.persistence.entity.RoleEntity;
import com.whatsappmvp.infrastructure.persistence.entity.UserEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.RoleJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Crea el usuario ADMIN inicial al arrancar si no existe.
 * Los datos vienen de variables de entorno (app.admin.*)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserJpaRepository userRepository;
    private final RoleJpaRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    @Override
    public void run(String... args) {
        String adminEmail = props.getAdmin().getEmail();

        var existingUser = userRepository.findByEmail(adminEmail);

        if (existingUser.isPresent()) {
            UserEntity user = existingUser.get();
            String currentHash = user.getPassword();
            String newRawPassword = props.getAdmin().getPassword();

            if (!passwordEncoder.matches(newRawPassword, currentHash)) {
                user.setPassword(passwordEncoder.encode(newRawPassword));
                userRepository.save(user);
                log.info("[Init] Admin password updated for: {}", adminEmail);
            } else {
                log.info("[Init] Admin user already exists: {}", adminEmail);
            }
            return;
        }

        RoleEntity adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException("Role ADMIN not found — check V2 migration"));

        UserEntity admin = UserEntity.builder()
                .email(adminEmail)
                .password(passwordEncoder.encode(props.getAdmin().getPassword()))
                .fullName(props.getAdmin().getFullName())
                .isActive(true)
                .roles(Set.of(adminRole))
                .build();

        userRepository.save(admin);
        log.info("[Init] Admin user created: {}", adminEmail);
    }
}
