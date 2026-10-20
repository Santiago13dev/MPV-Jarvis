#!/usr/bin/env python3
"""
Casos E2E que dependen de la IA (Groq):
- Pregunta libre sin FAQ → respuesta IA real (processed_by=AI).
- La IA ofrece asesor → se guarda HUMAN_TRANSFER_OFFERED; "sí" transfiere y persiste el mensaje.
- "no" a la oferta → vuelve a AUTO y sigue atendiendo.
- Extracción de personas con lenguaje natural ("mis papás, mi esposa y yo") vía LLM → 4.

Requiere GROQ_API_KEY configurada en el backend. Si la IA no está disponible,
los checks de IA fallan con el texto de fallback para que se vea claramente.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_reservation_full as e2e  # noqa: E402

P1 = "573009997001"
P2 = "573009997002"
P3 = "573009997003"


def last_processed_by(phone):
    return e2e.psql(f"""
        SELECT COALESCE(m.processed_by, '')
        FROM messages m
        JOIN conversations c ON c.id=m.conversation_id
        JOIN contacts k ON k.id=c.contact_id
        WHERE k.phone='{phone}' AND m.direction='OUTBOUND'
        ORDER BY m.sent_at DESC, m.id DESC LIMIT 1""")


def main():
    for p in (P1, P2, P3):
        e2e.clean(p)

    # ── 1. Pregunta libre → IA ────────────────────────────────────────────
    print("\n" + "=" * 78 + "\nIA-1. Pregunta libre responde la IA\n" + "=" * 78)
    r = e2e.say(P1, "¿Cuál es la historia del restaurante?", "IA")
    e2e.check("IA1. respuesta no vacía", bool(r.strip()), r)
    e2e.check("IA2. processed_by=AI", last_processed_by(P1) == "AI", last_processed_by(P1))
    conv = e2e.conversation(P1)
    e2e.check("IA3. oferta de asesor registrada", conv and conv["pending_action"] == "HUMAN_TRANSFER_OFFERED", conv)

    # ── 2. Aceptar la oferta → transferencia real y persistida ───────────
    print("\n" + "=" * 78 + "\nIA-2. Acepta la oferta de asesor\n" + "=" * 78)
    r2 = e2e.say(P1, "sí", "IA")
    e2e.check_contains("IA4. confirma transferencia", r2, "asesor")
    conv = e2e.conversation(P1)
    e2e.check("IA5. HUMAN_TAKEOVER", conv and conv["status"] == "HUMAN_TAKEOVER", conv)
    e2e.check("IA6. mensaje de transferencia persistido", "asesor" in e2e.norm(r2), r2)

    # ── 3. Rechazar la oferta → sigue el bot ─────────────────────────────
    print("\n" + "=" * 78 + "\nIA-3. Rechaza la oferta de asesor\n" + "=" * 78)
    r3 = e2e.say(P2, "¿Cuál es la historia del restaurante?", "IA")
    e2e.check_contains("IA7. ofrece asesor", r3, "asesor")
    r4 = e2e.say(P2, "no", "IA")
    e2e.check_contains("IA8. rechazo responde", r4, "sigo aqui")
    conv = e2e.conversation(P2)
    e2e.check("IA9. vuelve a AUTO", conv and conv["status"] == "AUTO", conv)
    e2e.check("IA10. limpia pending_action", conv and conv["pending_action"] is None, conv)

    # ── 4. Extracción de personas por LLM y creación completa ────────────
    print("\n" + "=" * 78 + "\nIA-4. Extracción de personas con lenguaje natural\n" + "=" * 78)
    r5 = e2e.say(P3, "quiero reservar", "Familia")
    e2e.check_contains("IA11. pide personas/día", r5, "cuantas personas", "que dia")
    r6 = e2e.say(P3, "somos mis papás, mi esposa y yo", "Familia")
    d = e2e.draft(P3)
    e2e.check("IA12. LLM extrae 4 personas", d and d["party_size"] == 4, d)
    e2e.check_contains("IA13. pide el día", r6, "que dia")
    e2e.say(P3, "el 14 de noviembre a las 12:00", "Familia")
    r7 = e2e.say(P3, "a nombre de Familia Pérez", "Familia")
    e2e.check_contains("IA14. resumen con 4", r7, "4 personas", "$40.000")
    r8 = e2e.say(P3, "sí", "Familia")
    e2e.check_contains("IA15. crea la reserva", r8, "registre tu reserva")
    res = e2e.reservation(P3)
    e2e.check("IA16. 4 personas en BD", res and res["people_count"] == 4, res)
    e2e.check("IA17. total correcto", res and float(res["total_price"]) == 40000.0, res)

    for p in (P1, P2, P3):
        e2e.clean(p)

    total = len(e2e.RESULTS)
    passed = sum(1 for _, ok, _ in e2e.RESULTS if ok)
    print("\n" + "=" * 78)
    print(f"RESULTADO IA: {passed}/{total} checks en verde")
    for label, ok, detail in e2e.RESULTS:
        if not ok:
            print(f" - {label}: {detail}")
    print("=" * 78)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
