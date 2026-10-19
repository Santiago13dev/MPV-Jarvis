package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.reservation.AvailabilityModels.Reason;
import com.whatsappmvp.application.reservation.AvailabilityModels.RequestCheck;
import com.whatsappmvp.application.reservation.ReservationIntentExtractor.Context;
import com.whatsappmvp.application.reservation.ReservationIntentExtractor.Extraction;
import com.whatsappmvp.application.reservation.ReservationIntentExtractor.Intent;
import com.whatsappmvp.application.service.ReservationService;
import com.whatsappmvp.application.service.ReservationService.CreateCommand;
import com.whatsappmvp.application.service.ReservationService.ModifyCommand;
import com.whatsappmvp.application.service.ReservationService.Outcome;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.domain.exception.BusinessException;
import com.whatsappmvp.infrastructure.persistence.entity.ConversationEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationDraftEntity;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationDraftEntity.Mode;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.ReservationDraftJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Motor conversacional de reservas por WhatsApp.
 *
 * Reparto de responsabilidades (nunca invertir):
 *   - El extractor (reglas + LLM) INTERPRETA el lenguaje del cliente → datos estructurados.
 *   - Este servicio lleva el estado (reservation_drafts en PostgreSQL) y decide qué preguntar.
 *   - ReservationAvailabilityService decide disponibilidad; ReservationService crea/modifica/cancela.
 *   - ReservationMessages redacta: toda cifra, fecha y alternativa sale del backend.
 *
 * El estado vive en BD (no en el historial del chat) y expira a los {@value #DRAFT_TTL_MINUTES} min de inactividad.
 * Una reserva solo se le confirma al cliente DESPUÉS de que ReservationService la guardó (hay número de reserva).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationConversationService {

    public enum Kind {
        /** Respuesta lista para enviar. */
        REPLY,
        /** El cliente hizo otra pregunta: responder con FAQ/IA y añadir este recordatorio del borrador. */
        QUESTION_WITH_REMINDER,
        /** No es un tema de reservas: continuar con el motor híbrido existente. */
        NOT_HANDLED
    }

    public record Result(Kind kind, String text) {
        static Result reply(String t) { return new Result(Kind.REPLY, t); }
        static Result question(String reminder) { return new Result(Kind.QUESTION_WITH_REMINDER, reminder); }
        static Result notHandled() { return new Result(Kind.NOT_HANDLED, null); }
    }

    static final int DRAFT_TTL_MINUTES = 30;
    private static final int MAX_OFFERED = 6;
    private static final String HUMAN_OFFER_ACTION = "HUMAN_TRANSFER_OFFERED";

    // Qué le preguntó el bot al cliente en el último turno
    private static final String A_PARTY = "PARTY_SIZE";
    private static final String A_DATE = "DATE";
    private static final String A_TIME = "TIME";
    private static final String A_TIME_ANY = "TIME_ANY";
    private static final String A_NAME = "NAME";
    private static final String A_TYPE = "TYPE";
    private static final String A_CONFIRM = "CONFIRM";
    private static final String A_RESERVATION = "RESERVATION";
    private static final String A_CHANGES = "CHANGES";

    private final ReservationDraftJpaRepository drafts;
    private final ReservationService reservationService;
    private final ReservationAvailabilityService availability;
    private final ReservationCalendarService calendar;
    private final RestaurantSettingsService settingsService;
    private final ReservationMessages msg;
    private final ReservationIntentExtractor extractor;
    private final ReceiptStorageService storage;
    private final ConversationJpaRepository conversationRepository;

    // ══════════════════════════════════════════════════════════════════════
    // API pública
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    public boolean hasActiveDraft(ConversationEntity conv) {
        return activeDraft(conv.getId()).isPresent();
    }

    /** Descarta el borrador de la conversación (p. ej. al transferirla a un asesor). */
    @Transactional
    public void discardDraft(ConversationEntity conv) {
        drafts.findByConversationId(conv.getId()).ifPresent(this::deleteDraft);
    }

    /** Procesa un mensaje de TEXTO del cliente. */
    @Transactional
    public Result handleText(ConversationEntity conv, String phone, String text, String waMessageId) {
        if (text == null || text.isBlank()) return Result.notHandled();
        RestaurantSettingsEntity s = settingsService.get();
        int pivot = s.getReservationStartTime().getHour();
        ReservationDraftEntity draft = activeDraft(conv.getId()).orElse(null);

        Context ctx = new Context(draft != null ? draft.getAwaiting() : null, pivot,
                draft != null && draft.getMode() == Mode.MODIFY, draft != null);
        Extraction x = extractor.extract(text, ctx);
        // El LLM solo entra cuando hay un borrador abierto y las reglas locales no entendieron nada
        if (draft != null && x.isEmpty()) {
            x = extractor.enrich(text, ctx, x);
        }
        log.info("[RESERVATION] turn customer={} intent={} awaiting={} draft={}",
                mask(phone), x.intent(), ctx.awaiting(), draft != null ? draft.getMode() : "none");

        try {
            return draft == null
                    ? start(conv, phone, text, x, waMessageId, s, pivot)
                    : proceed(draft, conv, phone, x, waMessageId, s);
        } catch (BusinessException e) {
            log.warn("[RESERVATION] business rule rejected customer={}: {}", mask(phone), e.getMessage());
            return Result.reply(e.getMessage());
        } catch (RuntimeException e) {
            log.error("[RESERVATION] unexpected failure customer={}: {}", mask(phone), e.getMessage(), e);
            return Result.reply(msg.systemError());
        }
    }

    /**
     * Procesa una imagen/PDF del cliente como posible comprobante de pago.
     * Vacío = no hay reserva pendiente de pago → el pipeline deriva el archivo a un asesor.
     */
    @Transactional
    public Optional<String> handleReceipt(ConversationEntity conv, String phone, byte[] data, String mimeType,
                                          String caption, String waMessageId) {
        List<ReservationEntity> candidates = reservationService.findUpcomingActiveByPhone(phone).stream()
                .filter(r -> r.getStatus() == ReservationStatus.PENDING
                        || r.getStatus() == ReservationStatus.PAYMENT_PENDING
                        || r.getStatus() == ReservationStatus.PAYMENT_RECEIPT_SENT)
                .toList();
        if (candidates.isEmpty()) return Optional.empty();

        ReservationEntity target = null;
        if (caption != null) {
            Long number = firstNumber(caption);
            if (number != null) {
                target = candidates.stream().filter(r -> number.equals(r.getReservationNumber())).findFirst().orElse(null);
            }
        }
        if (target == null) {
            // La que está esperando pago y vence primero; si no, la primera
            target = candidates.stream()
                    .filter(r -> r.getStatus() != ReservationStatus.PAYMENT_RECEIPT_SENT)
                    .min(Comparator.comparing(r -> r.getHoldExpiresAt() != null
                            ? r.getHoldExpiresAt() : calendar.nowDateTime().plusYears(1)))
                    .orElse(candidates.get(0));
        }
        try {
            var stored = storage.store(target.getId(), data, mimeType);
            ReservationEntity updated = reservationService.registerReceipt(target.getId(), stored.relativePath(),
                    stored.mimeType(), waMessageId, Actor.customer(phone));
            String reply = msg.receiptReceived(updated);
            if (candidates.size() > 1) {
                reply += "\n\n(La asocié a la reserva #" + updated.getReservationNumber()
                        + ". Si era para otra, avísame con el número.)";
            }
            return Optional.of(reply);
        } catch (BusinessException e) {
            log.warn("[RESERVATION] receipt rejected customer={}: {}", mask(phone), e.getMessage());
            return Optional.of(e.getMessage());
        } catch (RuntimeException e) {
            log.error("[RESERVATION] receipt failure customer={}: {}", mask(phone), e.getMessage(), e);
            return Optional.of("No pude registrar tu comprobante en este momento. Un asesor lo revisará pronto. 😊");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Sin borrador: según la intención
    // ══════════════════════════════════════════════════════════════════════

    private Result start(ConversationEntity conv, String phone, String text, Extraction x, String waId,
                         RestaurantSettingsEntity s, int pivot) {
        return switch (x.intent()) {
            case CREATE_RESERVATION, CHECK_AVAILABILITY ->
                    advanceCreate(newDraft(conv, Mode.CREATE), x, conv, phone, waId, s);
            case MODIFY_RESERVATION -> {
                // En modificaciones "del sábado... al domingo" se re-interpreta tomando el destino
                Extraction xm = extractor.extract(text, new Context(null, pivot, true));
                yield startManage(conv, phone, xm, s, true);
            }
            case CANCEL_RESERVATION -> startManage(conv, phone, x, s, false);
            case GET_RESERVATION -> showReservations(phone, x);
            case PAYMENT_RECEIPT -> {
                List<ReservationEntity> upcoming = reservationService.findUpcomingActiveByPhone(phone);
                boolean awaitingPayment = upcoming.stream()
                        .anyMatch(r -> r.getStatus() == ReservationStatus.PENDING
                                || r.getStatus() == ReservationStatus.PAYMENT_PENDING);
                // Sin reserva pendiente no se puede pedir comprobante: se contesta con lo que sí aplica
                // (nunca "quedó confirmada": eso lo dice una FAQ y sería mentirle al cliente).
                yield awaitingPayment ? Result.reply(msg.askReceiptImage())
                        : Result.reply(!upcoming.isEmpty() ? msg.noPendingPayment() : msg.noReservations());
            }
            default -> Result.notHandled();
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Con borrador
    // ══════════════════════════════════════════════════════════════════════

    private Result proceed(ReservationDraftEntity d, ConversationEntity conv, String phone, Extraction x,
                           String waId, RestaurantSettingsEntity s) {
        // Cambio de tema: gestionar una reserva existente mientras se armaba una nueva
        if (d.getMode() == Mode.CREATE
                && (x.intent() == Intent.CANCEL_RESERVATION || x.intent() == Intent.MODIFY_RESERVATION)) {
            boolean modify = x.intent() == Intent.MODIFY_RESERVATION;
            List<ReservationEntity> existing = reservationService.findUpcomingActiveByPhone(phone);
            deleteDraft(d);
            if (existing.isEmpty()) {
                return Result.reply(modify ? msg.noReservations() : msg.draftDiscarded());
            }
            return startManage(conv, phone, x, s, modify);
        }

        // Otra pregunta (o texto que nadie entendió): se responde y se conserva el borrador
        if (x.isEmpty()) {
            return Result.question(reminder(d));
        }

        // Pregunta que menciona datos ("¿abren el domingo?", "¿cuánto cuesta para 5 personas?"): es una consulta,
        // no una respuesta a lo que el bot preguntó. Se responde con FAQ/IA SIN tocar el borrador.
        boolean pureQuestion = x.intent() == Intent.GENERAL_QUESTION
                && !x.yes() && !x.no() && !x.discard()
                && x.ordinal() == 0 && x.reservationNumber() == null
                && !answersAwaited(d, x);
        if (pureQuestion) {
            return Result.question(reminder(d));
        }

        return switch (d.getMode()) {
            case CREATE -> advanceCreate(d, x, conv, phone, waId, s);
            case MODIFY -> continueModify(d, x, phone, s);
            case CANCEL -> continueCancel(d, x, phone);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // CREAR
    // ══════════════════════════════════════════════════════════════════════

    private Result advanceCreate(ReservationDraftEntity d, Extraction x, ConversationEntity conv, String phone,
                                 String waId, RestaurantSettingsEntity s) {
        String prev = d.getAwaiting();

        // Respuestas cortas según lo último que se preguntó
        if (A_TYPE.equals(prev) && x.yes() && d.getReservationType() == ReservationType.VIP) {
            d.setReservationType(ReservationType.NORMAL);
        }
        boolean changed = applyCreateFields(d, x);
        if (A_TIME.equals(prev) && !x.time().hasTime()) {
            LocalTime picked = pickOffered(d, x);
            if (picked != null) {
                d.setSlotTime(picked);
                d.setOfferedTimes(null);
                changed = true;
            }
        }

        // Abandonar / negar
        if (x.discard() || (x.no() && !A_TIME.equals(prev) && !A_TIME_ANY.equals(prev) && !A_DATE.equals(prev))) {
            return discardCreate(d);
        }
        if (x.no()) {
            return ask(d, prev, "Entendido. Dime qué día u horario te funciona mejor, "
                    + "o escribe *cancelar* si prefieres dejarlo así.");
        }

        if (x.date().status() == DateExpressionResolver.Status.AMBIGUOUS) {
            return ask(d, A_DATE, msg.askDateAmbiguous(x.date().note()));
        }

        // Personas inválidas o grupo demasiado grande: se resuelve ANTES de seguir preguntando
        // ("reserva para 0" / "para 50" no deben llegar hasta que se pide la fecha).
        if (d.getPartySize() != null && d.getPartySize() < 1) {
            d.setPartySize(null);
            return ask(d, A_PARTY, msg.partyInvalid());
        }
        int maxParty = s.getMaxPartySize() != null ? s.getMaxPartySize() : Integer.MAX_VALUE;
        if (d.getPartySize() != null && d.getPartySize() > maxParty) {
            return handToHuman(d, conv, msg.partyTooLarge(maxParty));
        }

        // ¿Qué falta? Solo se pregunta lo que no se sabe
        boolean nothingYet = d.getPartySize() == null && d.getSlotDate() == null && d.getSlotTime() == null
                && d.getReservationType() == null && d.getCustomerName() == null;
        if (nothingYet) return ask(d, A_PARTY, msg.askEverything());
        if (d.getPartySize() == null) return ask(d, A_PARTY, msg.askPartySize());
        if (d.getSlotDate() == null) return ask(d, A_DATE, msg.askDate(d.getPartySize()));

        ReservationType type = d.getReservationType() != null ? d.getReservationType() : ReservationType.NORMAL;

        // Problemas a nivel de día / grupo (día cerrado, bloqueado, grupo demasiado grande...)
        Result dayIssue = dayProblem(d, conv, type, s);
        if (dayIssue != null) return dayIssue;

        // Sin hora → ofrecer franjas reales
        if (d.getSlotTime() == null) {
            TimeExpressionResolver.Period period = x.time().hasPeriod() ? x.time().period() : null;
            return offerSlots(d, type, s, period);
        }

        // Verificación definitiva contra el backend
        RequestCheck c = availability.check(d.getSlotDate(), d.getSlotTime(), type, d.getPartySize(), null);
        if (!c.available()) {
            d.setSlotTime(null);
            if (c.alternatives().isEmpty()) {
                d.setSlotDate(null);
                d.setOfferedTimes(null);
                return ask(d, A_DATE, msg.reasonText(c));
            }
            d.setOfferedTimes(csv(c.alternatives()));
            return ask(d, A_TIME, msg.reasonText(c));
        }

        if (d.getCustomerName() == null || d.getCustomerName().isBlank()) {
            return ask(d, A_NAME, msg.askName());
        }

        boolean confirm = A_CONFIRM.equals(prev) && x.yes() && !changed;
        if (confirm) {
            return createReservation(d, conv, phone, waId, type);
        }
        d.setOfferedTimes(null);
        return ask(d, A_CONFIRM, msg.confirmPrompt(type, d.getSlotDate(), d.getSlotTime(),
                d.getPartySize(), c.price(), d.getCustomerName()));
    }

    private boolean applyCreateFields(ReservationDraftEntity d, Extraction x) {
        boolean changed = false;
        if (x.type() != null && x.type() != d.getReservationType()) {
            d.setReservationType(x.type());
            changed = true;
        }
        if (x.partySize() != null && !x.partySize().equals(d.getPartySize())) {
            d.setPartySize(x.partySize());
            changed = true;
        }
        if (x.customerName() != null && !x.customerName().equals(d.getCustomerName())) {
            d.setCustomerName(x.customerName());
            changed = true;
        }
        if (x.receiptPhone() != null && !x.receiptPhone().equals(d.getReceiptPhone())) {
            d.setReceiptPhone(x.receiptPhone());
            changed = true;
        }
        if (x.date().isResolved() && !x.date().date().equals(d.getSlotDate())) {
            d.setSlotDate(x.date().date());
            d.setOfferedTimes(null);
            changed = true;
        }
        if (x.time().hasTime() && !x.time().time().equals(d.getSlotTime())) {
            d.setSlotTime(x.time().time());
            changed = true;
        }
        return changed;
    }

    /** null = el día y el grupo son válidos; si no, deja el borrador listo para la siguiente respuesta. */
    private Result dayProblem(ReservationDraftEntity d, ConversationEntity conv, ReservationType type,
                              RestaurantSettingsEntity s) {
        LocalDate date = d.getSlotDate();
        RequestCheck c = availability.check(date, s.getReservationStartTime(), type, d.getPartySize(), null);
        Reason reason = c.reason();
        if (c.available()) return null;

        if (reason == Reason.PAST_DATE || reason == Reason.CLOSED_DAY) {
            d.setSlotDate(null);
            d.setSlotTime(null);
            d.setOfferedTimes(null);
            return ask(d, A_DATE, msg.reasonText(c));
        }
        if (reason == Reason.BLOCKED && calendar.isDateBlocked(date)) {
            List<LocalDate> next = calendar.nextBookableDays(date.plusDays(1), 2, s);
            d.setSlotDate(null);
            d.setSlotTime(null);
            d.setOfferedTimes(null);
            String text = "Para el " + msg.date(date) + " no estamos recibiendo reservas."
                    + (next.isEmpty() ? "" : " Puedo revisar el "
                    + orJoin(next.stream().map(msg::date).toList()) + ". ¿Te sirve?");
            return ask(d, A_DATE, text);
        }
        if (reason == Reason.INVALID_PARTY_SIZE) {
            d.setPartySize(null);
            return ask(d, A_PARTY, msg.reasonText(c));
        }
        if (reason == Reason.PARTY_TOO_LARGE) {
            return handToHuman(d, conv, msg.reasonText(c));
        }
        if (reason == Reason.VIP_CAPACITY_EXCEEDED) {
            return ask(d, A_TYPE, msg.reasonText(c));
        }
        return null; // problemas de franja: se resuelven con la hora
    }

    private Result offerSlots(ReservationDraftEntity d, ReservationType type, RestaurantSettingsEntity s,
                              TimeExpressionResolver.Period period) {
        LocalDate date = d.getSlotDate();
        int party = d.getPartySize();
        List<LocalTime> all = availability.findAvailableSlots(date, type, party, null);
        if (all.isEmpty()) {
            List<LocalDate> next = calendar.nextBookableDays(date.plusDays(1), 2, s);
            d.setSlotDate(null);
            d.setOfferedTimes(null);
            return ask(d, A_DATE, msg.noSlotsThatDay(date, next));
        }
        List<LocalTime> slots = filterPeriod(all, period);
        if (slots.isEmpty()) {
            d.setOfferedTimes(csv(limit(all)));
            return ask(d, A_TIME_ANY, msg.noSlotsInPeriod(date));
        }
        List<LocalTime> shown = limit(slots);
        d.setOfferedTimes(csv(shown));
        return ask(d, A_TIME, msg.offerSlots(date, party, type, shown));
    }

    private Result createReservation(ReservationDraftEntity d, ConversationEntity conv, String phone,
                                     String waId, ReservationType type) {
        String key = waId != null && !waId.isBlank() ? "wa:" + waId : "draft:" + d.getId() + ":" + d.getUpdatedAt();
        Outcome o = reservationService.create(new CreateCommand(
                d.getCustomerName(), phone, d.getReceiptPhone(), type, d.getSlotDate(), d.getSlotTime(),
                d.getPartySize(), null, conv.getId(), key, ReservationStatus.PAYMENT_PENDING,
                Actor.customer(phone)));

        if (o.ok() || o.duplicate()) {
            deleteDraft(d);
            return Result.reply(msg.created(o.reservation()));
        }
        // El cupo se ocupó entre el resumen y la confirmación: se vuelve a ofrecer lo real
        RequestCheck c = o.check();
        d.setSlotTime(null);
        if (c.alternatives().isEmpty()) {
            d.setSlotDate(null);
            d.setOfferedTimes(null);
            return ask(d, A_DATE, msg.reasonText(c));
        }
        d.setOfferedTimes(csv(c.alternatives()));
        return ask(d, A_TIME, msg.reasonText(c));
    }

    private Result discardCreate(ReservationDraftEntity d) {
        deleteDraft(d);
        return Result.reply(msg.draftDiscarded());
    }

    /** Pasa la conversación al flujo existente de transferencia a asesor (paso HUMAN_TRANSFER_OFFERED). */
    private Result handToHuman(ReservationDraftEntity d, ConversationEntity conv, String text) {
        deleteDraft(d);
        conv.setPendingAction(HUMAN_OFFER_ACTION);
        conv.setPendingActionData("{\"offer\":true}");
        conversationRepository.save(conv);
        return Result.reply(text);
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODIFICAR / CANCELAR
    // ══════════════════════════════════════════════════════════════════════

    private Result startManage(ConversationEntity conv, String phone, Extraction x, RestaurantSettingsEntity s,
                               boolean modify) {
        List<ReservationEntity> list = reservationService.findUpcomingActiveByPhone(phone);
        if (list.isEmpty()) return Result.reply(msg.noReservations());

        ReservationEntity target = pickReservation(list, x);
        ReservationDraftEntity d = newDraft(conv, modify ? Mode.MODIFY : Mode.CANCEL);
        if (target == null) {
            return ask(d, A_RESERVATION, msg.whichReservation(list, modify ? "modificar" : "cancelar"));
        }
        d.setTargetReservationId(target.getId());
        if (!modify) {
            return ask(d, A_CONFIRM, msg.cancelPrompt(target));
        }
        applyModifyFields(d, x);
        return evaluateModify(d, target);
    }

    private Result continueModify(ReservationDraftEntity d, Extraction x, String phone, RestaurantSettingsEntity s) {
        String prev = d.getAwaiting();
        if (x.discard() || (x.no() && A_CONFIRM.equals(prev))) return discardManage(d);

        if (d.getTargetReservationId() == null) {
            List<ReservationEntity> list = reservationService.findUpcomingActiveByPhone(phone);
            if (list.isEmpty()) {
                deleteDraft(d);
                return Result.reply(msg.noReservations());
            }
            ReservationEntity t = pickReservation(list, x);
            if (t == null) return ask(d, A_RESERVATION, msg.whichReservation(list, "modificar"));
            d.setTargetReservationId(t.getId());
            applyModifyFields(d, x);
            return evaluateModify(d, t);
        }

        ReservationEntity target = reservationService.getById(d.getTargetReservationId());
        if (!phone.equals(target.getPhoneNumber()) || !target.getStatus().isActive()) {
            deleteDraft(d);
            return Result.reply("Esa reserva ya no está activa, así que no puedo modificarla. "
                    + "Si quieres, hacemos una nueva 😊");
        }

        LocalTime picked = A_CHANGES.equals(prev) ? pickOffered(d, x) : null;
        boolean changed = applyModifyFields(d, x);
        if (picked != null) {
            d.setSlotTime(picked);
            d.setOfferedTimes(null);
            changed = true;
        }
        if (A_CONFIRM.equals(prev) && x.yes() && !changed) {
            return executeModify(d, phone);
        }
        return evaluateModify(d, target);
    }

    /** En MODIFY los campos del borrador son los NUEVOS valores (null = sin cambio). */
    private boolean applyModifyFields(ReservationDraftEntity d, Extraction x) {
        boolean changed = false;
        if (x.type() != null && x.type() != d.getReservationType()) {
            d.setReservationType(x.type());
            changed = true;
        }
        if (x.partySize() != null && !x.partySize().equals(d.getPartySize())) {
            d.setPartySize(x.partySize());
            changed = true;
        }
        if (x.date().isResolved() && !x.date().date().equals(d.getSlotDate())) {
            d.setSlotDate(x.date().date());
            d.setOfferedTimes(null);
            changed = true;
        }
        if (x.time().hasTime() && !x.time().time().equals(d.getSlotTime())) {
            d.setSlotTime(x.time().time());
            changed = true;
        }
        return changed;
    }

    private Result evaluateModify(ReservationDraftEntity d, ReservationEntity target) {
        boolean anyChange = d.getSlotDate() != null || d.getSlotTime() != null
                || d.getPartySize() != null || d.getReservationType() != null;
        if (!anyChange) return ask(d, A_CHANGES, msg.askWhatToChange(target));

        LocalDate nd = d.getSlotDate() != null ? d.getSlotDate() : target.getSlotDate();
        LocalTime nt = d.getSlotTime() != null ? d.getSlotTime() : target.getSlotTime();
        int np = d.getPartySize() != null ? d.getPartySize()
                : (target.getPartySize() != null ? target.getPartySize() : 1);
        ReservationType ntype = d.getReservationType() != null ? d.getReservationType() : target.getReservationType();

        boolean same = nd.equals(target.getSlotDate()) && nt.equals(target.getSlotTime())
                && ntype == target.getReservationType()
                && np == (target.getPartySize() != null ? target.getPartySize() : 1);
        if (same) {
            d.setSlotDate(null);
            d.setSlotTime(null);
            d.setPartySize(null);
            d.setReservationType(null);
            return ask(d, A_CHANGES, "Eso ya es lo que tiene tu reserva. " + msg.askWhatToChange(target));
        }

        // La propia reserva se excluye: sus cupos actuales cuentan como libres para el cambio
        RequestCheck c = availability.check(nd, nt, ntype, np, target.getId());
        if (!c.available()) {
            boolean dateLevel = c.reason() == Reason.CLOSED_DAY || c.reason() == Reason.PAST_DATE
                    || (c.reason() == Reason.BLOCKED && calendar.isDateBlocked(nd));
            if (dateLevel) d.setSlotDate(null);
            d.setSlotTime(null);
            d.setOfferedTimes(csv(c.alternatives()));
            return ask(d, A_CHANGES, msg.reasonText(c));
        }
        d.setOfferedTimes(null);
        return ask(d, A_CONFIRM, msg.modifyPrompt(target, nd, nt, np, ntype, c.price()));
    }

    private Result executeModify(ReservationDraftEntity d, String phone) {
        Outcome o = reservationService.modify(d.getTargetReservationId(), new ModifyCommand(
                d.getSlotDate(), d.getSlotTime(), d.getPartySize(), d.getReservationType(),
                phone, Actor.customer(phone)));
        if (o.unchanged()) {
            deleteDraft(d);
            return Result.reply("Tu reserva ya estaba con esos datos, no hice cambios.");
        }
        if (!o.ok()) {
            RequestCheck c = o.check();
            d.setSlotTime(null);
            d.setOfferedTimes(csv(c.alternatives()));
            return ask(d, A_CHANGES, msg.reasonText(c));
        }
        deleteDraft(d);
        return Result.reply(msg.modified(o.reservation(), o.priceDelta()));
    }

    private Result continueCancel(ReservationDraftEntity d, Extraction x, String phone) {
        String prev = d.getAwaiting();
        if (x.discard() || (x.no() && A_CONFIRM.equals(prev))) return discardManage(d);

        if (d.getTargetReservationId() == null) {
            List<ReservationEntity> list = reservationService.findUpcomingActiveByPhone(phone);
            if (list.isEmpty()) {
                deleteDraft(d);
                return Result.reply(msg.noReservations());
            }
            ReservationEntity t = pickReservation(list, x);
            if (t == null) return ask(d, A_RESERVATION, msg.whichReservation(list, "cancelar"));
            d.setTargetReservationId(t.getId());
            return ask(d, A_CONFIRM, msg.cancelPrompt(t));
        }

        if (A_CONFIRM.equals(prev) && x.yes()) {
            ReservationEntity r = reservationService.cancel(d.getTargetReservationId(), Actor.customer(phone),
                    "Cancelada por el cliente (WhatsApp)", phone);
            deleteDraft(d);
            return Result.reply(msg.cancelled(r));
        }
        return ask(d, A_CONFIRM, msg.cancelPrompt(reservationService.getById(d.getTargetReservationId())));
    }

    private Result discardManage(ReservationDraftEntity d) {
        deleteDraft(d);
        return Result.reply("Perfecto, dejé tu reserva como estaba 😊");
    }

    private Result showReservations(String phone, Extraction x) {
        List<ReservationEntity> list = reservationService.findUpcomingActiveByPhone(phone);
        if (list.isEmpty()) return Result.reply(msg.noReservations());
        if (x.reservationNumber() != null) {
            Optional<ReservationEntity> one = list.stream()
                    .filter(r -> x.reservationNumber().equals(r.getReservationNumber())).findFirst();
            if (one.isPresent()) return Result.reply("Esta es tu reserva:\n" + msg.reservationLine(one.get()));
        }
        return Result.reply(msg.reservationList(list));
    }

    private ReservationEntity pickReservation(List<ReservationEntity> list, Extraction x) {
        if (x.reservationNumber() != null) {
            return list.stream().filter(r -> x.reservationNumber().equals(r.getReservationNumber()))
                    .findFirst().orElse(null);
        }
        if (x.ordinal() > 0 && x.ordinal() <= list.size()) return list.get(x.ordinal() - 1);
        if (x.ordinal() == -1) return list.get(list.size() - 1);
        return list.size() == 1 ? list.get(0) : null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Borrador y utilidades
    // ══════════════════════════════════════════════════════════════════════

    private Optional<ReservationDraftEntity> activeDraft(UUID conversationId) {
        Optional<ReservationDraftEntity> opt = drafts.findByConversationId(conversationId);
        if (opt.isPresent() && opt.get().getUpdatedAt() != null
                && opt.get().getUpdatedAt().isBefore(calendar.nowDateTime().minusMinutes(DRAFT_TTL_MINUTES))) {
            log.info("[RESERVATION] draft expired conversationId={}", conversationId);
            deleteDraft(opt.get());
            return Optional.empty();
        }
        return opt;
    }

    private ReservationDraftEntity newDraft(ConversationEntity conv, Mode mode) {
        var now = calendar.nowDateTime();
        return ReservationDraftEntity.builder()
                .conversationId(conv.getId()).mode(mode).createdAt(now).updatedAt(now).build();
    }

    private Result ask(ReservationDraftEntity d, String awaiting, String text) {
        d.setAwaiting(awaiting);
        d.setUpdatedAt(calendar.nowDateTime());
        drafts.save(d);
        return Result.reply(text);
    }

    private void deleteDraft(ReservationDraftEntity d) {
        drafts.delete(d);
        drafts.flush(); // el DELETE debe ejecutarse antes de un posible INSERT de otro borrador (unique conversation_id)
    }

    /** ¿El mensaje trae justo el dato que el bot estaba esperando (fecha, hora, personas...)? */
    private boolean answersAwaited(ReservationDraftEntity d, Extraction x) {
        String awaiting = d.getAwaiting() == null ? "" : d.getAwaiting();
        return switch (awaiting) {
            case A_PARTY -> x.partySize() != null;
            case A_DATE -> x.date().status() != DateExpressionResolver.Status.NONE;
            case A_TIME, A_TIME_ANY -> x.time().hasTime() || x.time().hasPeriod();
            case A_NAME -> x.customerName() != null;
            case A_TYPE -> x.type() != null;
            case A_RESERVATION -> x.reservationNumber() != null || x.ordinal() != 0;
            case A_CHANGES -> x.hasBookingData();
            default -> false;
        };
    }

    /** Lo que se le recuerda al cliente tras responder una pregunta ajena. */
    private String reminder(ReservationDraftEntity d) {
        String subject = switch (d.getMode()) {
            case CREATE -> "tu reserva";
            case MODIFY -> "el cambio de tu reserva";
            case CANCEL -> "la cancelación de tu reserva";
        };
        return "¿Seguimos con " + subject + "? " + promptFor(d);
    }

    private String promptFor(ReservationDraftEntity d) {
        String awaiting = d.getAwaiting() == null ? "" : d.getAwaiting();
        return switch (awaiting) {
            case A_PARTY -> "¿Para cuántas personas sería?";
            case A_DATE -> "¿Para qué día te gustaría?";
            case A_TIME, A_TIME_ANY -> d.getSlotDate() != null
                    ? "¿A qué hora del " + msg.date(d.getSlotDate()) + " te gustaría?" : "¿A qué hora te gustaría?";
            case A_NAME -> "¿A nombre de quién dejo la reserva?";
            case A_TYPE -> "¿Prefieres una reserva normal?";
            case A_RESERVATION -> "¿Cuál reserva? Dime el número (por ejemplo #1001).";
            case A_CHANGES -> "¿Qué quieres cambiar: la fecha, la hora o el número de personas?";
            case A_CONFIRM -> d.getMode() == Mode.CREATE && d.getSlotDate() != null && d.getSlotTime() != null
                    ? "Tengo " + msg.date(d.getSlotDate()) + " " + msg.atTime(d.getSlotTime()) + ", "
                    + d.getPartySize() + " personas. Responde *sí* para confirmar o *no* para dejarlo así."
                    : "Responde *sí* para confirmar o *no* para dejarlo así.";
            default -> "Cuéntame qué datos de la reserva quieres darme.";
        };
    }

    /** Resuelve "la primera / la última / sí" contra las horas ofrecidas en el turno anterior. */
    private LocalTime pickOffered(ReservationDraftEntity d, Extraction x) {
        List<LocalTime> offered = parseTimes(d.getOfferedTimes());
        if (offered.isEmpty()) return null;
        if (x.ordinal() == -1) return offered.get(offered.size() - 1);
        if (x.ordinal() > 0 && x.ordinal() <= offered.size()) return offered.get(x.ordinal() - 1);
        if (x.yes() && offered.size() == 1) return offered.get(0);
        return null;
    }

    private List<LocalTime> filterPeriod(List<LocalTime> slots, TimeExpressionResolver.Period p) {
        if (p == null) return slots;
        return slots.stream().filter(t -> switch (p) {
            case MORNING -> t.isBefore(LocalTime.NOON);
            case AFTERNOON -> !t.isBefore(LocalTime.NOON) && t.isBefore(LocalTime.of(18, 0));
            case EVENING -> !t.isBefore(LocalTime.of(18, 0));
        }).toList();
    }

    private List<LocalTime> limit(List<LocalTime> slots) {
        return slots.size() > MAX_OFFERED ? new ArrayList<>(slots.subList(0, MAX_OFFERED)) : slots;
    }

    private static String csv(List<LocalTime> times) {
        if (times == null || times.isEmpty()) return null;
        return times.stream().map(LocalTime::toString).collect(Collectors.joining(","));
    }

    private static List<LocalTime> parseTimes(String csv) {
        List<LocalTime> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            try {
                out.add(LocalTime.parse(part.trim()));
            } catch (Exception ignored) { /* valor corrupto: se ignora */ }
        }
        return out;
    }

    private static String orJoin(List<String> items) {
        if (items.size() <= 1) return String.join("", items);
        return String.join(", ", items.subList(0, items.size() - 1)) + " o " + items.get(items.size() - 1);
    }

    private static Long firstNumber(String s) {
        var m = java.util.regex.Pattern.compile("#?(\\d{4,7})").matcher(s);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    /** Enmascara teléfonos en logs: 300xxxx567. */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "xxxx" + phone.substring(phone.length() - 3);
    }
}
