package com.example.booking.security;

import com.example.booking.entity.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-that-is-at-least-32-chars-long";

    @Test
    void generatedToken_isValidAndContainsUsername() {
        JwtService service = new JwtService(SECRET, 60_000);

        String token = service.generateToken("alice", Role.ROLE_USER);

        assertTrue(service.isValid(token));
        assertEquals("alice", service.extractUsername(token));
    }

    @Test
    void garbageToken_isInvalid() {
        JwtService service = new JwtService(SECRET, 60_000);

        assertFalse(service.isValid("not-a-jwt"));
    }

    @Test
    void tokenSignedWithDifferentKey_isInvalid() {
        JwtService issuer = new JwtService("another-secret-key-that-is-also-32-chars-long!!", 60_000);
        JwtService verifier = new JwtService(SECRET, 60_000);

        assertFalse(verifier.isValid(issuer.generateToken("alice", Role.ROLE_USER)));
    }

    @Test
    void expiredToken_isInvalid() {
        JwtService service = new JwtService(SECRET, -1_000);

        assertFalse(service.isValid(service.generateToken("alice", Role.ROLE_USER)));
    }
}
