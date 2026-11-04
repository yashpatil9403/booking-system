package com.example.booking.controller;

import com.example.booking.dto.CreateSlotRequest;
import com.example.booking.dto.SlotResponse;
import com.example.booking.entity.SlotStatus;
import com.example.booking.service.SlotService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/slots")
public class SlotController {

    private final SlotService slotService;

    public SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SlotResponse createSlot(@Valid @RequestBody CreateSlotRequest request) {
        return slotService.createSlot(request);
    }

    @GetMapping
    public List<SlotResponse> getSlots(@RequestParam(required = false) SlotStatus status) {
        return slotService.getSlots(status);
    }
}
