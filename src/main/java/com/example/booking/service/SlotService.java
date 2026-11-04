package com.example.booking.service;

import com.example.booking.dto.CreateSlotRequest;
import com.example.booking.dto.SlotResponse;
import com.example.booking.entity.Slot;
import com.example.booking.entity.SlotStatus;
import com.example.booking.exception.InvalidRequestException;
import com.example.booking.repository.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SlotService {

    private final SlotRepository slotRepository;

    public SlotService(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    @Transactional
    public SlotResponse createSlot(CreateSlotRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new InvalidRequestException("endTime must be after startTime");
        }
        Slot slot = new Slot();
        slot.setStartTime(request.startTime());
        slot.setEndTime(request.endTime());
        slot.setStatus(SlotStatus.AVAILABLE);
        return SlotResponse.from(slotRepository.save(slot));
    }

    @Transactional(readOnly = true)
    public List<SlotResponse> getSlots(SlotStatus status) {
        List<Slot> slots = status == null
                ? slotRepository.findAllByOrderByStartTimeAsc()
                : slotRepository.findByStatusOrderByStartTimeAsc(status);
        return slots.stream().map(SlotResponse::from).toList();
    }
}
