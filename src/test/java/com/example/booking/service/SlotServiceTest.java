package com.example.booking.service;

import com.example.booking.dto.CreateSlotRequest;
import com.example.booking.dto.SlotResponse;
import com.example.booking.entity.Slot;
import com.example.booking.entity.SlotStatus;
import com.example.booking.exception.InvalidRequestException;
import com.example.booking.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    @Mock private SlotRepository slotRepository;
    @InjectMocks private SlotService slotService;

    @Test
    void createSlot_success_savesAvailableSlot() {
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        when(slotRepository.save(any(Slot.class))).thenAnswer(inv -> {
            Slot s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        SlotResponse response = slotService.createSlot(new CreateSlotRequest(start, start.plusHours(1)));

        assertEquals(1L, response.id());
        assertEquals(SlotStatus.AVAILABLE, response.status());
        assertEquals(start, response.startTime());
    }

    @Test
    void createSlot_endNotAfterStart_throwsInvalidRequest() {
        LocalDateTime start = LocalDateTime.now().plusDays(1);

        assertThrows(InvalidRequestException.class,
                () -> slotService.createSlot(new CreateSlotRequest(start, start)));
        verify(slotRepository, never()).save(any());
    }

    @Test
    void getSlots_withoutFilter_returnsAll() {
        when(slotRepository.findAllByOrderByStartTimeAsc()).thenReturn(List.of(slot(1L, SlotStatus.AVAILABLE),
                slot(2L, SlotStatus.BOOKED)));

        List<SlotResponse> result = slotService.getSlots(null);

        assertEquals(2, result.size());
    }

    @Test
    void getSlots_withStatus_filtersByStatus() {
        when(slotRepository.findByStatusOrderByStartTimeAsc(SlotStatus.BOOKED))
                .thenReturn(List.of(slot(2L, SlotStatus.BOOKED)));

        List<SlotResponse> result = slotService.getSlots(SlotStatus.BOOKED);

        assertEquals(1, result.size());
        assertEquals(SlotStatus.BOOKED, result.get(0).status());
    }

    private static Slot slot(Long id, SlotStatus status) {
        Slot s = new Slot();
        s.setId(id);
        s.setStartTime(LocalDateTime.now().plusDays(1));
        s.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        s.setStatus(status);
        return s;
    }
}
