package com.whatsappmvp.application.reservation;

import com.whatsappmvp.application.reservation.AvailabilityModels.PriceQuote;
import com.whatsappmvp.application.reservation.AvailabilityModels.RequestCheck;
import com.whatsappmvp.domain.enums.ReservationStatus;
import com.whatsappmvp.domain.enums.ReservationType;
import com.whatsappmvp.infrastructure.persistence.entity.ReservationEntity;
import com.whatsappmvp.infrastructure.persistence.entity.RestaurantSettingsEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Textos al cliente. TODO dato (fechas, cupos, precios, alternativas) viene del backend:
 * la IA nunca redacta cifras ni disponibilidad. Los textos son humanos, nunca códigos de error.
 */
@Component
@RequiredArgsConstructor
public class ReservationMessages {

    private static final Locale ES = new Locale("es", "CO");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);

    private final RestaurantSettingsService settingsService;
    private final Clock clock;

    // ── Formato ───────────────────────────────────────────────────────────

    public String date(LocalDate d) {
        return d.format(DATE_FMT);
    }

    public String dateCap(LocalDate d) {
        String s = date(d);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public String time(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return String.format("%d:%02d %s", h, t.getMinute(), t.getHour() < 12 ? "AM" : "PM");
    }

    /** "a la 1:00 PM" / "a las 2:30 PM". */
    public String atTime(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return (h == 1 ? "a la " : "a las ") + time(t);
    }

    public String times(List<LocalTime> ts) {
        return join(ts.stream().map(this::time).toList());
    }

    public String money(BigDecimal v) {
        return "$" + String.format(Locale.GERMANY, "%,.0f", v);
    }

    public String typeLabel(ReservationType t) {
        return t == ReservationType.VIP ? "Espacio VIP privado" : "Reserva normal";
    }

    public String statusLabel(ReservationStatus s) {
        return switch (s) {
            case PENDING -> "pendiente";
            case PAYMENT_PENDING -> "pendiente de pago";
            case PAYMENT_RECEIPT_SENT -> "comprobante recibido, en revisión";
            case CONFIRMED -> "confirmada";
            case CANCELLED -> "cancelada";
            case EXPIRED -> "vencida";
        };
    }

    public String operatingDaysText() {
        RestaurantSettingsEntity s = settingsService.get();
        return join(s.operatingDaysSet().stream().sorted().map(this::pluralDay).toList());
    }

    private String pluralDay(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "lunes";
            case TUESDAY -> "martes";
            case WEDNESDAY -> "miércoles";
            case THURSDAY -> "jueves";
            case FRIDAY -> "viernes";
            case SATURDAY -> "sábados";
            case SUNDAY -> "domingos";
        };
    }

    public String join(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " y " + items.get(items.size() - 1);
    }

    private String orJoin(List<String> items) {
        if (items.size() <= 1) return join(items);
        return String.join(", ", items.subList(0, items.size() - 1)) + " o " + items.get(items.size() - 1);
    }

    // ── Preguntas por datos faltantes ─────────────────────────────────────

    public String askEverything() {
        return "¡Claro! Con gusto te ayudo con tu reserva 😊 Cuéntame para cuántas personas y para qué día "
                + "(" + operatingDaysText() + "). Si prefieres nuestro espacio VIP privado, también puedo reservarlo.";
    }

    public String askPartySize() {
        return "Perfecto. ¿Para cuántas personas sería?";
    }

    public String askDate(Integer partySize) {
        return "Claro. ¿Para qué día te gustaría? Atendemos " + operatingDaysText() + ".";
    }

    public String askDateAmbiguous(String note) {
        return "WEEKEND".equals(note)
                ? "¿Prefieres el sábado o el domingo? Atendemos " + operatingDaysText() + "."
                : "Me mencionaste varias fechas. ¿Para cuál día quieres la reserva?";
    }

    public String askTime(LocalDate date) {
        return "Perfecto. ¿A qué hora del " + date(date) + " te gustaría reservar?";
    }

    public String askName() {
        return "¿A nombre de quién dejo la reserva?";
    }

    public String offerSlots(LocalDate date, int partySize, ReservationType type, List<LocalTime> slots) {
        String who = type == ReservationType.VIP ? "el espacio VIP" : "" + partySize + " personas";
        return "Para " + who + " el " + date(date) + " tengo disponibilidad " + orJoin(
                slots.stream().map(t -> atTime(t)).toList()) + ". ¿Cuál prefieres?";
    }

    public String noSlotsThatDay(LocalDate date, List<LocalDate> nextDays) {
        String base = "Para el " + date(date) + " ya no tengo horarios disponibles con ese tamaño de grupo.";
        return nextDays.isEmpty() ? base
                : base + " Puedo revisar el " + orJoin(nextDays.stream().map(this::date).toList()) + ". ¿Te sirve?";
    }

    public String noSlotsInPeriod(LocalDate date) {
        return "En ese momento del día no tengo horarios disponibles el " + date(date)
                + ". ¿Quieres que te muestre los demás horarios?";
    }

    // ── Resumen y confirmación ────────────────────────────────────────────

    public String summaryBlock(ReservationType type, LocalDate date, LocalTime time, int partySize,
                               PriceQuote price, String customerName) {
        StringBuilder sb = new StringBuilder();
        sb.append("📅 ").append(dateCap(date)).append("\n");
        sb.append("🕐 ").append(time(time)).append("\n");
        sb.append("👥 ").append(partySize).append(partySize == 1 ? " persona" : " personas").append("\n");
        sb.append("🍽️ ").append(typeLabel(type)).append("\n");
        if (type == ReservationType.VIP) {
            sb.append("💰 Total: ").append(money(price.totalPrice())).append(" (valor del espacio completo)");
        } else {
            sb.append("💰 Total: ").append(money(price.totalPrice()))
              .append(" (").append(money(price.unitPrice())).append(" por persona)");
        }
        if (customerName != null && !customerName.isBlank()) {
            sb.append("\n👤 A nombre de ").append(customerName);
        }
        return sb.toString();
    }

    public String confirmPrompt(ReservationType type, LocalDate date, LocalTime time, int partySize,
                                PriceQuote price, String customerName) {
        return "Perfecto. Tengo estos datos:\n\n" + summaryBlock(type, date, time, partySize, price, customerName)
                + "\n\n¿Deseas continuar? Responde *sí* para reservar o dime qué quieres cambiar.";
    }

    public String created(ReservationEntity r) {
        PriceQuote price = new PriceQuote(r.getReservationType(), r.getPartySize(), r.getUnitPrice(),
                r.getTotalPrice(), r.getReservationType() == ReservationType.NORMAL);
        StringBuilder sb = new StringBuilder();
        sb.append("¡Listo! Registré tu reserva con el número *#").append(r.getReservationNumber()).append("* ✅\n\n");
        sb.append(summaryBlock(r.getReservationType(), r.getSlotDate(), r.getSlotTime(),
                r.getPartySize(), price, r.getCustomerName()));
        sb.append("\n\n").append(paymentText(r));
        return sb.toString();
    }

    public String paymentText(ReservationEntity r) {
        RestaurantSettingsEntity s = settingsService.get();
        StringBuilder sb = new StringBuilder();
        sb.append("💳 *Para confirmarla debes pagar ").append(money(r.getTotalPrice())).append("*.\n");
        if (s.getPaymentInstructions() != null) {
            sb.append(s.getPaymentInstructions()).append("\n");
        } else {
            sb.append("Un asesor te compartirá los datos para el pago.\n");
        }
        sb.append("Cuando pagues, envíame aquí la captura del comprobante.");
        if (r.getHoldExpiresAt() != null) {
            sb.append("\n\n⏳ Tu cupo queda apartado ").append(holdText(r.getHoldExpiresAt()))
              .append(". Si no recibimos el comprobante a tiempo, se libera automáticamente.");
        }
        return sb.toString();
    }

    private String holdText(LocalDateTime exp) {
        LocalDate today = LocalDate.now(clock);
        String t = time(exp.toLocalTime());
        if (exp.toLocalDate().equals(today)) return "hasta las " + t;
        return "hasta el " + date(exp.toLocalDate()) + " a las " + t;
    }

    // ── Rechazos del backend → texto humano ───────────────────────────────

    /** Traduce el resultado del backend (motivo + alternativas reales) a una respuesta natural. */
    public String reasonText(RequestCheck c) {
        List<String> altTimes = c.alternatives().stream().map(this::atTime).toList();
        String who = c.type() == ReservationType.VIP ? "el espacio VIP" : c.partySize() + " personas";
        return switch (c.reason()) {
            case OK -> "Todo en orden.";
            case CLOSED_DAY -> "Para el " + date(c.date()) + " no tenemos servicio. Atendemos únicamente "
                    + operatingDaysText() + "." + offerDays(c.nextOperatingDays());
            case PAST_DATE -> "Esa fecha ya pasó." + offerDays(c.nextOperatingDays());
            case BLOCKED -> {
                String base = "Para el " + date(c.date()) + (c.time() != null ? " " + atTime(c.time()) : "")
                        + " no estamos recibiendo reservas.";
                yield !altTimes.isEmpty()
                        ? base + " Tengo disponibilidad " + orJoin(altTimes) + ". ¿Cuál prefieres?"
                        : base + offerDays(c.nextOperatingDays());
            }
            case INVALID_SLOT -> "Tomamos reservas en estos horarios: " + slotListText() + ". ¿Cuál prefieres?";
            case SLOT_PASSED -> !altTimes.isEmpty()
                    ? "Esa hora ya pasó por hoy. Tengo disponibilidad " + orJoin(altTimes) + ". ¿Cuál prefieres?"
                    : "Esa hora ya pasó por hoy y no quedan más horarios disponibles.";
            case INVALID_PARTY_SIZE -> "Necesito un número de personas válido (mínimo 1).";
            case PARTY_TOO_LARGE -> "Para grupos tan grandes lo mejor es que te ayude un asesor del restaurante. "
                    + "¿Quieres que te comunique con uno?";
            case INSUFFICIENT_CAPACITY -> {
                String base = "Para " + atTime(c.time()) + " ya no tenemos suficientes cupos para " + who + ".";
                yield !altTimes.isEmpty()
                        ? "Para " + who + " " + atTime(c.time()) + " ya no tenemos cupo suficiente. Tengo disponibilidad "
                          + orJoin(altTimes) + ". ¿Cuál prefieres?"
                        : base + " Tampoco tengo otros horarios con cupo ese día. ¿Quieres que revise otro día?";
            }
            case VIP_TAKEN -> !altTimes.isEmpty()
                    ? "El espacio VIP ya está reservado " + atTime(c.time()) + ". Está libre " + orJoin(altTimes)
                      + ". ¿Te sirve alguno?"
                    : "El espacio VIP ya está reservado " + atTime(c.time()) + " y ese día no tiene otro horario libre. "
                      + "¿Quieres una reserva normal o probar otro día?";
            case VIP_CAPACITY_EXCEEDED -> "El espacio VIP tiene capacidad para hasta "
                    + settingsService.get().getVipCapacity() + " personas. ¿Prefieres una reserva normal para el grupo?";
        };
    }

    private String offerDays(List<LocalDate> days) {
        if (days.isEmpty()) return "";
        return " Si quieres, puedo ayudarte a reservar para el " + orJoin(days.stream().map(this::date).toList()) + ".";
    }

    private String slotListText() {
        return join(slotTimesOf().stream().map(this::time).toList());
    }

    private List<LocalTime> slotTimesOf() {
        RestaurantSettingsEntity s = settingsService.get();
        List<LocalTime> out = new java.util.ArrayList<>();
        int step = Math.max(5, s.getSlotIntervalMinutes());
        for (int m = s.getReservationStartTime().toSecondOfDay() / 60;
             m <= s.getReservationEndTime().toSecondOfDay() / 60; m += step) {
            out.add(LocalTime.of(m / 60, m % 60));
        }
        return out;
    }

    // ── Consultar / modificar / cancelar ──────────────────────────────────

    public String reservationLine(ReservationEntity r) {
        return "#" + r.getReservationNumber() + " · " + date(r.getSlotDate()) + " " + time(r.getSlotTime())
                + " · " + r.getPartySize() + (r.getPartySize() != null && r.getPartySize() == 1 ? " persona" : " personas")
                + " · " + (r.getReservationType() == ReservationType.VIP ? "VIP" : "normal")
                + " · " + statusLabel(r.getStatus());
    }

    public String reservationList(List<ReservationEntity> list) {
        String lines = list.stream().map(r -> "• " + reservationLine(r)).collect(Collectors.joining("\n"));
        return "Estas son tus reservas:\n\n" + lines;
    }

    public String noReservations() {
        return "No encuentro reservas activas a tu nombre. Si quieres hacer una, dime para cuántas personas y qué día 😊";
    }

    public String whichReservation(List<ReservationEntity> list, String verb) {
        String lines = list.stream().map(r -> "• " + reservationLine(r)).collect(Collectors.joining("\n"));
        return "Tienes varias reservas activas:\n\n" + lines + "\n\n¿Cuál quieres " + verb + "? Dime el número (por ejemplo #"
                + list.get(0).getReservationNumber() + ").";
    }

    public String askWhatToChange(ReservationEntity r) {
        return "Esta es tu reserva:\n" + reservationLine(r) + "\n\n¿Qué quieres cambiar? Puedes decirme la fecha, "
                + "la hora o el número de personas.";
    }

    public String modifyPrompt(ReservationEntity r, LocalDate date, LocalTime time, int party,
                               ReservationType type, PriceQuote price) {
        BigDecimal delta = price.totalPrice().subtract(r.getTotalPrice());
        StringBuilder sb = new StringBuilder("Este sería el cambio en tu reserva #")
                .append(r.getReservationNumber()).append(":\n\n")
                .append(summaryBlock(type, date, time, party, price, null));
        if (delta.signum() > 0) {
            sb.append("\n\nEl total sube ").append(money(delta)).append(" frente al valor anterior.");
        } else if (delta.signum() < 0) {
            sb.append("\n\nEl total baja ").append(money(delta.abs())).append(" frente al valor anterior.");
        }
        sb.append("\n\n¿Confirmo el cambio? Responde *sí* o *no*.");
        return sb.toString();
    }

    public String modified(ReservationEntity r, BigDecimal delta) {
        PriceQuote price = new PriceQuote(r.getReservationType(), r.getPartySize(), r.getUnitPrice(),
                r.getTotalPrice(), r.getReservationType() == ReservationType.NORMAL);
        StringBuilder sb = new StringBuilder("¡Listo! Actualicé tu reserva *#").append(r.getReservationNumber())
                .append("* ✅\n\n").append(summaryBlock(r.getReservationType(), r.getSlotDate(), r.getSlotTime(),
                        r.getPartySize(), price, r.getCustomerName()));
        if (delta != null && delta.signum() != 0 && r.getStatus() == ReservationStatus.CONFIRMED) {
            sb.append("\n\nComo el valor cambió, un asesor te contactará para ajustar la diferencia.");
        } else if (r.getStatus() == ReservationStatus.PAYMENT_PENDING || r.getStatus() == ReservationStatus.PENDING) {
            sb.append("\n\n").append(paymentText(r));
        }
        return sb.toString();
    }

    public String cancelPrompt(ReservationEntity r) {
        return "¿Confirmas que quieres cancelar esta reserva?\n\n" + reservationLine(r)
                + "\n\nResponde *sí* para cancelarla o *no* para dejarla como está.";
    }

    public String cancelled(ReservationEntity r) {
        return "Listo, cancelé tu reserva *#" + r.getReservationNumber() + "* del " + date(r.getSlotDate())
                + ". Cuando quieras hacer otra, aquí estoy 😊";
    }

    // ── Comprobantes y notificaciones ─────────────────────────────────────

    public String receiptReceived(ReservationEntity r) {
        return "¡Gracias! Recibí tu comprobante para la reserva *#" + r.getReservationNumber()
                + "* 🙌 Un asesor lo va a validar y te avisaremos por aquí apenas quede confirmada.";
    }

    public String alreadyConfirmed(ReservationEntity r) {
        return "Tu reserva *#" + r.getReservationNumber() + "* ya está confirmada ✅ ¡Te esperamos el "
                + date(r.getSlotDate()) + " " + atTime(r.getSlotTime()) + "!";
    }

    public String askReceiptImage() {
        return "Claro, envíame aquí la foto o captura del comprobante de pago y la asocio a tu reserva.";
    }

    public String noReservationForReceipt() {
        return "Recibí tu archivo, pero no encuentro una reserva pendiente de pago a tu nombre. "
                + "Un asesor lo revisará y te escribirá pronto. 😊";
    }

    public String confirmedNotification(ReservationEntity r) {
        PriceQuote price = new PriceQuote(r.getReservationType(), r.getPartySize(), r.getUnitPrice(),
                r.getTotalPrice(), r.getReservationType() == ReservationType.NORMAL);
        return "🎉 ¡Tu reserva *#" + r.getReservationNumber() + "* está *confirmada*!\n\n"
                + summaryBlock(r.getReservationType(), r.getSlotDate(), r.getSlotTime(), r.getPartySize(), price,
                r.getCustomerName()) + "\n\n¡Te esperamos!";
    }

    public String receiptRejectedNotification(ReservationEntity r, String note) {
        StringBuilder sb = new StringBuilder("No pudimos validar el comprobante de tu reserva *#")
                .append(r.getReservationNumber()).append("*.");
        if (note != null && !note.isBlank()) sb.append("\nMotivo: ").append(note);
        sb.append("\n\nPor favor envíame de nuevo el comprobante de pago");
        if (r.getHoldExpiresAt() != null) sb.append(" ").append(holdText(r.getHoldExpiresAt()));
        sb.append(" para mantener tu cupo.");
        return sb.toString();
    }

    public String expiredNotification(ReservationEntity r) {
        return "Tu reserva *#" + r.getReservationNumber() + "* del " + date(r.getSlotDate())
                + " venció porque no recibimos el comprobante a tiempo y el cupo fue liberado. "
                + "Si todavía quieres reservar, escríbeme y la hacemos de nuevo 😊";
    }

    public String cancelledByAdminNotification(ReservationEntity r, String note) {
        String base = "Tu reserva *#" + r.getReservationNumber() + "* del " + date(r.getSlotDate())
                + " fue cancelada por el restaurante.";
        return (note != null && !note.isBlank() ? base + "\nMotivo: " + note : base)
                + "\nSi tienes dudas, escríbenos por aquí.";
    }

    public String adminReceiptReceived(ReservationEntity r) {
        return "🧾 Nuevo comprobante recibido\nReserva #" + r.getReservationNumber() + " · " + r.getCustomerName()
                + " (" + r.getPhoneNumber() + ")\n" + date(r.getSlotDate()) + " " + time(r.getSlotTime())
                + " · " + r.getPartySize() + " personas · " + money(r.getTotalPrice())
                + "\nRevísalo en el dashboard → Reservas.";
    }

    // ── Misceláneos ───────────────────────────────────────────────────────

    public String draftDiscarded() {
        return "Listo, no hice ninguna reserva. Si necesitas algo más, aquí estoy 😊";
    }

    public String systemError() {
        return "No pude consultar la disponibilidad en este momento. Dame un momento y vuelve a intentarlo.";
    }

    public String stillWithReservation(String question) {
        return question;
    }
}
