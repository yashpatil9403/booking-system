package com.example.booking.service;

import com.example.booking.dto.AuthResponse;
import com.example.booking.dto.LoginRequest;
import com.example.booking.dto.RegisterRequest;
import com.example.booking.dto.UserResponse;
import com.example.booking.entity.Role;
import com.example.booking.entity.User;
import com.example.booking.exception.DuplicateUsernameException;
import com.example.booking.exception.InvalidCredentialsException;
import com.example.booking.repository.UserRepository;
import com.example.booking.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @InjectMocks private AuthService authService;

    @Test
    void register_success_encodesPasswordAndCreatesUserRole() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("ENCODED");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });

        UserResponse response = authService.register(new RegisterRequest("alice", "secret123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("ENCODED", captor.getValue().getPassword());
        assertEquals(Role.ROLE_USER, captor.getValue().getRole());
        assertEquals(7L, response.id());
        assertEquals("ROLE_USER", response.role());
    }

    @Test
    void register_duplicateUsername_throwsConflict() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThrows(DuplicateUsernameException.class,
                () -> authService.register(new RegisterRequest("alice", "secret123")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_success_returnsToken() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("ENCODED");
        user.setRole(Role.ROLE_USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "ENCODED")).thenReturn(true);
        when(jwtService.generateToken("alice", Role.ROLE_USER)).thenReturn("jwt-token");

        AuthResponse response = authService.login(new LoginRequest("alice", "secret123"));

        assertEquals("jwt-token", response.token());
        assertEquals("Bearer", response.tokenType());
        assertEquals("ROLE_USER", response.role());
    }

    @Test
    void login_unknownUser_throwsInvalidCredentials() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("ghost", "whatever")));
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("ENCODED");
        user.setRole(Role.ROLE_USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "ENCODED")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("alice", "wrong")));
        verifyNoInteractions(jwtService);
    }
}
