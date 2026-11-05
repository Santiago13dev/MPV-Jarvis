#!/usr/bin/env python3
"""Suite de aceptación del motor de reservas (conversacional, contra el webhook real).

Cubre los caminos que rompieron con la clienta @AV.ORTIZ25 y los arreglos posteriores:
encuesta de celebración con salida de emergencia, horas compactas, fecha pasada,
día de la semana vs número, nombre en la confirmación, VIP sobre capacidad y
domingo en reserva normal.

Uso (con el stack local arriba):
    python3 scripts/test_reservation_flows.py

Requiere: backend en :8080 (X-Webhook-Secret: devsecret) y la BD en Docker
(lee las respuestas con `docker compose exec postgres psql`).
"""
import json
import subprocess
import time
import urllib.request

WEBHOOK = "http://localhost:8080/api/webhook/message"
SECRET = "devsecret"
PSQL = ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "wamvp_user", "-d", "whatsapp_mvp", "-tAc"]
SEP = "@@@MSG@@@"

OK = []
FAIL = []


def psql(sql):
    return subprocess.run(PSQL + [sql], capture_output=True, text=True).stdout.strip()


def outbound(phone):
    raw = psql(f"""SELECT COALESCE(string_agg(m.content, E'{SEP}' ORDER BY m.sent_at, m.id), '')
    FROM messages m JOIN conversations c ON c.id=m.conversation_id
    JOIN contacts k ON k.id=c.contact_id
    WHERE k.phone='{phone}' AND m.direction='OUTBOUND'""")
    return raw.split(SEP) if raw else []


def send(phone, text):
    before = outbound(phone)
    body = {"waMessageId": f"st-{phone}-{int(time.time() * 1000000)}", "phone": phone,
            "remoteJid": f"{phone}@s.whatsapp.net", "displayName": "Suite", "content": text,
            "messageType": "TEXT"}
    req = urllib.request.Request(WEBHOOK, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Webhook-Secret": SECRET})
    urllib.request.urlopen(req, timeout=40).read()
    deadline, new, last_len, last_change = time.time() + 20, [], 0, time.time()
    while time.time() < deadline:
        cur = outbound(phone)
        if len(cur) > len(before):
            new = cur[len(before):]
            if len(new) != last_len:
                last_len, last_change = len(new), time.time()
            if time.time() - last_change > 1.2:
                break
        time.sleep(0.1)
    return "\n".join(new)


def say(phone, text):
    reply = send(phone, text)
    print(f"   👤 {text}\n   🤖 {reply.replace(chr(10), ' / ')[:220]}")
    return reply


def reset(phone):
    psql(f"""DELETE FROM reservation_receipts WHERE reservation_id IN (SELECT id FROM reservations WHERE phone_number='{phone}');
    DELETE FROM reservation_audit_log WHERE reservation_id IN (SELECT id FROM reservations WHERE phone_number='{phone}');
    DELETE FROM messages WHERE conversation_id IN (SELECT c.id FROM conversations c JOIN contacts k ON k.id=c.contact_id WHERE k.phone='{phone}');
    DELETE FROM reservation_drafts WHERE conversation_id IN (SELECT c.id FROM conversations c JOIN contacts k ON k.id=c.contact_id WHERE k.phone='{phone}');
    DELETE FROM reservations WHERE phone_number='{phone}';
    DELETE FROM conversations WHERE contact_id IN (SELECT id FROM contacts WHERE phone='{phone}');
    DELETE FROM contacts WHERE phone='{phone}';""")


def draft_row(phone):
    return psql(f"""SELECT COALESCE(d.awaiting||'|'||COALESCE(d.party_size::text,'-')||'|'||COALESCE(d.slot_date::text,'-')
||'|'||COALESCE(d.slot_time::text,'-')||'|'||COALESCE(d.celebration_type,'-'),'-') FROM reservation_drafts d
JOIN conversations c ON c.id=d.conversation_id JOIN contacts k ON k.id=c.contact_id
WHERE k.phone='{phone}'""") or "-"


def res_row(phone):
    return psql(f"""SELECT reservation_type||'|'||slot_date||'|'||slot_time||'|'
||people_count||'|'||total_price||'|'||status||'|'||COALESCE(celebration_type,'-')||'|'||COALESCE(customer_name,'-')
||'|'||COALESCE(honorees::text,'-') FROM reservations WHERE phone_number='{phone}' ORDER BY created_at DESC LIMIT 1""")


def check(name, cond, detail=""):
    if cond:
        OK.append(name)
        print(f"✅ {name}")
    else:
        FAIL.append(name)
        print(f"❌ {name}\n     → {detail}")


def has(reply, *needles):
    return all(n.lower() in reply.lower() for n in needles)


print("=" * 78)
print("SUITE DE ACEPTACIÓN — motor de reservas")
print("=" * 78)

# ── 1. Normal + cumpleaños, camino feliz completo ────────────────────────────
print("\n[1] Camino feliz: cumpleaños mujer, 1 homenajeado")
p = "573000002001"
reset(p)
r = say(p, "reserva para 6 el sábado 31 de octubre a las 12:30 a nombre de Carla Ruiz")
check("1a pide motivo de celebración", has(r, "motivo de la reserva"), r[:160])
r = say(p, "cumpleaños mujer")
check("1b pide homenajeados", has(r, "homenajeados"), r[:160])
r = say(p, "1")
check("1c resumen con $40.000", has(r, "Tengo estos datos", "$40.000", "6 personas"), r[:200])
r = say(p, "sí")
check("1d reserva creada", has(r, "Registré tu reserva"), r[:160])
row = res_row(p)
check("1e BD: 40.000 / BIRTHDAY_FEMALE / 6 / 12:30",
      "40000" in row.replace("40000.00", "40000") and "BIRTHDAY_FEMALE" in row and "|6|" in row and "12:30" in row, row)

# ── 2. Normal sin celebración ("no") ────────────────────────────────────────
print("\n[2] Sin celebración: responde 'no'")
p = "573000002002"
reset(p)
say(p, "reserva para 4 el sábado 31 a las 12:30 a nombre de Ana")
r = say(p, "no")
check("2a resumen sin costo", has(r, "Tengo estos datos", "Sin costo"), r[:200])
r = say(p, "sí")
check("2b reserva creada", has(r, "Registré tu reserva"), r[:160])
row = res_row(p)
check("2c BD: NONE y total 0", "|NONE|" in row and row.split("|")[4].startswith("0"), row)

# ── 3. Rescate de la encuesta (dos respuestas que no son motivo) ────────────
print("\n[3] Rescate: 'hola' dos veces y sigue")
p = "573000002003"
reset(p)
r = say(p, "reserva para 6 el sábado 31 a las 12:30")
check("3a pide motivo", has(r, "motivo de la reserva"), r[:160])
r = say(p, "hola")
check("3b segunda oportunidad", has(r, "No entendí el motivo"), r[:160])
r = say(p, "que pena")
check("3c cierra encuesta sola", has(r, "sin celebración"), r[:200])
r = say(p, "Carla Ruiz")
check("3d pide confirmación", has(r, "Tengo estos datos"), r[:200])
r = say(p, "sí")
check("3e reserva creada", has(r, "Registré tu reserva"), r[:160])
check("3f nombre correcto", "|Carla Ruiz" in res_row(p), res_row(p))

# ── 4. VIP ─────────────────────────────────────────────────────────────────
print("\n[4] VIP 15 personas")
p = "573000002004"
reset(p)
r = say(p, "quiero el vip para 15 personas el sábado 7 de noviembre a las 12:30 a nombre de Ana")
check("4a pide motivo", has(r, "motivo de la reserva"), r[:160])
r = say(p, "aniversario")
check("4b pide homenajeados", has(r, "homenajeados"), r[:160])
r = say(p, "2")
check("4c resumen VIP", has(r, "VIP", "15 personas"), r[:220])
r = say(p, "sí")
check("4d reserva VIP creada", has(r, "Registré tu reserva"), r[:160])
check("4e BD: VIP 15", res_row(p).startswith("VIP|") and res_row(p).split("|")[3] == "15", res_row(p))

# ── 5. Hora compacta dentro de una frase ───────────────────────────────────
print("\n[5] Hora compacta 'a las 1230' en frase completa")
p = "573000002005"
reset(p)
say(p, "reserva para 4 el sábado 31 a las 1230")
d = draft_row(p)
check("5a hora 12:30 registrada", "|12:30" in d, d)

# ── 6. Fecha pasada ────────────────────────────────────────────────────────
print("\n[6] Fecha que ya pasó")
p = "573000002006"
reset(p)
r = say(p, "reserva para 4 el 24 de octubre a las 12:30")
check("6a avisa que ya pasó", has(r, "ya pasó"), r[:200])
check("6b no agenda 2027", "2027" not in draft_row(p) and "2027" not in res_row(p), draft_row(p) + res_row(p))

# ── 7. Día de la semana vs número ──────────────────────────────────────────
print("\n[7] 'sábado 30' (el 30 es viernes)")
p = "573000002007"
reset(p)
r = say(p, "reserva para 4 el sábado 30 a las 12:30")
check("7a pregunta en vez de elegir", has(r, "cae", "no sábado"), r[:200])

# ── 8. Nombre en la confirmación ───────────────────────────────────────────
print("\n[8] Nombre escrito en la confirmación")
p = "573000002008"
reset(p)
say(p, "reserva para 6 el sábado 31 a las 12:30 a nombre de Carla")
say(p, "no")
r = say(p, "Juan Melo")
check("8a actualiza el nombre", has(r, "Juan Melo", "Tengo estos datos"), r[:220])
r = say(p, "sí")
check("8b reserva a nombre de Juan Melo", "|Juan Melo" in res_row(p), res_row(p))

# ── 9. VIP por encima de la capacidad ──────────────────────────────────────
print("\n[9] VIP para 20 (capacidad 15)")
p = "573000002009"
reset(p)
r = say(p, "quiero el vip para 20 personas el sábado 31")
check("9a explica el máximo", has(r, "15"), r[:220])

# ── 10. Domingo en reserva normal ──────────────────────────────────────────
print("\n[10] Domingo en reserva normal")
p = "573000002010"
reset(p)
r = say(p, "reserva para 6 el domingo")
check("10a ofrece VIP", has(r, "solo tenemos espacio VIP"), r[:200])

print("\n" + "=" * 78)
print(f"RESULTADO: {len(OK)} ✅ / {len(FAIL)} ❌")
if FAIL:
    print("Fallaron: " + ", ".join(FAIL))
