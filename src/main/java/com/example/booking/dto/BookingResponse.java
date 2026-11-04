package com.example.booking.dto;

import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;

import java.time.LocalDateTime;

public record BookingResponse(Long id, Long slotId, Long userId, BookingStatus status, LocalDateTime createdAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getSlot().getId(),
                booking.getUser().getId(),
                booking.getStatus(),
                booking.getCreatedAt());
    }
}
