package com.example.booking.exception;

public class SlotAlreadyBookedException extends RuntimeException {

    public SlotAlreadyBookedException(Long slotId) {
        super("Slot " + slotId + " is already booked");
    }
}
