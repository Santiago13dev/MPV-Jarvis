#!/usr/bin/env python3
"""
Carga un set completo de FAQs oficiales en el backend local.

- Idempotente: busca por `question`; crea las que faltan.
- Con --update actualiza las existentes (respuesta, keywords y prioridad).
- No elimina ninguna FAQ.
- Solo usa información oficial del sistema prompt / operación del restaurante.

Uso:
    python3 scripts/seed_faqs_full.py
    python3 scripts/seed_faqs_full.py --update
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE_DEFAULT = "http://localhost:8080/api"


# (question, answer, keywords, priority)
FAQS = [
    # ── Horario ──────────────────────────────────────────────────────────────
    (
        "¿Cuál es el horario de atención?",
        "Atendemos sábados y domingos desde las 11:30 AM hasta agotar existencias, "
        "y también los lunes festivos. Las reservas se manejan por este chat. 🕐",
        ["horario", "horario de atencion", "a que hora", "abren", "cierran",
         "atienden", "abierto", "cerrado", "que dia", "dias"],
        90,
    ),
    (
        "¿Abren los lunes?",
        "Sí, los lunes festivos atendemos desde las 11:30 AM hasta agotar existencias. "
        "Los lunes que no son festivos permanecemos cerrados.",
        ["lunes", "lunes festivos", "festivo", "festivos"],
        92,
    ),
    (
        "¿Abren entre semana?",
        "Solo atendemos sábados, domingos y lunes festivos. Entre semana no abrimos.",
        ["entre semana", "martes", "miercoles", "jueves", "viernes"],
        91,
    ),
    (
        "¿A qué hora cierran?",
        "Atendemos hasta agotar existencias, así que no tenemos una hora fija de cierre. "
        "Te recomendamos llegar desde las 11:30 AM.",
        ["a que hora cierran", "cierran", "hora de cierre", "hasta que hora"],
        91,
    ),
    (
        "¿Abren el sábado?",
        "Sí, los sábados atendemos desde las 11:30 AM hasta agotar existencias.",
        ["sabado", "sabados"],
        90,
    ),
    (
        "¿Abren el domingo?",
        "Sí, los domingos atendemos desde las 11:30 AM hasta agotar existencias.",
        ["domingo", "domingos"],
        90,
    ),
    # ── Reservas ─────────────────────────────────────────────────────────────
    (
        "¿Cómo puedo reservar?",
        "Escríbeme por aquí con la fecha, la hora y el número de personas. "
        "El sistema te muestra la disponibilidad y el valor, y te guía paso a paso. 😊",
        ["como puedo reservar", "como reservo", "hacer una reserva", "quiero reservar", "reservar"],
        90,
    ),
    (
        "¿Puedo reservar por WhatsApp?",
        "Sí, las reservas se hacen por este mismo chat de WhatsApp.",
        ["reservar por whatsapp", "reserva por whatsapp", "whatsapp"],
        88,
    ),
    (
        "¿Qué datos necesito para reservar?",
        "Necesito tu nombre, la fecha, la hora y el número de personas. "
        "Si es una celebración, puedes contarme el motivo y el nombre del homenajeado. 🎉",
        ["datos para reservar", "que necesito para reservar", "informacion para reservar",
         "datos de la reserva"],
        85,
    ),
    (
        "¿Puedo reservar para hoy?",
        "Sí, si hay disponibilidad. Dime la hora y el número de personas y el sistema te confirma.",
        ["reservar para hoy", "reserva para hoy"],
        84,
    ),
    (
        "¿Puedo reservar para mañana?",
        "Sí, si hay disponibilidad. Dime la hora y el número de personas y el sistema te confirma.",
        ["reservar para manana", "reserva para manana"],
        84,
    ),
    (
        "¿Puedo reservar para un día festivo?",
        "Sí, los lunes festivos atendemos. Dime la fecha, la hora y las personas y revisamos disponibilidad.",
        ["reservar para un dia festivo", "reserva para festivo", "festivo", "festivos"],
        84,
    ),
    (
        "¿Puedo reservar para un grupo?",
        "Sí. Dime cuántas personas son, la fecha y la hora. "
        "Para grupos de más de 40 personas te conectamos con un asesor.",
        ["reservar para un grupo", "reserva de grupo", "grupo", "grupos"],
        83,
    ),
    (
        "¿Cuántas personas máximo pueden reservar?",
        "El máximo por reserva es de 40 personas. Para grupos más grandes, te conectamos con un asesor.",
        ["maximo de personas", "cuantas personas maximo", "maximo 40", "40 personas"],
        85,
    ),
    (
        "¿Puedo reservar para más de 40 personas?",
        "Para grupos de más de 40 personas te conectamos con un asesor del restaurante.",
        ["mas de 40", "mas de cuarenta", "grupo grande", "grupos grandes"],
        86,
    ),
    (
        "¿Hay reservas VIP?",
        "Sí, contamos con un espacio VIP privado (solo uno por día). Dime la fecha, la hora "
        "y el número de personas y el sistema te confirma disponibilidad y valor.",
        ["reserva vip", "reservas vip", "vip"],
        85,
    ),
    (
        "¿Qué incluye el espacio VIP?",
        "El VIP es un espacio privado y exclusivo: solo hay un espacio disponible por día. "
        "Si ya está ocupado, un asesor te ayuda a revisar opciones. El sistema te confirma "
        "la capacidad y el valor al hacer la reserva.",
        ["espacio vip", "que incluye el vip", "vip privado", "vip", "reserva vip"],
        85,
    ),
    (
        "¿Puedo cambiar mi reserva?",
        "Sí. Escríbeme qué quieres cambiar (fecha, hora, número de personas o nombre) "
        "y lo gestionamos según disponibilidad.",
        ["cambiar mi reserva", "cambiar la reserva", "modificar mi reserva",
         "modificar la reserva", "cambio de reserva"],
        80,
    ),
    (
        "¿Puedo cancelar mi reserva?",
        "Sí. Avísame por aquí con anticipación. Ten en cuenta que no hay devolución en dinero; "
        "el valor queda disponible para otra fecha.",
        ["cancelar mi reserva", "cancelar la reserva", "cancelacion de reserva", "cancelar"],
        80,
    ),
    (
        "¿Cuál es la política de cancelación?",
        "Si cancelas, no hay devolución en dinero: el valor queda disponible para otra fecha. "
        "Te recomendamos avisarnos con anticipación. 📅",
        ["politica de cancelacion", "cancelacion", "cancelar", "devolucion",
         "devolver el dinero", "reprogramar"],
        85,
    ),
    (
        "¿Me devuelven el dinero si cancelo?",
        "No hay devolución en dinero. El valor de la reserva queda como saldo para una nueva fecha.",
        ["devolucion", "devolver el dinero", "reembolso", "devolucion de dinero"],
        80,
    ),
    (
        "¿Qué pasa si llego tarde?",
        "La reserva se mantiene por un máximo de 15 minutos. "
        "Después de ese tiempo queda sujeta a disponibilidad.",
        ["llego tarde", "llegar tarde", "retraso", "demora"],
        80,
    ),
    (
        "¿Cuánto tiempo guardan la mesa?",
        "La reserva se mantiene por 15 minutos; después queda sujeta a disponibilidad. ⏳",
        ["cuanto tiempo guardan", "tiempo de espera", "espera", "15 minutos"],
        78,
    ),
    (
        "¿Cómo sé que mi reserva quedó confirmada?",
        "Cuando la reserva quede confirmada, el sistema te envía el número de reserva por este chat. "
        "Si no la ves, escríbeme y la revisamos.",
        ["mi reserva quedo confirmada", "confirmacion de reserva", "numero de reserva",
         "reserva confirmada"],
        78,
    ),
    (
        "¿Necesito pagar para reservar?",
        "Al crear la reserva, el sistema te muestra el valor y el paso a paso. "
        "Si se requiere comprobante, te lo indica por aquí.",
        ["pagar para reservar", "pago para reservar", "necesito pagar", "pago de la reserva"],
        76,
    ),
    (
        "¿Cómo pago mi reserva?",
        "Puedes pagar con Nequi, efectivo, transferencia o Llave. "
        "El sistema te indica el paso a paso al reservar.",
        ["como pago mi reserva", "pagar mi reserva", "pago de reserva", "pagar la reserva"],
        76,
    ),
    (
        "¿Puedo enviar el comprobante por WhatsApp?",
        "Sí, envíalo por aquí como imagen o PDF y el sistema lo registra para revisión.",
        ["enviar comprobante", "comprobante", "recibo de pago", "soporte de pago"],
        76,
    ),
    # ── Pagos y factura ──────────────────────────────────────────────────────
    (
        "¿Qué medios de pago reciben?",
        "Recibimos Nequi, efectivo, transferencia y Llave. 💳",
        ["medios de pago", "formas de pago", "metodos de pago", "pago", "pagar"],
        80,
    ),
    (
        "¿Aceptan Nequi?",
        "Sí, aceptamos Nequi.",
        ["nequi"],
        82,
    ),
    (
        "¿Aceptan efectivo?",
        "Sí, aceptamos efectivo.",
        ["efectivo"],
        82,
    ),
    (
        "¿Aceptan transferencia?",
        "Sí, aceptamos transferencia bancaria.",
        ["transferencia", "transferencias"],
        82,
    ),
    (
        "¿Aceptan Llave?",
        "Sí, aceptamos pago por Llave.",
        ["llave"],
        82,
    ),
    (
        "¿Aceptan tarjeta?",
        "No manejamos tarjeta. Los medios disponibles son Nequi, efectivo, transferencia y Llave.",
        ["tarjeta", "tarjetas", "datafono", "datáfono"],
        85,
    ),
    (
        "¿Expiden factura electrónica?",
        "Sí, emitimos factura electrónica.",
        ["factura", "factura electronica", "facturacion", "electronica"],
        55,
    ),
    (
        "¿Cómo pido la factura?",
        "Pídela al momento de pagar o escríbenos por aquí y te ayudamos con la factura electrónica.",
        ["pedir factura", "solicitar factura", "factura de la compra", "como pido la factura"],
        56,
    ),
    # ── Menú y comida ────────────────────────────────────────────────────────
    (
        "¿Qué tipo de comida venden?",
        "Somos un restaurante colombiano de chicharrón y comida tradicional. 🐷",
        ["tipo de comida", "que comida venden", "comida colombiana", "comida tipica",
         "comida tradicional"],
        60,
    ),
    (
        "¿Tienen chicharrón?",
        "Sí, el chicharrón es nuestra especialidad.",
        ["chicharron", "chicharrones"],
        60,
    ),
    (
        "¿Cómo es el chicharrón?",
        "Es crocante por fuera y jugoso por dentro, preparado con cerdo certificado.",
        ["como es el chicharron", "chicharron crocante", "chicharron jugoso"],
        60,
    ),
    (
        "¿De qué es el chicharrón?",
        "Nuestro chicharrón es de cerdo certificado.",
        ["de que es el chicharron", "chicharron de cerdo", "cerdo"],
        60,
    ),
    (
        "¿Tienen churrasco?",
        "Sí, ofrecemos nuestro reconocido churrasco calentano.",
        ["churrasco", "churrasco calentano"],
        60,
    ),
    (
        "¿Tienen comida tradicional?",
        "Sí, tenemos comida tradicional colombiana, además de nuestro chicharrón.",
        ["comida tradicional", "comida tipica", "platos tipicos"],
        58,
    ),
    (
        "¿El cerdo es certificado?",
        "Sí, trabajamos con cerdo certificado de excelente calidad.",
        ["cerdo certificado", "carne certificada", "calidad del cerdo"],
        60,
    ),
    (
        "¿Tienen menú?",
        "¡Claro! Te comparto el menú en PDF por este chat.",
        ["menu", "carta", "platos", "que tienen", "que sirven"],
        60,
    ),
    # ── Servicios e instalaciones ────────────────────────────────────────────
    (
        "¿Tienen parqueadero?",
        "Sí, contamos con parqueadero público para carros y motos.",
        ["parqueadero", "parking", "estacionamiento", "carro", "carros"],
        70,
    ),
    (
        "¿Hay parqueadero para motos?",
        "Sí, el parqueadero público también es para motos.",
        ["moto", "motos"],
        72,
    ),
    (
        "¿Tienen WiFi?",
        "No, no contamos con servicio de WiFi.",
        ["wifi", "internet", "red"],
        50,
    ),
    (
        "¿Tienen baños?",
        "Sí, contamos con baños para nuestros clientes.",
        ["banos", "bano", "servicios sanitarios"],
        55,
    ),
    (
        "¿Hay acceso para personas con discapacidad?",
        "Actualmente no contamos con adecuaciones especiales para personas con discapacidad.",
        ["discapacidad", "accesibilidad", "silla de ruedas", "acceso para discapacitados"],
        55,
    ),
    (
        "¿Puedo llevar mi mascota?",
        "¡Sí! Somos pet friendly. Solo te pedimos traer una bolsa para recoger los residuos "
        "de tu mascota. 🐶",
        ["mascota", "mascotas", "perro", "perros", "gato", "gatos", "pet friendly"],
        70,
    ),
    (
        "¿Debo llevar bolsa para mi mascota?",
        "Sí, por favor trae una bolsa para recoger los residuos de tu mascota.",
        ["bolsa para la mascota", "bolsa para mi mascota", "residuos de la mascota"],
        70,
    ),
    (
        "¿Tienen música en vivo?",
        "Sí, ocasionalmente tenemos música en vivo los domingos. 🎶",
        ["musica", "musica en vivo", "banda", "orquesta", "en vivo"],
        50,
    ),
    (
        "¿Qué días hay música en vivo?",
        "La música en vivo es ocasional y suele ser los domingos.",
        ["que dias hay musica", "musica los domingos", "domingos musica"],
        52,
    ),
    (
        "¿Cuántas mesas tienen?",
        "Tenemos capacidad para 112 mesas.",
        ["cuantas mesas", "numero de mesas", "112 mesas", "aforo"],
        65,
    ),
    # ── Celebraciones ────────────────────────────────────────────────────────
    (
        "¿Tienen decoración para cumpleaños?",
        "Sí, contamos con decoración para celebraciones. Tiene un valor de $40.000 COP.",
        ["decoracion", "decoraciones", "cumpleanos", "celebracion", "globos"],
        60,
    ),
    (
        "¿Cuánto cuesta la decoración?",
        "La decoración tiene un valor de $40.000 COP.",
        ["cuanto cuesta la decoracion", "precio de la decoracion", "valor de la decoracion",
         "decoracion"],
        62,
    ),
    (
        "¿Puedo celebrar un cumpleaños?",
        "Sí. Cuéntame la fecha, la hora y el número de personas, y si quieres agregar "
        "decoración te la cotizamos.",
        ["celebrar un cumpleanos", "cumpleanos", "celebracion"],
        60,
    ),
    (
        "¿Puedo reservar para un aniversario?",
        "Sí. Dime la fecha, la hora y el número de personas y lo gestionamos por aquí.",
        ["aniversario", "celebrar aniversario"],
        60,
    ),
    (
        "¿Se puede reservar para una fecha especial?",
        "Sí. Escríbeme la fecha, la hora y el número de personas y el sistema revisa disponibilidad.",
        ["fecha especial", "ocasion especial", "fecha especial reserva"],
        75,
    ),
    (
        "¿Se puede reservar para Navidad?",
        "Escríbeme la fecha y el número de personas; el sistema revisa si hay disponibilidad para esa fecha.",
        ["reservar para navidad", "reserva para navidad", "navidad"],
        74,
    ),
    (
        "¿Se puede reservar para Año Nuevo?",
        "Escríbeme la fecha y el número de personas; el sistema revisa si hay disponibilidad para esa fecha.",
        ["reservar para ano nuevo", "reserva para ano nuevo", "ano nuevo"],
        74,
    ),
    (
        "¿Se puede reservar para el Día de la Madre?",
        "Escríbeme la fecha, la hora y las personas; revisamos disponibilidad para el Día de la Madre.",
        ["reservar para el dia de la madre", "dia de la madre", "reservar dia de la madre"],
        74,
    ),
    (
        "¿Se puede reservar para una empresa?",
        "Para reservas de empresa, cuéntame cuántas personas son. Si son más de 40, te conectamos con un asesor.",
        ["reserva de empresa", "reservar para una empresa", "empresa"],
        73,
    ),
    # ── Domicilios y otros ───────────────────────────────────────────────────
    (
        "¿Tienen servicio de domicilio?",
        "No, actualmente no contamos con servicio de domicilio.",
        ["domicilio", "domicilios", "delivery", "envio", "a domicilio", "entregas"],
        80,
    ),
    (
        "¿Hacen entregas a domicilio?",
        "No manejamos entregas a domicilio. La atención es en el restaurante.",
        ["entregas a domicilio", "envio a domicilio", "pedido a domicilio"],
        82,
    ),
    (
        "¿Cómo se llama el restaurante?",
        "Somos La Montaña del Bendito Chicharrón, un restaurante colombiano de chicharrón "
        "y comida tradicional.",
        ["nombre del restaurante", "como se llama el restaurante"],
        55,
    ),
    (
        "¿Qué los hace especiales?",
        "Nuestro chicharrón es crocante por fuera y jugoso por dentro, con cerdo certificado. "
        "También tenemos churrasco calentano y comida tradicional.",
        ["que los hace especiales", "especialidad", "por que elegirlos"],
        50,
    ),
    (
        "¿Cómo puedo hacer un pedido?",
        "No manejamos domicilios. Puedes visitarnos en el restaurante o escribirnos por WhatsApp "
        "para reservar.",
        ["hacer un pedido", "pedido", "pedir", "ordenar", "orden"],
        60,
    ),
]


def load_env(path: Path) -> dict:
    values = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def api(base, method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", BASE_DEFAULT))
    parser.add_argument("--update", action="store_true",
                        help="actualizar respuesta/keywords/prioridad de las FAQs ya existentes")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    if not base.endswith("/api"):
        base += "/api"
    env = load_env(ROOT / ".env")
    email = os.environ.get("ADMIN_EMAIL", env.get("ADMIN_EMAIL", "admin@example.com"))
    password = os.environ.get("ADMIN_PASSWORD", env.get("ADMIN_PASSWORD", "admin123"))

    print(f"Backend: {base}")
    login = api(base, "POST", "/auth/login", {"email": email, "password": password})
    token = login["data"]["accessToken"]

    existing = api(base, "GET", "/faqs", token=token)["data"]
    by_question = {item["question"].strip().lower(): item for item in existing}
    print(f"FAQs actuales: {len(existing)}")

    created = updated = skipped = failed = 0
    for question, answer, keywords, priority in FAQS:
        payload = {
            "question": question,
            "answer": answer,
            "keywords": keywords,
            "priority": priority,
        }
        current = by_question.get(question.strip().lower())
        try:
            if current:
                if args.update:
                    api(base, "PUT", f"/faqs/{current['id']}", payload, token=token)
                    updated += 1
                else:
                    skipped += 1
            else:
                api(base, "POST", "/faqs", payload, token=token)
                created += 1
        except urllib.error.HTTPError as exc:
            failed += 1
            detail = exc.read().decode("utf-8", errors="replace")[:300]
            print(f"  ERROR {question}: HTTP {exc.code} {detail}")
        except Exception as exc:  # noqa: BLE001
            failed += 1
            print(f"  ERROR {question}: {exc}")

    final = api(base, "GET", "/faqs", token=token)["data"]
    active = [item for item in final if item.get("isActive", True)]
    print(f"Resultado: {created} creadas, {updated} actualizadas, {skipped} omitidas, {failed} fallidas")
    print(f"FAQs finales: {len(final)} (activas: {len(active)})")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
