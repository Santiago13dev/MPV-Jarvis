package com.whatsappmvp.infrastructure.persistence.jpa;

import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationJpaRepository
        extends JpaRepository<ReservationEntity, UUID>, JpaSpecificationExecutor<ReservationEntity> {

    long countByStatus(ReservationStatus status);

    Optional<ReservationEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<ReservationEntity> findByReservationNumber(Long reservationNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReservationEntity r WHERE r.id = :id")
    Optional<ReservationEntity> findByIdForUpdate(@Param("id") UUID id);

    List<ReservationEntity> findByPhoneNumberAndStatusInOrderByCreatedAtDesc(
            String phoneNumber, Collection<ReservationStatus> statuses);

    /** Reservas activas de un cliente que aún no han pasado. */
    @Query("SELECT r FROM ReservationEntity r WHERE r.phoneNumber = :phone AND r.status IN :statuses " +
           "AND r.slotDate >= :fromDate ORDER BY r.slotDate ASC, r.slotTime ASC")
    List<ReservationEntity> findUpcomingByPhone(@Param("phone") String phone,
                                                @Param("statuses") Collection<ReservationStatus> statuses,
                                                @Param("fromDate") LocalDate fromDate);

    /** Suma de personas de reservas NORMAL (o VIP) activas en una franja, excluyendo una reserva. */
    @Query("SELECT COALESCE(SUM(COALESCE(r.partySize, 1)), 0) FROM ReservationEntity r " +
           "WHERE r.slotDate = :date AND r.slotTime = :time AND r.reservationType = :type " +
           "AND r.status IN :statuses AND r.id <> :excludeId")
    long sumPartySize(@Param("date") LocalDate date, @Param("time") LocalTime time,
                      @Param("type") ReservationType type,
                      @Param("statuses") Collection<ReservationStatus> statuses,
                      @Param("excludeId") UUID excludeId);

    @Query("SELECT COUNT(r) FROM ReservationEntity r " +
           "WHERE r.slotDate = :date AND r.slotTime = :time AND r.reservationType = :type " +
           "AND r.status IN :statuses AND r.id <> :excludeId")
    long countReservations(@Param("date") LocalDate date, @Param("time") LocalTime time,
                           @Param("type") ReservationType type,
                           @Param("statuses") Collection<ReservationStatus> statuses,
                           @Param("excludeId") UUID excludeId);

    /** Personas por franja en un día (para el panel de disponibilidad). [slotTime, sum]. */
    @Query("SELECT r.slotTime, COALESCE(SUM(COALESCE(r.partySize, 1)), 0), COUNT(r) FROM ReservationEntity r " +
           "WHERE r.slotDate = :date AND r.reservationType = :type AND r.status IN :statuses " +
           "GROUP BY r.slotTime")
    List<Object[]> sumByDate(@Param("date") LocalDate date, @Param("type") ReservationType type,
                             @Param("statuses") Collection<ReservationStatus> statuses);

    @Query("SELECT r FROM ReservationEntity r WHERE r.status IN :statuses AND r.holdExpiresAt IS NOT NULL " +
           "AND r.holdExpiresAt < :now")
    List<ReservationEntity> findExpiredHolds(@Param("statuses") Collection<ReservationStatus> statuses,
                                             @Param("now") LocalDateTime now);

    // ── Estadísticas del dashboard ────────────────────────────────────────
    @Query("SELECT COUNT(r) FROM ReservationEntity r WHERE r.slotDate = :date AND r.status IN :statuses")
    long countByDate(@Param("date") LocalDate date, @Param("statuses") Collection<ReservationStatus> statuses);

    @Query("SELECT COALESCE(SUM(COALESCE(r.partySize, 1)), 0) FROM ReservationEntity r " +
           "WHERE r.slotDate = :date AND r.status IN :statuses")
    long sumPeopleByDate(@Param("date") LocalDate date, @Param("statuses") Collection<ReservationStatus> statuses);

    @Query("SELECT COUNT(r) FROM ReservationEntity r WHERE r.slotDate = :date AND r.reservationType = :type " +
           "AND r.status IN :statuses")
    long countByDateAndType(@Param("date") LocalDate date, @Param("type") ReservationType type,
                            @Param("statuses") Collection<ReservationStatus> statuses);

    @Query("SELECT COALESCE(SUM(r.totalPrice), 0) FROM ReservationEntity r WHERE r.status = :status " +
           "AND r.slotDate >= :fromDate")
    java.math.BigDecimal sumTotalByStatusFrom(@Param("status") ReservationStatus status,
                                              @Param("fromDate") LocalDate fromDate);

    @Query("SELECT COUNT(r) FROM ReservationEntity r WHERE r.status IN :statuses AND r.slotDate >= :fromDate")
    long countByStatusInFrom(@Param("statuses") Collection<ReservationStatus> statuses,
                             @Param("fromDate") LocalDate fromDate);

    // ── Secuencia de números de reserva ───────────────────────────────────
    @Query(value = "SELECT nextval('reservation_number_seq')", nativeQuery = true)
    long nextReservationNumber();

    // ── Lock transaccional por franja (se libera al terminar la transacción) ──
    /** Devuelve '' — el cast evita el problema de mapeo del tipo void de PostgreSQL. */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:namespace, :slotKey) AS text)", nativeQuery = true)
    String lockSlot(@Param("namespace") int namespace, @Param("slotKey") int slotKey);
}
