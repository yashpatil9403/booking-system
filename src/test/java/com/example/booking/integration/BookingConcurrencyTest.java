package com.example.booking.integration;

import com.example.booking.entity.*;
import com.example.booking.exception.InvalidBookingStateException;
import com.example.booking.exception.SlotAlreadyBookedException;
import com.example.booking.dto.BookingResponse;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.SlotRepository;
import com.example.booking.repository.UserRepository;
import com.example.booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real transactions against the real H2 database (no mocks, no @Transactional on the test),
 * with many threads released at the same instant.
 */
@SpringBootTest
class BookingConcurrencyTest {

    private static final int THREADS = 20;

    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanBookingsAndSlots() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
    }

    @Test
    void manyUsersBookSameSlot_exactlyOneSucceeds() throws Exception {
        Slot slot = createSlot();
        List<String> usernames = createUsers(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        runConcurrently(usernames, username -> {
            try {
                bookingService.bookSlot(slot.getId(), username);
                success.incrementAndGet();
            } catch (SlotAlreadyBookedException ex) {
                conflicts.incrementAndGet();
            } catch (Exception ex) {
                unexpected.incrementAndGet();
            }
        });

        assertEquals(1, success.get(), "exactly one booking must succeed");
        assertEquals(THREADS - 1, conflicts.get(), "everyone else must get 'slot already booked'");
        assertEquals(0, unexpected.get(), "no unexpected errors");
        assertEquals(1, bookingRepository.countBySlotIdAndStatus(slot.getId(), BookingStatus.ACTIVE));
        assertEquals(SlotStatus.BOOKED, slotRepository.findById(slot.getId()).orElseThrow().getStatus());
    }

    @Test
    void concurrentCancelsOfSameBooking_exactlyOneSucceeds() throws Exception {
        Slot slot = createSlot();
        String owner = createUsers(1).get(0);
        BookingResponse booking = bookingService.bookSlot(slot.getId(), owner);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger alreadyCancelled = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        runConcurrently(java.util.Collections.nCopies(10, owner), username -> {
            try {
                bookingService.cancelOwnBooking(booking.id(), username);
                success.incrementAndGet();
            } catch (InvalidBookingStateException ex) {
                alreadyCancelled.incrementAndGet();
            } catch (Exception ex) {
                unexpected.incrementAndGet();
            }
        });

        assertEquals(1, success.get());
        assertEquals(9, alreadyCancelled.get());
        assertEquals(0, unexpected.get());
        assertEquals(0, bookingRepository.countBySlotIdAndStatus(slot.getId(), BookingStatus.ACTIVE));
        assertEquals(SlotStatus.AVAILABLE, slotRepository.findById(slot.getId()).orElseThrow().getStatus());
    }

    @Test
    void slotCanBeRebookedAfterCancellation() {
        Slot slot = createSlot();
        List<String> users = createUsers(2);

        BookingResponse first = bookingService.bookSlot(slot.getId(), users.get(0));
        bookingService.cancelOwnBooking(first.id(), users.get(0));
        bookingService.bookSlot(slot.getId(), users.get(1));

        assertEquals(1, bookingRepository.countBySlotIdAndStatus(slot.getId(), BookingStatus.ACTIVE));
        assertEquals(SlotStatus.BOOKED, slotRepository.findById(slot.getId()).orElseThrow().getStatus());
    }

    // ---------- helpers ----------

    private void runConcurrently(List<String> usernames, Consumer<String> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(usernames.size());
        CountDownLatch ready = new CountDownLatch(usernames.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (String username : usernames) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                action.accept(username);
            }));
        }
        ready.await();
        go.countDown(); // release all threads at once
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    private Slot createSlot() {
        Slot slot = new Slot();
        slot.setStartTime(LocalDateTime.now().plusDays(1));
        slot.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        slot.setStatus(SlotStatus.AVAILABLE);
        return slotRepository.save(slot);
    }

    private List<String> createUsers(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = new User();
            user.setUsername("u-" + UUID.randomUUID());
            user.setPassword("x");
            user.setRole(Role.ROLE_USER);
            names.add(userRepository.save(user).getUsername());
        }
        return names;
    }
}
