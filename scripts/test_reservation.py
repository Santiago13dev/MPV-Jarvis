#!/usr/bin/env python3
"""Pruebas end-to-end del flujo de reservas contra el backend local.

Simula mensajes entrantes de WhatsApp vía webhook y lee las respuestas
que el backend persiste en PostgreSQL (en lugar de enviarlas a WhatsApp).
"""
import json
import subprocess
import sys
import time
import urllib.request

WEBHOOK = "http://localhost:8080/api/webhook/message"
SECRET = "devsecret"
PSQL = ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "wamvp_user", "-d", "whatsapp_mvp", "-tAc"]
SEP = "@@@MSG@@@"


def psql(sql):
    out = subprocess.run(PSQL + [sql], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    return out.stdout.strip()


def clean(phone):
    psql(f"""
    DELETE FROM messages WHERE conversation_id IN (
      SELECT c.id FROM conversations c JOIN contacts k ON k.id=c.contact_id WHERE k.phone='{phone}');
    DELETE FROM reservations WHERE phone_number='{phone}';
    DELETE FROM conversations WHERE contact_id IN (SELECT id FROM contacts WHERE phone='{phone}');
    DELETE FROM contacts WHERE phone='{phone}';""")


def outbound_messages(phone):
    raw = psql(f"""
    SELECT coalesce(string_agg(m.content, E'{SEP}' ORDER BY m.sent_at), '')
    FROM messages m
    JOIN conversations c ON c.id=m.conversation_id
    JOIN contacts k ON k.id=c.contact_id
    WHERE k.phone='{phone}' AND m.direction='OUTBOUND'""")
    return raw.split(SEP) if raw else []


def send(phone, content, display_name="Cliente Prueba", jid=None):
    jid = jid or f"{phone}@s.whatsapp.net"
    before = len(outbound_messages(phone))
    body = json.dumps({
        "waMessageId": f"test-{phone}-{int(time.time()*1000)}-{before}",
        "phone": phone,
        "remoteJid": jid,
        "displayName": display_name,
        "content": content,
        "messageType": "TEXT",
    }).encode()
    req = urllib.request.Request(WEBHOOK, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "X-Webhook-Secret": SECRET,
    })
    urllib.request.urlopen(req, timeout=15).read()

    deadline = time.time() + 30
    while time.time() < deadline:
        msgs = outbound_messages(phone)
        if len(msgs) > before:
            return msgs[before:]
        time.sleep(0.4)
    return ["(SIN RESPUESTA - timeout)"]


def state(phone):
    return psql(f"""
    SELECT coalesce(c.pending_action,'')||' | '||coalesce(c.pending_action_data,'')
    FROM conversations c JOIN contacts k ON k.id=c.contact_id
    WHERE k.phone='{phone}'""")


def reservations(phone):
    return psql(f"""
    SELECT customer_name||' | '||reservation_date||' | personas='||coalesce(people_count::text,'?')||
           ' | '||status||' | '||coalesce(notes,'')
    FROM reservations WHERE phone_number='{phone}' ORDER BY created_at""") or "(sin reservas)"


def say(phone, msg, name="Cliente Prueba"):
    print(f"\n👤 {name}: {msg}")
    replies = send(phone, msg, name)
    for r in replies:
        print(f"🤖 Bot: {r}")
    print(f"   [estado] {state(phone)}")
    return replies


def title(t):
    print("\n" + "=" * 72)
    print(t)
    print("=" * 72)


def run_happy_path():
    phone = "573001110001"
    title("CASO A — Reserva completa (camino feliz)")
    clean(phone)
    say(phone, "Hola, quiero reservar una mesa", "Carlos Pérez")
    say(phone, "Para el sábado, somos 6 personas, a las 12:30, es el cumpleaños de mi mamá", "Carlos Pérez")
    say(phone, "SI", "Carlos Pérez")
    print("\n📊 RESERVA EN BD:\n" + reservations(phone))
    print(f"[estado final] {state(phone)}")


def run_interruption():
    phone = "573001110002"
    title("CASO B — Preguntas durante la reserva (no perder contexto)")
    clean(phone)
    say(phone, "quiero reservar", "Ana Gómez")
    say(phone, "somos 4 personas", "Ana Gómez")
    say(phone, "¿puedo llevar a mi perro?", "Ana Gómez")
    say(phone, "¿tienen wifi?", "Ana Gómez")
    say(phone, "Para el sábado a la 1 de la tarde", "Ana Gómez")
    say(phone, "SI", "Ana Gómez")
    print("\n📊 RESERVA EN BD:\n" + reservations(phone))


def run_modification_and_cancel():
    phone = "573001110003"
    title("CASO C — Modificar y cancelar reserva existente")
    clean(phone)
    say(phone, "quiero reservar para el sábado, somos 4 personas a las 12:00", "Luis Rojas")
    say(phone, "SI", "Luis Rojas")
    print("\n📊 RESERVA INICIAL:\n" + reservations(phone))
    say(phone, "puedo llevar 2 más", "Luis Rojas")
    print("\n📊 TRAS 'llevar 2 más':\n" + reservations(phone))
    say(phone, "cancelar mi reserva", "Luis Rojas")
    say(phone, "SI", "Luis Rojas")
    print("\n📊 TRAS CANCELAR:\n" + reservations(phone))


def run_edge_cases():
    phone = "573001110004"
    title("CASO D — Validaciones y casos borde")
    clean(phone)
    say(phone, "quiero reservar para el viernes, somos 2 personas a las 3 de la tarde", "Pedro")
    say(phone, "quiero reservar el sábado para 2 personas a las 3 pm", "Pedro")
    say(phone, "cambiar a 8 personas", "Pedro")
    print("\n📊 RESERVA EN BD:\n" + reservations(phone))


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("all", "a"):
        run_happy_path()
    if which in ("all", "b"):
        run_interruption()
    if which in ("all", "c"):
        run_modification_and_cancel()
    if which in ("all", "d"):
        run_edge_cases()
    print("\n\n✅ Pruebas finalizadas")
