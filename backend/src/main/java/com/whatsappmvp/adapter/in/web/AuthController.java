package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.request.LoginRequest;
import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.adapter.dto.response.LoginResponse;
import com.whatsappmvp.infrastructure.persistence.jpa.UserJpaRepository;
import com.whatsappmvp.infrastructure.security.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserJpaRepository userRepository;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest req) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword())
        );
        UserDetails userDetails = userDetailsService.loadUserByUsername(req.getEmail());
        String accessToken  = jwtTokenProvider.generateToken(userDetails);
        String refreshToken = jwtTokenProvider.generateRefreshToken(userDetails);

        var user = userRepository.findByEmail(req.getEmail()).orElseThrow();
        String role = user.getRoles().stream().findFirst().map(r -> r.getName()).orElse("OPERATOR");

        return ResponseEntity.ok(ApiResponse.ok(
            new LoginResponse(accessToken, refreshToken, user.getEmail(), user.getFullName(), role)
        ));
    }
}
