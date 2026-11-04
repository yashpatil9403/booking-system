package com.example.booking.dto;

import com.example.booking.entity.Slot;
import com.example.booking.entity.SlotStatus;

import java.time.LocalDateTime;

public record SlotResponse(Long id, LocalDateTime startTime, LocalDateTime endTime, SlotStatus status) {

    public static SlotResponse from(Slot slot) {
        return new SlotResponse(slot.getId(), slot.getStartTime(), slot.getEndTime(), slot.getStatus());
    }
}
