package com.whatsappmvp.application.service;

import com.whatsappmvp.application.reservation.Actor;
import com.whatsappmvp.application.reservation.AvailabilityModels.Pool;
import com.whatsappmvp.application.reservation.AvailabilityModels.PriceQuote;
import com.whatsappmvp.application.reservation.AvailabilityModels.Reason;
import com.whatsappmvp.application.reservation.AvailabilityModels.RequestCheck;
import com.whatsappmvp.application.reservation.AvailabilityModels.SlotAvailability;
import com.whatsappmvp.application.reservation.ReservationAuditService;
import com.whatsappmvp.application.reservation.ReservationAvailabilityService;
import com.whatsappmvp.application.reservation.ReservationCalendarService;
import com.whatsappmvp.application.reservation.ReservationNotificationEvent;
import com.whatsappmvp.application.reservation.ReservationNotificationEvent.Type;
import com.whatsappmvp.application.reservation.RestaurantSettingsService;
import com.whatsappmvp.domain.enums.AuditActorType;
import com.whatsappmvp.domain.enums.ReceiptStatus;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.domain.exception.BusinessException;
import com.whatsappmvp.domain.exception.NotFoundException;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationReceiptEntity;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationReceiptJpaRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * ReservationService — ÚNICO punto de escritura de reservas.
 *
 *   WhatsApp → ReservationService      Dashboard → ReservationService      API → ReservationService
 *
 * Concurrencia: cada operación que consume cupo toma un lock transaccional de PostgreSQL
 * (pg_advisory_xact_lock) por franja (fecha + hora), y DENTRO de ese lock verifica disponibilidad e inserta.
 * Así "verificar + crear" es atómico entre clientes concurrentes. Respaldos adicionales en BD:
 * índice único parcial para VIP e índice único de idempotency_key.
 * Los locks de varias franjas (modificaciones) se toman en orden determinista para evitar deadlocks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final int SLOT_LOCK_NAMESPACE = 7001;

    private final ReservationJpaRepository reservationRepository;
    private final ReservationReceiptJpaRepository receiptRepository;
    private final ReservationAvailabilityService availability;
    private final RestaurantSettingsService settingsService;
    private final ReservationCalendarService calendar;
    private final com.whatsappmvp.application.reservation.ReservationPricing pricing;
    private final ReservationAuditService audit;
    private final ConversationJpaRepository conversationRepository;
    private final ApplicationEventPublisher events;

    // ──────────────────────────────────────────────────────────────────────
    // Comandos y resultados
    // ──────────────────────────────────────────────────────────────────────

    public record CreateCommand(String customerName, String phoneNumber, String receiptPhone,
                                ReservationType type, LocalDate date, LocalTime time, int partySize,
                                String notes, UUID conversationId, String idempotencyKey,
                                ReservationStatus initialStatus, Actor actor) {}

    /** null = sin cambio. {@code requiredOwnerPhone} != null → solo el dueño puede modificar. */
    public record ModifyCommand(LocalDate date, LocalTime time, Integer partySize, ReservationType type,
                                String requiredOwnerPhone, Actor actor) {}

    /**
     * Resultado de crear/modificar. Si {@code check.available()} es false NO se hizo ningún cambio y
     * {@code check} trae el motivo y las alternativas reales.
     */
    public record Outcome(ReservationEntity reservation, RequestCheck check, boolean duplicate,
                          BigDecimal priceDelta, boolean unchanged) {
        public boolean ok() { return reservation != null && (check == null || check.available()); }
        static Outcome rejected(RequestCheck c, ReservationEntity current) {
            return new Outcome(current, c, false, null, false);
        }
        static Outcome created(ReservationEntity r, RequestCheck c) {
            return new Outcome(r, c, false, null, false);
        }
        static Outcome duplicate(ReservationEntity r) {
            return new Outcome(r, null, true, null, false);
        }
        static Outcome modified(ReservationEntity r, RequestCheck c, BigDecimal delta) {
            return new Outcome(r, c, false, delta, false);
        }
        static Outcome unchanged(ReservationEntity r) {
            return new Outcome(r, null, false, null, true);
        }
    }

    public record SearchFilter(LocalDate from, LocalDate to, Collection<ReservationStatus> statuses,
                               ReservationType type, String query) {}

    public record Stats(LocalDate date, long reservationsToday, long peopleToday, long vipToday,
                        BigDecimal confirmedRevenueFromToday, long pendingPayments, int remainingCapacityToday) {}

    // ──────────────────────────────────────────────────────────────────────
    // Crear
    // ──────────────────────────────────────────────────────────────────────

    @Transactional
    public Outcome create(CreateCommand c) {
        if (c.customerName() == null || c.customerName().isBlank()) {
            throw new BusinessException("El nombre del cliente es obligatorio");
        }
        if (c.phoneNumber() == null || c.phoneNumber().isBlank()) {
            throw new BusinessException("El teléfono del cliente es obligatorio");
        }
        if (c.date() == null || c.time() == null) {
            throw new BusinessException("La fecha y la hora son obligatorias");
        }
        Actor actor = c.actor() != null ? c.actor() : Actor.system();
        ReservationType type = c.type() != null ? c.type() : ReservationType.NORMAL;

        // Idempotencia (1/2): antes del lock, camino rápido
        if (c.idempotencyKey() != null) {
            var existing = reservationRepository.findByIdempotencyKey(c.idempotencyKey());
            if (existing.isPresent()) {
                log.info("[RESERVATION] duplicate request ignored key={} reservationId={}",
                        c.idempotencyKey(), existing.get().getId());
                return Outcome.duplicate(existing.get());
            }
        }

        lockSlots(List.of(slotKey(c.date(), c.time())));

        // Idempotencia (2/2): tras tomar el lock, otra transacción concurrente con la misma llave ya pudo commitear
        if (c.idempotencyKey() != null) {
            var existing = reservationRepository.findByIdempotencyKey(c.idempotencyKey());
            if (existing.isPresent()) {
                return Outcome.duplicate(existing.get());
            }
        }

        RequestCheck check = availability.check(c.date(), c.time(), type, c.partySize(), null);
        if (!check.available()) {
            log.info("[RESERVATION] create rejected customer={} type={} date={} time={} partySize={} reason={}",
                    mask(c.phoneNumber()), type, c.date(), c.time(), c.partySize(), check.reason());
            return Outcome.rejected(check, null);
        }

        RestaurantSettingsEntity settings = settingsService.get();
        PriceQuote price = check.price();
        ReservationStatus status = c.initialStatus() != null ? c.initialStatus() : ReservationStatus.PAYMENT_PENDING;
        if (status != ReservationStatus.PENDING && status != ReservationStatus.PAYMENT_PENDING
                && status != ReservationStatus.CONFIRMED) {
            throw new BusinessException("Estado inicial no permitido: " + status);
        }
        LocalDateTime now = calendar.nowDateTime();

        ReservationEntity entity = ReservationEntity.builder()
                .reservationNumber(reservationRepository.nextReservationNumber())
                .customerName(c.customerName().trim())
                .phoneNumber(c.phoneNumber().trim())
                .receiptPhone(blankToNull(c.receiptPhone()))
                .reservationType(type)
                .slotDate(c.date())
                .slotTime(c.time())
                .partySize(c.partySize())
                .unitPrice(price.unitPrice())
                .totalPrice(price.totalPrice())
                .status(status)
                .notes(blankToNull(c.notes()))
                .idempotencyKey(c.idempotencyKey())
                .holdExpiresAt(ReservationStatus.HOLDS.contains(status)
                        ? now.plusMinutes(settings.getHoldMinutes()) : null)
                .conversation(c.conversationId() != null
                        ? conversationRepository.getReferenceById(c.conversationId()) : null)
                .build();
        if (status == ReservationStatus.CONFIRMED) {
            entity.setConfirmedAt(now);
            entity.setConfirmedBy(actor.id());
        }
        entity.syncLegacyFields();

        ReservationEntity saved;
        try {
            saved = reservationRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // Red de seguridad de BD (VIP duplicado, llave de idempotencia): el lock hace esto improbable.
            log.warn("[RESERVATION] integrity violation on create: {}", e.getMostSpecificCause().getMessage());
            throw new BusinessException("No fue posible crear la reserva: el horario acaba de ser ocupado",
                    HttpStatus.CONFLICT);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", type);
        meta.put("date", c.date());
        meta.put("time", c.time());
        meta.put("partySize", c.partySize());
        meta.put("totalPrice", price.totalPrice());
        meta.put("number", saved.getReservationNumber());
        audit.record(saved.getId(), ReservationAuditService.CREATED, actor, null, status, meta);

        log.info("[RESERVATION] created reservationId={} number={} customer={} type={} date={} time={} partySize={} status={}",
                saved.getId(), saved.getReservationNumber(), mask(saved.getPhoneNumber()), type,
                c.date(), c.time(), c.partySize(), status);
        return Outcome.created(saved, check);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Modificar
    // ──────────────────────────────────────────────────────────────────────

    @Transactional
    public Outcome modify(UUID id, ModifyCommand m) {
        ReservationEntity r = reservationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Reserva no encontrada"));
        requireOwner(r, m.requiredOwnerPhone());
        if (!r.getStatus().isActive()) {
            throw new BusinessException("La reserva #" + r.getReservationNumber() + " está "
                    + r.getStatus() + " y no se puede modificar", HttpStatus.CONFLICT);
        }
        Actor actor = m.actor() != null ? m.actor() : Actor.system();

        ReservationType newType = m.type() != null ? m.type() : r.getReservationType();
        LocalDate newDate = m.date() != null ? m.date() : r.getSlotDate();
        LocalTime newTime = m.time() != null ? m.time() : r.getSlotTime();
        int newParty = m.partySize() != null ? m.partySize() : Objects.requireNonNullElse(r.getPartySize(), 1);

        boolean same = newType == r.getReservationType() && newDate.equals(r.getSlotDate())
                && newTime.equals(r.getSlotTime()) && newParty == Objects.requireNonNullElse(r.getPartySize(), 1);
        if (same) return Outcome.unchanged(r);

        lockSlots(List.of(slotKey(r.getSlotDate(), r.getSlotTime()), slotKey(newDate, newTime)));

        // La propia reserva se excluye: sus cupos actuales quedan libres para el cambio
        RequestCheck check = availability.check(newDate, newTime, newType, newParty, r.getId());
        if (!check.available()) {
            log.info("[RESERVATION] modify rejected reservationId={} reason={}", r.getId(), check.reason());
            return Outcome.rejected(check, r);
        }

        Map<String, Object> before = snapshot(r);
        BigDecimal oldTotal = Objects.requireNonNullElse(r.getTotalPrice(), BigDecimal.ZERO);

        r.setReservationType(newType);
        r.setSlotDate(newDate);
        r.setSlotTime(newTime);
        r.setPartySize(newParty);
        r.setUnitPrice(check.price().unitPrice());
        r.setTotalPrice(check.price().totalPrice());
        BigDecimal delta = r.getTotalPrice().subtract(oldTotal);
        if (delta.signum() != 0 && r.getStatus() == ReservationStatus.CONFIRMED) {
            String note = "Cambio de precio tras modificación: diferencia " + delta.toPlainString() + " COP (revisar con el cliente)";
            r.setNotes(r.getNotes() == null ? note : r.getNotes() + "\n" + note);
        }
        if (ReservationStatus.HOLDS.contains(r.getStatus())) {
            r.setHoldExpiresAt(calendar.nowDateTime().plusMinutes(settingsService.get().getHoldMinutes()));
        }
        r.syncLegacyFields();
        reservationRepository.saveAndFlush(r);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("before", before);
        meta.put("after", snapshot(r));
        meta.put("priceDelta", delta);
        audit.record(r.getId(), ReservationAuditService.UPDATED, actor, r.getStatus(), r.getStatus(), meta);

        log.info("[RESERVATION] updated reservationId={} number={} date={} time={} partySize={} type={} priceDelta={}",
                r.getId(), r.getReservationNumber(), newDate, newTime, newParty, newType, delta);
        return Outcome.modified(r, check, delta);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cancelar (nunca borra físicamente)
    // ──────────────────────────────────────────────────────────────────────

    @Transactional
    public ReservationEntity cancel(UUID id, Actor actor, String reason, String requiredOwnerPhone) {
        ReservationEntity r = reservationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Reserva no encontrada"));
        requireOwner(r, requiredOwnerPhone);
        if (r.getStatus().isTerminal()) {
            return r; // idempotente: ya estaba cancelada/expirada
        }
        ReservationStatus previous = r.getStatus();
        r.setStatus(ReservationStatus.CANCELLED);
        r.setCancelledAt(calendar.nowDateTime());
        r.setCancelledBy(actor.id());
        r.setCancelReason(blankToNull(reason));
        r.setHoldExpiresAt(null);
        reservationRepository.saveAndFlush(r);

        Map<String, Object> meta = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) meta.put("reason", reason);
        audit.record(r.getId(), ReservationAuditService.CANCELLED, actor, previous, ReservationStatus.CANCELLED, meta);
        log.info("[RESERVATION] cancelled reservationId={} number={} by={} previous={}",
                r.getId(), r.getReservationNumber(), actor.type(), previous);
        if (actor.type() == AuditActorType.ADMIN) {
            events.publishEvent(new ReservationNotificationEvent(r.getId(), Type.CANCELLED_BY_ADMIN, reason));
        }
        return r;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Pagos / comprobantes
    // ──────────────────────────────────────────────────────────────────────

    @Transactional
    public ReservationEntity registerReceipt(UUID id, String filePath, String mimeType, String waMessageId, Actor actor) {
        if (waMessageId != null) {
            var dup = receiptRepository.findByWaMessageId(waMessageId);
            if (dup.isPresent()) {
                log.info("[RESERVATION] duplicate receipt ignored waMessageId={}", waMessageId);
                return reservationRepository.findById(dup.get().getReservationId())
                        .orElseThrow(() -> new NotFoundException("Reserva no encontrada"));
            }
        }
        ReservationEntity r = reservationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Reserva no encontrada"));
        if (r.getStatus() != ReservationStatus.PENDING && r.getStatus() != ReservationStatus.PAYMENT_PENDING
                && r.getStatus() != ReservationStatus.PAYMENT_RECEIPT_SENT) {
            throw new BusinessException("La reserva #" + r.getReservationNumber() + " está " + r.getStatus()
                    + " y no recibe comprobantes", HttpStatus.CONFLICT);
        }
        LocalDateTime now = calendar.nowDateTime();
        receiptRepository.save(ReservationReceiptEntity.builder()
                .reservationId(r.getId()).filePath(filePath).mimeType(mimeType)
                .waMessageId(waMessageId).status(ReceiptStatus.RECEIVED).uploadedAt(now).build());

        ReservationStatus previous = r.getStatus();
        r.setStatus(ReservationStatus.PAYMENT_RECEIPT_SENT);
        r.setPaymentReceiptUploadedAt(now);
        r.setHoldExpiresAt(null); // mientras el asesor revisa, el hold no vence
        reservationRepository.saveAndFlush(r);

        audit.record(r.getId(), ReservationAuditService.RECEIPT_UPLOADED, actor, previous,
                ReservationStatus.PAYMENT_RECEIPT_SENT, Map.of("file", filePath));
        log.info("[RESERVATION] payment receipt uploaded reservationId={} number={}", r.getId(), r.getReservationNumber());
        events.publishEvent(new ReservationNotificationEvent(r.getId(), Type.RECEIPT_RECEIVED, null));
        return r;
    }

    /** Confirma la reserva (asesor/administrador). Idempotente si ya estaba confirmada. */
    @Transactional
    public ReservationEntity confirm(UUID id, Actor actor) {
        ReservationEntity r = reservationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Reserva no encontrada"));
        if (r.getStatus() == ReservationStatus.CONFIRMED) return r;
        if (!r.getStatus().canTransitionTo(ReservationStatus.CONFIRMED)) {
            throw new BusinessException("La reserva #" + r.getReservationNumber() + " está " + r.getStatus()
                    + " y no se puede confirmar. Si el cliente aún la quiere, crea una nueva.", HttpStatus.CONFLICT);
        }
        LocalDateTime now = calendar.nowDateTime();
        for (ReservationReceiptEntity rc : receiptRepository.findByReservationIdAndStatus(r.getId(), ReceiptStatus.RECEIVED)) {
            rc.setStatus(ReceiptStatus.ACCEPTED);
            rc.setReviewedBy(actor.id());
            rc.setReviewedAt(now);
            receiptRepository.save(rc);
        }
        ReservationStatus previous = r.getStatus();
        r.setStatus(ReservationStatus.CONFIRMED);
        r.setConfirmedAt(now);
        r.setConfirmedBy(actor.id());
        r.setHoldExpiresAt(null);
        reservationRepository.saveAndFlush(r);

        audit.record(r.getId(), ReservationAuditService.CONFIRMED, actor, previous, ReservationStatus.CONFIRMED, null);
        log.info("[RESERVATION] confirmed reservationId={} number={} by={}", r.getId(), r.getReservationNumber(), actor.id());
        events.publishEvent(new ReservationNotificationEvent(r.getId(), Type.CONFIRMED, null));
        return r;
    }

    /** El asesor rechaza el comprobante: vuelve a PAYMENT_PENDING con un hold nuevo. */
    @Transactional
    public ReservationEntity rejectReceipt(UUID id, Actor actor, String note) {
        ReservationEntity r = reservationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Reserva no encontrada"));
        if (r.getStatus() != ReservationStatus.PAYMENT_RECEIPT_SENT) {
            throw new BusinessException("La reserva #" + r.getReservationNumber()
                    + " no tiene un comprobante pendiente de revisión", HttpStatus.CONFLICT);
        }
        LocalDateTime now = calendar.nowDateTime();
        for (ReservationReceiptEntity rc : receiptRepository.findByReservationIdAndStatus(r.getId(), ReceiptStatus.RECEIVED)) {
            rc.setStatus(ReceiptStatus.REJECTED);
            rc.setReviewedBy(actor.id());
            rc.setReviewedAt(now);
            rc.setReviewNote(blankToNull(note));
            receiptRepository.save(rc);
        }
        r.setStatus(ReservationStatus.PAYMENT_PENDING);
        r.setHoldExpiresAt(now.plusMinutes(settingsService.get().getHoldMinutes()));
        reservationRepository.saveAndFlush(r);

        Map<String, Object> meta = new LinkedHashMap<>();
        if (note != null && !note.isBlank()) meta.put("note", note);
        audit.record(r.getId(), ReservationAuditService.RECEIPT_REJECTED, actor,
                ReservationStatus.PAYMENT_RECEIPT_SENT, ReservationStatus.PAYMENT_PENDING, meta);
        log.info("[RESERVATION] receipt rejected reservationId={} number={} by={}", r.getId(), r.getReservationNumber(), actor.id());
        events.publishEvent(new ReservationNotificationEvent(r.getId(), Type.RECEIPT_REJECTED, note));
        return r;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Expiración de holds (la invoca ReservationExpirationJob)
    // ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UUID> findExpiredHoldIds() {
        return reservationRepository.findExpiredHolds(ReservationStatus.HOLDS, calendar.nowDateTime())
                .stream().map(ReservationEntity::getId).toList();
    }

    /** @return true si la reserva se expiró en esta llamada. */
    @Transactional
    public boolean expire(UUID id) {
        ReservationEntity r = reservationRepository.findByIdForUpdate(id).orElse(null);
        if (r == null) return false;
        // Re-verificar con el lock: el cliente pudo enviar comprobante justo ahora
        if (!ReservationStatus.HOLDS.contains(r.getStatus()) || r.getHoldExpiresAt() == null
                || !r.getHoldExpiresAt().isBefore(calendar.nowDateTime())) {
            return false;
        }
        ReservationStatus previous = r.getStatus();
        r.setStatus(ReservationStatus.EXPIRED);
        reservationRepository.saveAndFlush(r);
        audit.record(r.getId(), ReservationAuditService.EXPIRED, Actor.system(), previous, ReservationStatus.EXPIRED, null);
        log.info("[RESERVATION] expired reservationId={} number={}", r.getId(), r.getReservationNumber());
        events.publishEvent(new ReservationNotificationEvent(r.getId(), Type.EXPIRED, null));
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Consultas
    // ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReservationEntity getById(UUID id) {
        return reservationRepository.findById(id).orElseThrow(() -> new NotFoundException("Reserva no encontrada"));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ReservationEntity> findByNumber(Long number) {
        return reservationRepository.findByReservationNumber(number);
    }

    /** Reservas activas y futuras de un cliente (para consultar/modificar/cancelar por WhatsApp). */
    @Transactional(readOnly = true)
    public List<ReservationEntity> findUpcomingActiveByPhone(String phone) {
        return reservationRepository.findUpcomingByPhone(phone, ReservationStatus.ACTIVE, calendar.today());
    }

    @Transactional(readOnly = true)
    public List<ReservationReceiptEntity> getReceipts(UUID reservationId) {
        return receiptRepository.findByReservationIdOrderByUploadedAtDesc(reservationId);
    }

    @Transactional(readOnly = true)
    public ReservationReceiptEntity getReceipt(UUID reservationId, UUID receiptId) {
        ReservationReceiptEntity rc = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new NotFoundException("Comprobante no encontrado"));
        if (!rc.getReservationId().equals(reservationId)) throw new NotFoundException("Comprobante no encontrado");
        return rc;
    }

    @Transactional(readOnly = true)
    public Page<ReservationEntity> search(SearchFilter f, Pageable pageable) {
        Specification<ReservationEntity> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.from() != null) ps.add(cb.greaterThanOrEqualTo(root.get("slotDate"), f.from()));
            if (f.to() != null) ps.add(cb.lessThanOrEqualTo(root.get("slotDate"), f.to()));
            if (f.statuses() != null && !f.statuses().isEmpty()) ps.add(root.get("status").in(f.statuses()));
            if (f.type() != null) ps.add(cb.equal(root.get("reservationType"), f.type()));
            if (f.query() != null && !f.query().isBlank()) {
                String like = "%" + f.query().trim().toLowerCase() + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("customerName")), like));
                any.add(cb.like(root.get("phoneNumber"), "%" + f.query().trim() + "%"));
                try {
                    any.add(cb.equal(root.get("reservationNumber"), Long.parseLong(f.query().trim().replace("#", ""))));
                } catch (NumberFormatException ignored) { /* no es un número */ }
                ps.add(cb.or(any.toArray(new Predicate[0])));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return reservationRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        LocalDate today = calendar.today();
        var active = ReservationStatus.ACTIVE;
        int remaining = availability.getDayAvailability(today).stream()
                .filter(s -> !s.blocked() && !s.passed())
                .mapToInt(s -> s.normal().available()).sum();
        return new Stats(today,
                reservationRepository.countByDate(today, active),
                reservationRepository.sumPeopleByDate(today, active),
                reservationRepository.countByDateAndType(today, ReservationType.VIP, active),
                reservationRepository.sumTotalByStatusFrom(ReservationStatus.CONFIRMED, today),
                reservationRepository.countByStatusInFrom(
                        List.of(ReservationStatus.PAYMENT_PENDING, ReservationStatus.PAYMENT_RECEIPT_SENT), today),
                remaining);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internos
    // ──────────────────────────────────────────────────────────────────────

    /** Clave entera de franja: minutos desde epoch-day (cabe en int hasta el año ~7000). */
    static int slotKey(LocalDate date, LocalTime time) {
        return (int) (date.toEpochDay() * 1440L + time.toSecondOfDay() / 60);
    }

    /** Toma los locks en orden ascendente y sin repetir → sin deadlocks entre transacciones. */
    private void lockSlots(Collection<Integer> keys) {
        for (int key : new TreeSet<>(keys)) {
            reservationRepository.lockSlot(SLOT_LOCK_NAMESPACE, key);
        }
    }

    private void requireOwner(ReservationEntity r, String requiredOwnerPhone) {
        if (requiredOwnerPhone != null && !requiredOwnerPhone.equals(r.getPhoneNumber())) {
            log.warn("[RESERVATION] ownership violation reservationId={} requester={}", r.getId(), mask(requiredOwnerPhone));
            throw new BusinessException("Esa reserva no pertenece a este cliente", HttpStatus.FORBIDDEN);
        }
    }

    private Map<String, Object> snapshot(ReservationEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", r.getReservationType());
        m.put("date", r.getSlotDate());
        m.put("time", r.getSlotTime());
        m.put("partySize", r.getPartySize());
        m.put("totalPrice", r.getTotalPrice());
        return m;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Enmascara teléfonos en logs: 300xxxx567. */
    static String mask(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "xxxx" + phone.substring(phone.length() - 3);
    }
}
