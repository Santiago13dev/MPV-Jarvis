#!/usr/bin/env python3
"""
Batería E2E completa del motor de reservas (local).

- Envía mensajes reales al webhook (como WhatsApp).
- Lee las respuestas persistidas en PostgreSQL (no depende del simulador).
- Verifica estados, precios, cupos, idempotencia y endpoints REST.

Uso: python3 scripts/test_reservation_full.py
Requiere: docker compose con postgres/backend arriba y la BD local.
"""
import base64
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import unicodedata

WEBHOOK = "http://localhost:8080/api/webhook/message"
API = "http://localhost:8080/api"
SECRET = "devsecret"
PSQL = ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "wamvp_user", "-d", "whatsapp_mvp", "-tAc"]
SEP = "@@@MSG@@@"
PNG_1PX = ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2n0kAAAAASUVORK5CYII=")

RESULTS = []
OBSERVATIONS = []


# ─────────────────────────── utilidades ───────────────────────────

def norm(s):
    s = unicodedata.normalize("NFD", s or "")
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return s.lower().strip()


def psql(sql):
    out = subprocess.run(PSQL + [sql], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def check(label, ok, detail=""):
    RESULTS.append((label, bool(ok), detail))
    print(("✅ " if ok else "❌ ") + label + ("" if ok else f"\n     → {detail}"))


def check_contains(label, text, *subs):
    t = norm(text)
    missing = [s for s in subs if norm(s) not in t]
    check(label, not missing, f"faltan={missing} | respuesta={text!r}")


def check_not_contains(label, text, *subs):
    t = norm(text)
    found = [s for s in subs if norm(s) in t]
    check(label, not found, f"no debería contener={found} | respuesta={text!r}")


def check_eq(label, actual, expected):
    check(label, actual == expected, f"esperado={expected!r} actual={actual!r}")


# ─────────────────────────── BD / webhook ───────────────────────────

def clean(phone):
    psql(f"""
    DELETE FROM reservation_receipts WHERE reservation_id IN (SELECT id FROM reservations WHERE phone_number='{phone}');
    DELETE FROM reservation_audit_log WHERE reservation_id IN (SELECT id FROM reservations WHERE phone_number='{phone}');
    DELETE FROM reservations WHERE phone_number='{phone}';
    DELETE FROM reservation_drafts WHERE conversation_id IN (
        SELECT c.id FROM conversations c JOIN contacts k ON k.id=c.contact_id WHERE k.phone='{phone}');
    DELETE FROM messages WHERE conversation_id IN (
        SELECT c.id FROM conversations c JOIN contacts k ON k.id=c.contact_id WHERE k.phone='{phone}');
    DELETE FROM conversations WHERE contact_id IN (SELECT id FROM contacts WHERE phone='{phone}');
    DELETE FROM contacts WHERE phone='{phone}';
    """)


def outbound(phone):
    raw = psql(f"""
    SELECT COALESCE(string_agg(m.content, E'{SEP}' ORDER BY m.sent_at, m.id), '')
    FROM messages m
    JOIN conversations c ON c.id=m.conversation_id
    JOIN contacts k ON k.id=c.contact_id
    WHERE k.phone='{phone}' AND m.direction='OUTBOUND'""")
    return raw.split(SEP) if raw else []


def post_webhook(phone, text, display_name="Cliente E2E", remote_jid=None, wa_id=None,
                 message_type="TEXT", media=None, media_mime=None):
    remote_jid = remote_jid or f"{phone}@s.whatsapp.net"
    body = {
        "waMessageId": wa_id or f"e2e-{phone}-{int(time.time()*1000000)}",
        "phone": phone,
        "remoteJid": remote_jid,
        "displayName": display_name,
        "content": text,
        "messageType": message_type,
    }
    if media:
        body["mediaBase64"] = media
        body["mediaMimeType"] = media_mime or "image/png"
    req = urllib.request.Request(
        WEBHOOK, data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "X-Webhook-Secret": SECRET})
    with urllib.request.urlopen(req, timeout=30) as r:
        r.read()
    return body["waMessageId"]


def send(phone, text, display_name="Cliente E2E", remote_jid=None, wa_id=None,
         message_type="TEXT", media=None, media_mime=None, wait=15):
    before = outbound(phone)
    post_webhook(phone, text, display_name, remote_jid, wa_id, message_type, media, media_mime)
    new = []
    last_len = 0
    last_change = time.time()
    deadline = time.time() + wait
    while time.time() < deadline:
        cur = outbound(phone)
        if len(cur) > len(before):
            new = cur[len(before):]
            if len(new) != last_len:
                last_len = len(new)
                last_change = time.time()
            if time.time() - last_change > 1.0:
                break
        time.sleep(0.12)
    return "\n".join(new)


def say(phone, text, name="Cliente E2E", **kw):
    print(f"\n👤 {name}: {text}")
    reply = send(phone, text, name, **kw)
    print(f"🤖 {reply if reply else '(sin respuesta)'}")
    return auto_survey(phone, reply, name, **kw)


# Respuesta automática a la encuesta de celebración (V13). El guion VIP la cambia a "no"
# para validar el precio base del espacio sin el paquete de cumpleaños.
AUTO_CELEBRATION = "cumpleaños hombre"


def auto_survey(phone, reply, name, **kw):
    """V13 agregó la encuesta de celebración (motivo + homenajeados) antes de confirmar.
    Los guiones de esta batería son anteriores: se responde por el paquete de cumpleaños
    (así el total sigue siendo $40.000) y se devuelve la respuesta que el guion espera."""
    if reply and "motivo de la reserva" in reply:
        reply = send(phone, AUTO_CELEBRATION, name, **kw)
        print(f"🤖 (auto motivo) {reply if reply else '(sin respuesta)'}")
        if reply and "homenajeados" in reply:
            reply = send(phone, "1", name, **kw)
            print(f"🤖 (auto homenajeado) {reply if reply else '(sin respuesta)'}")
    return reply


def reservations(phone):
    raw = psql(f"""
    SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (
      SELECT id, reservation_number, reservation_type, slot_date, slot_time, people_count,
             unit_price, total_price, status, customer_name, celebration_type, honorees,
             idempotency_key, hold_expires_at
      FROM reservations WHERE phone_number='{phone}' ORDER BY created_at DESC
    ) t""")
    return json.loads(raw or "[]")


def reservation(phone):
    rs = reservations(phone)
    return rs[0] if rs else None


def draft(phone):
    raw = psql(f"""
    SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (
      SELECT d.mode, d.awaiting, d.reservation_type, d.party_size, d.slot_date, d.slot_time,
             d.customer_name, d.target_reservation_id
      FROM reservation_drafts d
      JOIN conversations c ON c.id=d.conversation_id
      JOIN contacts k ON k.id=c.contact_id
      WHERE k.phone='{phone}'
    ) t""")
    rs = json.loads(raw or "[]")
    return rs[0] if rs else None


def receipts(phone):
    raw = psql(f"""
    SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (
      SELECT r.status, r.mime_type, r.file_path
      FROM reservation_receipts r
      JOIN reservations x ON x.id=r.reservation_id
      WHERE x.phone_number='{phone}' ORDER BY r.uploaded_at
    ) t""")
    return json.loads(raw or "[]")


def conversation(phone):
    raw = psql(f"""
    SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (
      SELECT c.status, c.pending_action, c.remote_jid
      FROM conversations c JOIN contacts k ON k.id=c.contact_id WHERE k.phone='{phone}'
    ) t""")
    rs = json.loads(raw or "[]")
    return rs[0] if rs else None


def set_hold_past(phone):
    # hold_expires_at se guarda en hora local del restaurante (America/Bogota), no en UTC.
    psql(f"UPDATE reservations SET hold_expires_at = (NOW() AT TIME ZONE 'America/Bogota') - INTERVAL '2 minutes' "
         f"WHERE phone_number='{phone}'")


# ─────────────────────────── API REST ───────────────────────────

def api(method, path, body=None, token=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def login():
    return api("POST", "/auth/login", {"email": "admin@example.com", "password": "admin123"})["data"]["accessToken"]


# ─────────────────────────── casos ───────────────────────────

PHONES = {k: f"5730099930{i:02d}" for i, k in enumerate(
    ["normal1", "normal2", "vip", "vipinfo", "vipcap", "vipdup", "normal3", "valid0", "valid50",
     "closed", "past", "time15", "time8", "avail1", "avail2", "nobook", "cancelmid", "receipt",
     "nopay", "hold", "human", "faq", "welcome", "location", "menu", "dedup", "lid", "rate",
     "modify", "name", "extra1", "extra2", "holiday", "normalmon"], start=1)}

SAT = "2026-10-31"
SUN = "2026-11-01"
HOLIDAY_MONDAY = "2026-11-02"   # lunes festivo (Todos los Santos)
NORMAL_MONDAY = "2026-11-09"    # lunes normal, cerrado


def run_create_normal():
    print("\n" + "=" * 78 + "\nA. CREAR — normal en un solo mensaje\n" + "=" * 78)
    p = PHONES["normal1"]
    clean(p)
    r = say(p, "reserva para 6 personas el 31 de octubre a las 12:30 a nombre de Carla Ruiz", "Carla")
    check_contains("A1. pide confirmación con resumen", r, "tengo estos datos", "deseas continuar", "6 personas", "$40.000")
    r2 = say(p, "sí", "Carla")
    check_contains("A2. confirma creación", r2, "registre tu reserva", "$40.000", "6 personas")
    res = reservation(p)
    check("A3. reserva en BD", res is not None, "sin reserva")
    if res:
        check_eq("A4. tipo NORMAL", res["reservation_type"], "NORMAL")
        check_eq("A5. personas", res["people_count"], 6)
        check_eq("A6. fecha", res["slot_date"], SAT)
        check_eq("A7. hora", res["slot_time"], "12:30:00")
        check_eq("A8. total", float(res["total_price"]), 40000.0)
        check_eq("A9. estado", res["status"], "PAYMENT_PENDING")
        check_eq("A10. nombre", res["customer_name"], "Carla Ruiz")
        check("A11. idempotency key wa:", str(res["idempotency_key"]).startswith("wa:"), res["idempotency_key"])
        check("A12. hold futuro", str(res["hold_expires_at"]) > time.strftime("%Y-%m-%d"), res["hold_expires_at"])

    # ── V13: reserva normal sin celebración = sin costo ──────────────────────
    p2 = PHONES["normal2"]
    clean(p2)
    r = send(p2, "reserva para 4 personas el 31 de octubre a las 12:00 a nombre de Luis Gómez", "Luis")
    check_contains("A13. pregunta motivo de celebración", r, "motivo de la reserva")
    r = send(p2, "no", "Luis")
    check_contains("A14. resumen sin costo", r, "sin costo", "4 personas")
    r = send(p2, "sí", "Luis")
    check_contains("A15. crea reserva gratis", r, "registre tu reserva")
    res2 = reservation(p2)
    check_eq("A16. total 0", res2 and float(res2["total_price"]), 0.0)
    check_eq("A17. sin celebración", res2 and res2["celebration_type"], "NONE")

    # ── Encuesta de celebración: no deja la conversación atrapada ────────────
    p3 = PHONES["name"]
    clean(p3)
    r = send(p3, "reserva para 4 personas el 31 de octubre a las 12:00", "Atrapado")
    check_contains("A18. pide motivo", r, "motivo de la reserva")
    r = send(p3, "hola", "Atrapado")
    check_contains("A19. segunda oportunidad", r, "no entendi el motivo")
    r = send(p3, "que pena", "Atrapado")
    check_contains("A20. cierra la encuesta sola", r, "sin celebracion")
    r = send(p3, "Luis Gómez", "Atrapado")
    check_contains("A21. pide confirmación", r, "tengo estos datos")
    r = send(p3, "sí", "Atrapado")
    check_contains("A22. reserva creada tras el rescate", r, "registre tu reserva")


def run_create_steps():
    print("\n" + "=" * 78 + "\nB. CREAR — paso a paso\n" + "=" * 78)
    p = PHONES["normal2"]
    clean(p)
    r1 = say(p, "quiero reservar", "Diego")
    check_contains("B1. pide personas y día", r1, "cuantas personas", "que dia")
    r2 = say(p, "somos 4", "Diego")
    check_contains("B2. pide día", r2, "que dia")
    r3 = say(p, "el 31 de octubre", "Diego")
    check_contains("B3. ofrece horarios", r3, "disponibilidad", "11:30")
    r4 = say(p, "a las 11:30", "Diego")
    check_contains("B4. pide nombre", r4, "a nombre de quien")
    r5 = say(p, "me llamo Diego Mora", "Diego")
    check_contains("B5. resumen", r5, "tengo estos datos", "4 personas", "$40.000")
    r6 = say(p, "sí", "Diego")
    check_contains("B6. crea", r6, "registre tu reserva", "#")
    res = reservation(p)
    check("B7. BD", res is not None, "sin reserva")
    if res:
        check_eq("B8. personas", res["people_count"], 4)
        check_eq("B9. total", float(res["total_price"]), 40000.0)
        check_eq("B10. hora", res["slot_time"], "11:30:00")
        check_eq("B11. nombre", res["customer_name"], "Diego Mora")


def run_vip():
    print("\n" + "=" * 78 + "\nC. VIP — información, creación y precio\n" + "=" * 78)
    global AUTO_CELEBRATION
    AUTO_CELEBRATION = "no"   # el VIP se prueba sin paquete: total = valor del espacio
    p = PHONES["vip"]
    clean(p)
    r1 = say(p, "¿tienen VIP?", "Valentina")
    check_contains("C1. info VIP", r1, "espacio vip privado", "$100.000", "hasta 15")
    r2 = say(p, "para 10 personas el 31 de octubre", "Valentina")
    check_contains("C2. ofrece horarios", r2, "disponibilidad", "11:30")
    r3 = say(p, "a las 12:00", "Valentina")
    check_contains("C3. pide nombre", r3, "a nombre de quien")
    r4 = say(p, "a nombre de Valentina Ríos", "Valentina")
    check_contains("C4. resumen VIP", r4, "espacio vip privado", "$100.000", "valor del espacio completo")
    r5 = say(p, "sí", "Valentina")
    check_contains("C5. crea VIP", r5, "registre tu reserva", "vip", "$100.000")
    res = reservation(p)
    check("C6. VIP en BD", res is not None, "sin reserva")
    if res:
        check_eq("C7. tipo", res["reservation_type"], "VIP")
        check_eq("C8. total", float(res["total_price"]), 100000.0)
        check_eq("C9. hora", res["slot_time"], "12:00:00")
        check_eq("C10. estado", res["status"], "PAYMENT_PENDING")

    # Precio + descarte de borrador
    p2 = PHONES["vipinfo"]
    clean(p2)
    r6 = say(p2, "¿cuánto cuesta el VIP?", "Info")
    check_contains("C11. responde precio VIP", r6, "espacio vip privado", "$100.000")
    r7 = say(p2, "olvídalo", "Info")
    check_contains("C12. descarta borrador", r7, "no hice ninguna reserva")
    check("C13. sin reserva tras descartar", reservation(p2) is None, "creó una reserva")
    check("C14. sin draft tras descartar", draft(p2) is None, draft(p2))
    AUTO_CELEBRATION = "cumpleaños hombre"


def run_vip_limits():
    print("\n" + "=" * 78 + "\nD. VIP — capacidad y duplicado\n" + "=" * 78)
    p = PHONES["vipcap"]
    clean(p)
    r = say(p, "quiero el VIP para 25 personas el 31 de octubre a las 12:00", "Grupo")
    check_contains("D1. rechaza por capacidad VIP", r, "capacidad para hasta 15")
    check("D2. no crea reserva", reservation(p) is None, "creó reserva")

    p2 = PHONES["vipdup"]
    clean(p2)
    # El VIP de Valentina ya está reservado ese día: otra franja del mismo día tampoco se ofrece.
    r2 = say(p2, "quiero el VIP para 6 personas el 31 de octubre a la 1:00", "Duplicado")
    check_contains("D3. bloquea VIP ocupado todo el día", r2, "ya esta reservado", "asesor")
    check("D4. no crea duplicado", reservation(p2) is None, "creó reserva")
    conv = conversation(p2)
    check("D5. ofrece transferencia a asesor", conv and conv["pending_action"] == "HUMAN_TRANSFER_OFFERED", conv)
    r3 = say(p2, "sí", "Duplicado")
    check_contains("D6. transfiere a asesor", r3, "asesor")
    conv = conversation(p2)
    check("D7. HUMAN_TAKEOVER", conv and conv["status"] == "HUMAN_TAKEOVER", conv)


def run_normal_slot():
    print("\n" + "=" * 78 + "\nE. CREAR — hora inválida y reintento\n" + "=" * 78)
    p = PHONES["normal3"]
    clean(p)
    r = say(p, "reserva para 5 personas el 31 de octubre a las 12:45", "Gabriela")
    check_contains("E1. rechaza hora inválida", r, "horarios", "11:30")
    check("E2. no crea aún", reservation(p) is None, "creó antes de confirmar")
    r2 = say(p, "a las 12:30", "Gabriela")
    check_contains("E3. acepta hora ofrecida", r2, "a nombre de quien")
    r3 = say(p, "a nombre de Gabriela Díaz", "Gabriela")
    check_contains("E4. resumen", r3, "5 personas", "$40.000")
    r4 = say(p, "sí", "Gabriela")
    check_contains("E5. crea", r4, "registre tu reserva")
    res = reservation(p)
    check("E6. BD", res is not None, "sin reserva")
    if res:
        check_eq("E7. hora", res["slot_time"], "12:30:00")
        check_eq("E8. total", float(res["total_price"]), 40000.0)


def run_validations():
    print("\n" + "=" * 78 + "\nF. VALIDACIONES — personas, día, fecha, hora\n" + "=" * 78)
    cases = [
        ("valid0", "reserva para 0 personas el 31 de octubre", ["numero de personas valido", "minimo 1"]),
        ("valid50", "reserva para 50 personas el 31 de octubre", ["disponibilidad"]),
        ("closed", "reserva para 4 personas el 30 de octubre", ["no tenemos servicio"]),
        ("past", "reserva para 4 personas el 18 de octubre de 2025", ["ya paso"]),
        ("time15", "reserva para 4 personas el 31 de octubre a las 3 de la tarde", ["horarios", "11:30"]),
        ("time8", "reserva para 4 personas el 31 de octubre a las 8 de la mañana", ["horarios", "11:30"]),
    ]
    for key, msg, subs in cases:
        p = PHONES[key]
        clean(p)
        r = say(p, msg, key)
        check_contains(f"F.{key}. respuesta correcta", r, *subs)
        check(f"F.{key}. no crea reserva", reservation(p) is None, "creó reserva")


def run_holiday_monday():
    print("\n" + "=" * 78 + "\nF2. LUNES FESTIVOS — abren; lunes normales no\n" + "=" * 78)
    p = PHONES["holiday"]
    clean(p)
    r = say(p, "reserva para 4 personas el lunes 2 de noviembre a las 12:00 a nombre de Ana Festiva", "Ana")
    check_contains("F2.1 acepta lunes festivo", r, "tengo estos datos", "lunes 2 de noviembre", "12:00")
    r2 = say(p, "sí", "Ana")
    check_contains("F2.2 crea reserva de lunes festivo", r2, "registre tu reserva", "4 personas")
    res = reservation(p)
    check("F2.3 reserva en BD", res is not None, "sin reserva")
    if res:
        check_eq("F2.4 fecha festiva", res["slot_date"], HOLIDAY_MONDAY)

    p2 = PHONES["normalmon"]
    clean(p2)
    r3 = say(p2, "reserva para 4 personas el lunes 9 de noviembre a las 12:00", "Normal")
    check_contains("F2.5 cierra lunes normal", r3, "no tenemos servicio", "lunes festivos")
    check("F2.6 no crea lunes normal", reservation(p2) is None, "creó reserva")


def run_availability_and_get():
    print("\n" + "=" * 78 + "\nG. DISPONIBILIDAD Y CONSULTA DE RESERVA\n" + "=" * 78)
    for key, msg in [
        ("avail1", "¿hay disponibilidad para 6 personas el 31 de octubre?"),
        ("avail2", "¿tienen mesa para 6 el 31 de octubre?"),
    ]:
        p = PHONES[key]
        clean(p)
        r = say(p, msg, key)
        check_contains(f"G.{key}. muestra horarios", r, "disponibilidad", "11:30")

    p = PHONES["normal3"]
    r = say(p, "tengo reserva a las 12:30", "Gabriela")
    check_contains("G3. consulta por hora", r, "#", "12:30", "pendiente de pago")
    r2 = say(p, "¿cuál es mi reserva?", "Gabriela")
    check_contains("G4. consulta general", r2, "#", "12:30")

    p2 = PHONES["nobook"]
    clean(p2)
    r3 = say(p2, "tengo reserva", "Sin reserva")
    check_contains("G5. sin reservas", r3, "no encuentro reservas activas")


def run_modify():
    print("\n" + "=" * 78 + "\nH. MODIFICAR — personas, hora y tipo\n" + "=" * 78)
    p = PHONES["normal3"]  # reserva 5 personas 12:30

    r = say(p, "quiero cambiar mi reserva a 6 personas", "Gabriela")
    check_contains("H1. propone cambio + precio", r, "cambio", "6 personas", "$40.000")
    r2 = say(p, "sí", "Gabriela")
    check_contains("H2. aplica cambio", r2, "actualice tu reserva", "6 personas", "$40.000")
    res = reservation(p)
    check_eq("H3. personas en BD", res and res["people_count"], 6)
    check_eq("H4. total en BD", res and float(res["total_price"]), 40000.0)

    r3 = say(p, "quiero cambiar la hora de mi reserva a la 1:00", "Gabriela")
    check_contains("H5. propone nueva hora", r3, "1:00")
    r4 = say(p, "sí", "Gabriela")
    check_contains("H6. aplica hora", r4, "actualice tu reserva", "1:00")
    res = reservation(p)
    check_eq("H7. hora en BD", res and res["slot_time"], "13:00:00")

    r5 = say(p, "quiero cambiar mi reserva a 8 personas", "Gabriela")
    check_contains("H8. propone 8", r5, "8 personas", "$40.000")
    r6 = say(p, "sí", "Gabriela")
    check_contains("H9. aplica 8", r6, "actualice tu reserva", "$40.000")
    res = reservation(p)
    check_eq("H10. total en BD", res and float(res["total_price"]), 40000.0)

    # Incremento/decremento natural: "llevar 2 más", "quitar 2 personas"
    r8 = say(p, "puedo llevar 2 más", "Gabriela")
    check_contains("H13. propone incremento", r8, "10 personas")
    r9 = say(p, "sí", "Gabriela")
    check_contains("H14. aplica incremento", r9, "actualice tu reserva", "10 personas")
    res = reservation(p)
    check_eq("H15. total con incremento", res and float(res["total_price"]), 40000.0)

    r10 = say(p, "quitar 2 personas", "Gabriela")
    check_contains("H16. propone decremento", r10, "8 personas")
    r11 = say(p, "sí", "Gabriela")
    check_contains("H17. aplica decremento", r11, "actualice tu reserva", "8 personas")
    res = reservation(p)
    check_eq("H18. total con decremento", res and float(res["total_price"]), 40000.0)

    r12 = say(p, "quiero cambiar el nombre de la reserva a Gabriela Torres", "Gabriela")
    check_contains("H19. propone cambio de nombre", r12, "Gabriela Torres")
    r13 = say(p, "sí", "Gabriela")
    check_contains("H20. aplica cambio de nombre", r13, "actualice tu reserva", "Gabriela Torres")
    res = reservation(p)
    check_eq("H21. nombre en BD", res and res["customer_name"], "Gabriela Torres")

    r7 = say(p, "no", "Gabriela")
    check("H22. 'no' sin cambio pendiente no rompe", r7 is not None, "sin respuesta")
    res = reservation(p)
    check_eq("H23. sigue en 8", res and res["people_count"], 8)


def run_cancel():
    print("\n" + "=" * 78 + "\nI. CANCELAR — con confirmación y a mitad de creación\n" + "=" * 78)
    p = PHONES["normal3"]
    r = say(p, "no puedo asistir, quiero cancelar", "Gabriela")
    check_contains("I1. pide confirmación", r, "confirmas que quieres cancelar", "#")
    r2 = say(p, "no", "Gabriela")
    check("I2. 'no' mantiene activa", reservation(p) and reservation(p)["status"] == "PAYMENT_PENDING",
          reservation(p))
    r3 = say(p, "quiero cancelar mi reserva", "Gabriela")
    check_contains("I3. vuelve a pedir confirmación", r3, "confirmas que quieres cancelar")
    r4 = say(p, "sí", "Gabriela")
    check_contains("I4. cancela", r4, "cancele tu reserva", "#")
    check_eq("I5. estado CANCELLED", reservation(p) and reservation(p)["status"], "CANCELLED")
    r5 = say(p, "cancelar mi reserva", "Gabriela")
    check_contains("I6. ya no hay activa", r5, "no encuentro reservas activas")

    p2 = PHONES["cancelmid"]
    clean(p2)
    r6 = say(p2, "quiero reservar para 4 personas", "Mid")
    check_contains("I7. inicia creación", r6, "que dia")
    r7 = say(p2, "olvídalo", "Mid")
    check_contains("I8. descarta borrador", r7, "no hice ninguna reserva")
    check("I9. sin reserva", reservation(p2) is None, "creó reserva")
    check("I10. sin draft", draft(p2) is None, draft(p2))


def run_receipt():
    print("\n" + "=" * 78 + "\nJ. COMPROBANTES DE PAGO\n" + "=" * 78)
    p = PHONES["receipt"]
    clean(p)
    r = say(p, "reserva para 2 personas el 31 de octubre a las 13:00 a nombre de Rosa Pérez", "Rosa")
    check_contains("J1. resumen", r, "2 personas", "$40.000")
    r2 = say(p, "sí", "Rosa")
    check_contains("J2. creada", r2, "registre tu reserva")
    check_eq("J3. estado inicial", reservation(p) and reservation(p)["status"], "PAYMENT_PENDING")

    r3 = say(p, "ya pagué", "Rosa")
    check_contains("J4. pide comprobante", r3, "comprobante")
    r4 = say(p, "aquí está", "Rosa", message_type="IMAGE", media=PNG_1PX, media_mime="image/png")
    check_contains("J5. recibe comprobante", r4, "recibi tu comprobante")
    check_eq("J6. estado en revisión", reservation(p) and reservation(p)["status"], "PAYMENT_RECEIPT_SENT")
    rec = receipts(p)
    check("J7. recibo en BD", len(rec) == 1, rec)
    r4b = say(p, "ya pagué", "Rosa")
    check_contains("J7b. comprobante en revisión", r4b, "en revision")

    token = login()
    rid = reservation(p)["id"]
    conf = api("POST", f"/reservations/{rid}/confirm", token=token)
    check("J8. confirma desde API", conf.get("success") is True, conf)
    check_eq("J9. estado CONFIRMED", reservation(p) and reservation(p)["status"], "CONFIRMED")
    rec = receipts(p)
    check_eq("J10. recibo ACCEPTED", rec[0]["status"] if rec else None, "ACCEPTED")

    # Ya confirmada: "ya pagué" no debe pedir otro comprobante
    r5 = say(p, "ya pagué", "Rosa")
    check_contains("J11. ya confirmada", r5, "ya esta confirmada")

    # Sin reserva pendiente
    p2 = PHONES["nopay"]
    clean(p2)
    r6 = say(p2, "ya pagué", "SinPago")
    check_contains("J12. sin reserva", r6, "no encuentro reservas activas")
    r7 = say(p2, "aquí está", "SinPago", message_type="IMAGE", media=PNG_1PX, media_mime="image/png")
    check_contains("J13. archivo sin reserva deriva a asesor", r7, "recibi tu archivo", "asesor")

    # Rechazo de comprobante
    p3 = PHONES["extra1"]
    clean(p3)
    r8 = say(p3, "reserva para 2 personas el 31 de octubre a las 11:30 a nombre de Pedro Ruiz", "Pedro")
    check_contains("J14. resumen rechazo", r8, "2 personas")
    r9 = say(p3, "sí", "Pedro")
    check_contains("J15. creada rechazo", r9, "registre tu reserva")
    say(p3, "ya pagué", "Pedro")
    say(p3, "comprobante", "Pedro", message_type="IMAGE", media=PNG_1PX, media_mime="image/png")
    check_eq("J16. en revisión", reservation(p3) and reservation(p3)["status"], "PAYMENT_RECEIPT_SENT")
    rid3 = reservation(p3)["id"]
    rej = api("POST", f"/reservations/{rid3}/reject-receipt", {"note": "Comprobante ilegible"}, token=token)
    check("J17. rechaza desde API", rej.get("success") is True, rej)
    check_eq("J18. vuelve a PAYMENT_PENDING", reservation(p3) and reservation(p3)["status"], "PAYMENT_PENDING")
    rec3 = receipts(p3)
    check_eq("J19. recibo REJECTED", rec3[0]["status"] if rec3 else None, "REJECTED")


def run_human_transfer():
    print("\n" + "=" * 78 + "\nK. TRANSFERENCIA A HUMANO\n" + "=" * 78)
    p = PHONES["human"]
    clean(p)
    r = say(p, "quiero hablar con un asesor", "Cliente")
    check_contains("K1. transfiere", r, "asesor")
    conv = conversation(p)
    check_eq("K2. estado HUMAN_TAKEOVER", conv and conv["status"], "HUMAN_TAKEOVER")
    r2 = say(p, "hola", "Cliente", wait=4)
    check("K3. no automatiza en takeover", r2 == "", r2)


def run_faqs():
    print("\n" + "=" * 78 + "\nL. FAQs (7 locales)\n" + "=" * 78)
    p = PHONES["faq"]
    clean(p)
    cases = [
        ("¿Tienen WiFi?", ["no contamos con servicio de wifi"]),
        ("¿Puedo llevar a mi mascota?", ["pet friendly"]),
        ("¿Hacen domicilios?", ["no contamos con servicio de domicilio"]),
        ("¿Cuál es el horario de atención?", ["sabados", "11:30"]),
        ("¿A qué hora abren?", ["sabados", "11:30"]),
        ("¿Cuáles son los métodos de pago?", ["nequi", "efectivo", "transferencia", "llave"]),
        ("¿Cuánto cuesta la decoración?", ["$40.000"]),
        ("¿Cómo puedo hacer un pedido?", ["whatsapp"]),
        ("¿Emiten factura electrónica?", ["factura electronica"]),
        ("¿Cuál es la política de cancelación?", ["no hay devolucion"]),
        ("¿Cuántas mesas tienen?", ["112"]),
        ("¿Hay música en vivo?", ["domingos"]),
        ("¿Cuánto tiempo puedo esperar?", ["15 minutos"]),
    ]
    for i, (q, subs) in enumerate(cases, 1):
        r = say(p, q, "FAQ")
        check_contains(f"L{i}. FAQ {q}", r, *subs)
        check_not_contains(f"L{i}b. no es bienvenida", r, "bienvenido")


def run_pipeline():
    print("\n" + "=" * 78 + "\nM. PIPELINE — bienvenida, ubicación, menú, dedup, @lid\n" + "=" * 78)
    p = PHONES["welcome"]
    clean(p)
    r = say(p, "hola", "Nuevo")
    check_contains("M1. bienvenida", r, "bienvenido")

    p2 = PHONES["location"]
    clean(p2)
    r2 = say(p2, "¿dónde están?", "Ubicación")
    check_contains("M2. ubicación", r2, "sibate")

    p3 = PHONES["menu"]
    clean(p3)
    r3 = say(p3, "menú", "Menú")
    check_contains("M3. menú", r3, "menu")

    # Dedup por waMessageId
    p4 = PHONES["dedup"]
    clean(p4)
    wid = f"dedup-{int(time.time()*1000000)}"
    before = len(outbound(p4))
    post_webhook(p4, "¿Tienen WiFi?", wa_id=wid)
    time.sleep(2)
    post_webhook(p4, "¿Tienen WiFi?", wa_id=wid)
    time.sleep(2)
    after = outbound(p4)
    check_eq("M4. dedup no duplica respuesta", len(after) - before, 1)

    # @lid
    p5 = PHONES["lid"]
    clean(p5)
    lid = f"{p5}@lid"
    say(p5, "hola", "LID", remote_jid=lid)
    conv = conversation(p5)
    check_eq("M5. conserva remote_jid @lid", conv and conv["remote_jid"], lid)


def run_rate_limit():
    print("\n" + "=" * 78 + "\nN. RATE LIMIT (30/5min)\n" + "=" * 78)
    p = PHONES["rate"]
    clean(p)
    for i in range(31):
        post_webhook(p, f"mensaje {i}")
    found = False
    deadline = time.time() + 50
    while time.time() < deadline:
        msgs = outbound(p)
        if any("muchos mensajes" in norm(m) for m in msgs):
            found = True
            break
        time.sleep(1)
    msgs = outbound(p)
    check("N1. aviso de rate limit", found, f"{len(msgs)} respuestas, sin aviso")
    logs = subprocess.run(["docker", "logs", "wamvp_backend", "--since", "5m"],
                          capture_output=True, text=True).stdout
    check("N2. log RateLimit exceeded", "RateLimit" in logs and "exceeded" in logs, "sin log")


def run_concurrency():
    print("\n" + "=" * 78 + "\nO. CONCURRENCIA — cupo 2, 5 solicitudes simultáneas\n" + "=" * 78)
    token = login()
    psql("UPDATE restaurant_settings SET default_slot_capacity=2 WHERE id=1")
    phones = [f"5730099940{i:02d}" for i in range(1, 6)]
    for p in phones:
        clean(p)
    results = []

    def create(p, idx):
        try:
            body = {"customerName": f"Conc {idx}", "phoneNumber": p, "reservationType": "NORMAL",
                    "date": "2026-11-07", "time": "11:30", "partySize": 1, "status": "CONFIRMED"}
            r = api("POST", "/reservations", body, token)
            results.append(("ok", p, r))
        except urllib.error.HTTPError as e:
            results.append(("err", p, e.read().decode()))

    threads = [threading.Thread(target=create, args=(p, i)) for i, p in enumerate(phones)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    ok = [r for r in results if r[0] == "ok"]
    err = [r for r in results if r[0] == "err"]
    check_eq("O1. exactamente 2 reservas", len(ok), 2)
    check_eq("O2. exactamente 3 rechazadas", len(err), 3)
    print(f"   resultados: {len(ok)} ok / {len(err)} rechazadas")
    for p in phones:
        clean(p)
    psql("UPDATE restaurant_settings SET default_slot_capacity=50 WHERE id=1")
    check_eq("O3. capacidad restaurada", psql("SELECT default_slot_capacity FROM restaurant_settings WHERE id=1"), "50")


def run_hold_expiration():
    print("\n" + "=" * 78 + "\nP. EXPIRACIÓN DE HOLD\n" + "=" * 78)
    p = PHONES["hold"]
    clean(p)
    r = say(p, "reserva para 2 personas el 7 de noviembre a las 12:00 a nombre de Hugo Pardo", "Hugo")
    check_contains("P1. resumen", r, "2 personas")
    r2 = say(p, "sí", "Hugo")
    check_contains("P2. creada", r2, "registre tu reserva")
    check_eq("P3. PAYMENT_PENDING", reservation(p) and reservation(p)["status"], "PAYMENT_PENDING")
    set_hold_past(p)
    status = None
    for _ in range(20):
        time.sleep(5)
        res = reservation(p)
        status = res["status"] if res else None
        if status == "EXPIRED":
            break
    check_eq("P4. expira por hold", status, "EXPIRED")

    # El cupo quedó libre: se puede volver a reservar la misma franja
    p2 = PHONES["extra2"]
    clean(p2)
    r3 = say(p2, "reserva para 2 personas el 7 de noviembre a las 12:00 a nombre de Inés Gil", "Inés")
    check_contains("P5. franja liberada", r3, "2 personas")
    r4 = say(p2, "sí", "Inés")
    check_contains("P6. re-reserva", r4, "registre tu reserva")


def main():
    print("🧪 Batería E2E completa — La Montaña del Bendito Chicharrón")
    # limpieza inicial de todos los teléfonos de prueba
    for p in PHONES.values():
        clean(p)
    run_create_normal()
    run_create_steps()
    run_vip()
    run_vip_limits()
    run_normal_slot()
    run_validations()
    run_holiday_monday()
    run_availability_and_get()
    run_modify()
    run_cancel()
    run_receipt()
    run_human_transfer()
    run_faqs()
    run_pipeline()
    run_rate_limit()
    run_concurrency()
    run_hold_expiration()

    total = len(RESULTS)
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    print("\n" + "=" * 78)
    print(f"RESULTADO: {passed}/{total} checks en verde")
    if passed != total:
        print("Fallos:")
        for label, ok, detail in RESULTS:
            if not ok:
                print(f" - {label}: {detail}")
    print("=" * 78)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
