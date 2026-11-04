package com.example.booking.service;

import com.example.booking.dto.BookingResponse;
import com.example.booking.entity.*;
import com.example.booking.exception.*;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.SlotRepository;
import com.example.booking.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private SlotRepository slotRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private BookingService bookingService;

    private User alice;
    private User bob;
    private Slot slot;

    @BeforeEach
    void setUp() {
        alice = user(1L, "alice");
        bob = user(2L, "bob");
        slot = new Slot();
        slot.setId(10L);
        slot.setStartTime(LocalDateTime.now().plusDays(1));
        slot.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        slot.setStatus(SlotStatus.AVAILABLE);
    }

    @Test
    void bookSlot_success_marksSlotBookedAndCreatesActiveBooking() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(slotRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(slot));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(100L);
            return b;
        });

        BookingResponse response = bookingService.bookSlot(10L, "alice");

        assertEquals(100L, response.id());
        assertEquals(10L, response.slotId());
        assertEquals(1L, response.userId());
        assertEquals(BookingStatus.ACTIVE, response.status());
        assertEquals(SlotStatus.BOOKED, slot.getStatus());
        verify(slotRepository).save(slot);
    }

    @Test
    void bookSlot_alreadyBooked_throwsConflictAndSavesNothing() {
        slot.setStatus(SlotStatus.BOOKED);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(slotRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(slot));

        SlotAlreadyBookedException ex =
                assertThrows(SlotAlreadyBookedException.class, () -> bookingService.bookSlot(10L, "alice"));

        assertTrue(ex.getMessage().contains("10"));
        verify(bookingRepository, never()).save(any());
        verify(slotRepository, never()).save(any());
    }

    @Test
    void bookSlot_slotNotFound_throwsNotFound() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(slotRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bookingService.bookSlot(99L, "alice"));
    }

    @Test
    void bookSlot_userNotFound_throwsNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bookingService.bookSlot(10L, "ghost"));
        verifyNoInteractions(slotRepository);
    }

    @Test
    void cancelOwnBooking_success_freesSlot() {
        slot.setStatus(SlotStatus.BOOKED);
        Booking booking = booking(100L, alice, slot, BookingStatus.ACTIVE);
        when(bookingRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(booking));
        when(slotRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(slot));

        BookingResponse response = bookingService.cancelOwnBooking(100L, "alice");

        assertEquals(BookingStatus.CANCELLED, response.status());
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertEquals(SlotStatus.AVAILABLE, slot.getStatus());
        verify(bookingRepository).save(booking);
        verify(slotRepository).save(slot);
    }

    @Test
    void cancelOwnBooking_someoneElsesBooking_isForbidden() {
        slot.setStatus(SlotStatus.BOOKED);
        Booking booking = booking(100L, alice, slot, BookingStatus.ACTIVE);
        when(bookingRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(booking));

        assertThrows(ForbiddenOperationException.class, () -> bookingService.cancelOwnBooking(100L, "bob"));

        assertEquals(BookingStatus.ACTIVE, booking.getStatus());
        assertEquals(SlotStatus.BOOKED, slot.getStatus());
        verifyNoInteractions(slotRepository);
    }

    @Test
    void cancelOwnBooking_alreadyCancelled_throwsInvalidState() {
        Booking booking = booking(100L, alice, slot, BookingStatus.CANCELLED);
        when(bookingRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(booking));

        assertThrows(InvalidBookingStateException.class, () -> bookingService.cancelOwnBooking(100L, "alice"));
    }

    @Test
    void cancelOwnBooking_notFound_throwsNotFound() {
        when(bookingRepository.findByIdForUpdate(5L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bookingService.cancelOwnBooking(5L, "alice"));
    }

    @Test
    void cancelOwnBooking_slotMissing_throwsNotFound() {
        Booking booking = booking(100L, alice, slot, BookingStatus.ACTIVE);
        when(bookingRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(booking));
        when(slotRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bookingService.cancelOwnBooking(100L, "alice"));
    }

    @Test
    void cancelAnyBooking_adminCanCancelAnotherUsersBooking() {
        slot.setStatus(SlotStatus.BOOKED);
        Booking booking = booking(100L, bob, slot, BookingStatus.ACTIVE);
        when(bookingRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(booking));
        when(slotRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(slot));

        BookingResponse response = bookingService.cancelAnyBooking(100L);

        assertEquals(BookingStatus.CANCELLED, response.status());
        assertEquals(SlotStatus.AVAILABLE, slot.getStatus());
    }

    private static User user(Long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        u.setPassword("x");
        u.setRole(Role.ROLE_USER);
        return u;
    }

    private static Booking booking(Long id, User user, Slot slot, BookingStatus status) {
        Booking b = new Booking();
        b.setId(id);
        b.setUser(user);
        b.setSlot(slot);
        b.setStatus(status);
        return b;
    }
}
