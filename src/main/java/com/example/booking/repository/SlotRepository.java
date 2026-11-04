package com.example.booking.repository;

import com.example.booking.entity.Slot;
import com.example.booking.entity.SlotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    /** SELECT ... FOR UPDATE: concurrent transactions block here until the lock holder commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Slot s WHERE s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") Long id);

    List<Slot> findAllByOrderByStartTimeAsc();

    List<Slot> findByStatusOrderByStartTimeAsc(SlotStatus status);
}
