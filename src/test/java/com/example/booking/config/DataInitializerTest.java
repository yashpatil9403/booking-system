package com.example.booking.config;

import com.example.booking.entity.Role;
import com.example.booking.entity.User;
import com.example.booking.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @Test
    void run_createsAdminWhenMissing() {
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("pw")).thenReturn("ENCODED");
        DataInitializer initializer = new DataInitializer(userRepository, passwordEncoder, "admin", "pw");

        initializer.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(Role.ROLE_ADMIN, captor.getValue().getRole());
        assertEquals("ENCODED", captor.getValue().getPassword());
    }

    @Test
    void run_doesNothingWhenAdminExists() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);
        DataInitializer initializer = new DataInitializer(userRepository, passwordEncoder, "admin", "pw");

        initializer.run();

        verify(userRepository, never()).save(any());
    }
}
