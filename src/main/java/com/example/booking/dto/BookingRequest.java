package com.example.booking.dto;

import jakarta.validation.constraints.NotNull;

public record BookingRequest(@NotNull(message = "slotId is required") Long slotId) {
}
