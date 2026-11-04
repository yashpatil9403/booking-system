package com.example.booking.service;

import com.example.booking.dto.BookingResponse;
import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;
import com.example.booking.entity.Slot;
import com.example.booking.entity.SlotStatus;
import com.example.booking.entity.User;
import com.example.booking.exception.ForbiddenOperationException;
import com.example.booking.exception.InvalidBookingStateException;
import com.example.booking.exception.ResourceNotFoundException;
import com.example.booking.exception.SlotAlreadyBookedException;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.SlotRepository;
import com.example.booking.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    public BookingService(SlotRepository slotRepository,
                          BookingRepository bookingRepository,
                          UserRepository userRepository) {
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    /**
     * One transaction: lock slot row -> check status -> mark BOOKED -> insert booking.
     * Any exception rolls back everything, so slot and booking can never disagree.
     */
    @Transactional
    public BookingResponse bookSlot(Long slotId, String username) {
        User user = findUser(username);

        // SELECT ... FOR UPDATE. Competing requests wait here until this transaction commits.
        Slot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found: " + slotId));

        if (slot.getStatus() != SlotStatus.AVAILABLE) {
            throw new SlotAlreadyBookedException(slotId);
        }

        slot.setStatus(SlotStatus.BOOKED);
        slotRepository.save(slot);

        Booking booking = new Booking();
        booking.setSlot(slot);
        booking.setUser(user);
        booking.setStatus(BookingStatus.ACTIVE);
        return BookingResponse.from(bookingRepository.save(booking));
    }

    @Transactional
    public BookingResponse cancelOwnBooking(Long bookingId, String username) {
        return cancel(bookingId, username, false);
    }

    @Transactional
    public BookingResponse cancelAnyBooking(Long bookingId) {
        return cancel(bookingId, null, true);
    }

    /**
     * Lock order is always booking -> slot (booking creation only ever locks the slot),
     * so concurrent book/cancel calls cannot deadlock.
     */
    private BookingResponse cancel(Long bookingId, String username, boolean admin) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (!admin && !booking.getUser().getUsername().equals(username)) {
            throw new ForbiddenOperationException("You can cancel only your own booking");
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new InvalidBookingStateException("Booking " + bookingId + " is already cancelled");
        }

        Slot slot = slotRepository.findByIdForUpdate(booking.getSlot().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found for booking " + bookingId));

        booking.setStatus(BookingStatus.CANCELLED);
        slot.setStatus(SlotStatus.AVAILABLE);
        bookingRepository.save(booking);
        slotRepository.save(slot);
        return BookingResponse.from(booking);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }
}
