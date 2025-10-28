package com.gocavgo.ridehail.auth;

import com.gocavgo.ridehail.auth.dto.AuthResponse;
import com.gocavgo.ridehail.auth.dto.LoginRequest;
import com.gocavgo.ridehail.auth.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final com.gocavgo.ridehail.location.DriverRepository driverRepository;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService, com.gocavgo.ridehail.location.DriverRepository driverRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.driverRepository = driverRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (userRepository.existsByPhone(req.getPhone())) {
            return ResponseEntity.badRequest().body("Phone already registered");
        }
        if (req.getRole() == Role.DRIVER && (req.getPlateNumber() == null || req.getPlateNumber().isBlank())) {
            return ResponseEntity.badRequest().body("plateNumber required for drivers");
        }

        User user = new User();
        user.setPhone(req.getPhone());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setFirstName(req.getFirstName());
        user.setLastName(req.getLastName());
        user.setRole(req.getRole());
        user = userRepository.save(user);

        if (user.getRole() == Role.DRIVER) {
            var d = new com.gocavgo.ridehail.location.Driver();
            d.setUserId(user.getId());
            d.setPlateNumber(req.getPlateNumber());
            d.setAvailable(true);
            driverRepository.save(d);
        }

        String token = jwtService.generateToken(user.getId(), user.getRole().name());
        return ResponseEntity.ok(new AuthResponse(token, user.getId(), user.getRole().name(), user.getFirstName(), user.getLastName(), user.getPhone()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        var userOpt = userRepository.findByPhone(req.getPhone());
        if (userOpt.isEmpty()) return ResponseEntity.status(401).body("Invalid credentials");
        var user = userOpt.get();
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(401).body("Invalid credentials");
        }
        String token = jwtService.generateToken(user.getId(), user.getRole().name());
        return ResponseEntity.ok(new AuthResponse(token, user.getId(), user.getRole().name(), user.getFirstName(), user.getLastName(), user.getPhone()));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return userRepository.findById(userId)
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(new AuthResponse(null, u.getId(), u.getRole().name(), u.getFirstName(), u.getLastName(), u.getPhone())))
                .orElseGet(() -> ResponseEntity.status(404).body("Not found"));
    }
}


