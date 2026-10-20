#!/usr/bin/env python3
"""
Pruebas de dashboard/configuración del motor de reservas.

Cubre: API REST (listado, filtros, stats, disponibilidad), creación/confirmación/
cancelación desde dashboard, subida de comprobante multipart, bloqueos de día,
reglas de cupo y cambios de configuración (precio VIP / hora final) con restauración.

Uso: python3 scripts/test_dashboard_config.py
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_reservation_full as e2e  # noqa: E402

PNG = e2e.PNG_1PX
P1 = "573009995001"
P2 = "573009995002"
P3 = "573009995003"
P4 = "573009995004"
P5 = "573009995005"
P6, P7, P8, P9, P10, P11, P12 = (f"5730099951{i:02d}" for i in range(1, 8))
TEST_PHONES = [P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12]
DATE = "2026-11-14"       # sábado
HOLIDAY_DATE = "2026-11-02"  # lunes festivo
SUN = "2026-11-08"        # domingo
BLOCK_DATE = "2026-11-15"
VIP_DATE = "2026-11-21"   # sábado, día VIP libre


def upload_receipt(rid, token):
    boundary = "----E2E" + str(int(time.time() * 1000))
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="receipt.png"\r\n'
        f"Content-Type: image/png\r\n\r\n"
    ).encode() + __import__("base64").b64decode(PNG) + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{e2e.API}/reservations/{rid}/payment-receipt", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def api_idempotent(body, token, key):
    req = urllib.request.Request(f"{e2e.API}/reservations", data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Idempotency-Key", key)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def main():
    for p in TEST_PHONES:
        e2e.clean(p)

    token = e2e.login()
    original = e2e.api("GET", "/reservations/configuration", token=token)["data"]
    changed_settings = False
    created_blocks = []
    created_rules = []
    created_ids = []

    try:
        # ── 1. Lectura / filtros / stats ─────────────────────────────────
        listing = e2e.api("GET", "/reservations?size=5", token=token)
        e2e.check("D1. listado de reservas", listing.get("success") is True and len(listing["data"]) > 0, listing)

        stats = e2e.api("GET", "/reservations/stats", token=token)["data"]
        e2e.check("D2. stats del dashboard", "pendingPayments" in stats and "confirmedRevenueFromToday" in stats, stats)
        e2e.check("D2b. configuración expone lunes festivos",
                  original.get("openOnHolidayMondays") is not None, original)

        vip = e2e.api("GET", "/reservations?type=VIP&size=50", token=token)["data"]
        e2e.check("D3. filtro tipo VIP", all(r["reservationType"] == "VIP" for r in vip), vip[:2])
        e2e.check("D4. hay VIP en el listado", len(vip) > 0, "sin VIP")

        confirmed = e2e.api("GET", "/reservations?status=CONFIRMED&size=50", token=token)["data"]
        e2e.check("D5. filtro confirmadas", all(r["status"] == "CONFIRMED" for r in confirmed), confirmed[:2])

        search = e2e.api("GET", "/reservations?q=Valentina", token=token)["data"]
        e2e.check("D6. búsqueda por nombre", any("Valentina" in (r.get("customerName") or "") for r in search), search)

        slots = e2e.api("GET", f"/reservations/available-slots?date={DATE}&partySize=4", token=token)["data"]
        e2e.check("D7. disponibilidad por fecha", any(s.startswith("11:30") for s in slots), slots)

        day = e2e.api("GET", f"/reservations/day-availability?date={DATE}", token=token)["data"]
        e2e.check("D8. ocupación del día", len(day) > 0 and "normal" in day[0] and "vip" in day[0], day[:1])

        # ── 2. Crear / confirmar / cancelar desde dashboard ───────────────
        created = e2e.api("POST", "/reservations", {
            "customerName": "Dashboard Test", "phoneNumber": P1, "reservationType": "NORMAL",
            "date": DATE, "time": "12:00", "partySize": 4, "status": "PAYMENT_PENDING",
            "notes": "Creada por prueba dashboard",
        }, token)["data"]
        created_ids.append(created["id"])
        e2e.check("D9. crea desde dashboard", created["customerName"] == "Dashboard Test", created)
        e2e.check("D10. precio calculado", float(created["totalPrice"]) == 40000.0, created)
        e2e.check("D11. estado inicial", created["status"] == "PAYMENT_PENDING", created["status"])

        got = e2e.api("GET", f"/reservations/{created['id']}", token=token)["data"]
        e2e.check("D12. detalle por id", got["reservationNumber"] == created["reservationNumber"], got)

        conf = e2e.api("POST", f"/reservations/{created['id']}/confirm", token=token)["data"]
        e2e.check("D13. confirma pago", conf["status"] == "CONFIRMED", conf["status"])

        canc = e2e.api("POST", f"/reservations/{created['id']}/cancel", {"reason": "Prueba dashboard"}, token)["data"]
        e2e.check("D14. cancela", canc["status"] == "CANCELLED", canc["status"])

        # ── 3. Comprobante multipart + archivo ───────────────────────────
        r2 = e2e.api("POST", "/reservations", {
            "customerName": "Recibo Dashboard", "phoneNumber": P2, "reservationType": "NORMAL",
            "date": DATE, "time": "12:30", "partySize": 2, "status": "PAYMENT_PENDING",
        }, token)["data"]
        created_ids.append(r2["id"])
        up = upload_receipt(r2["id"], token)
        e2e.check("D15. sube comprobante multipart", up.get("success") is True and up["data"]["status"] == "PAYMENT_RECEIPT_SENT",
                  up)
        recs = e2e.api("GET", f"/reservations/{r2['id']}/receipts", token=token)["data"]
        e2e.check("D16. lista comprobantes", len(recs) == 1 and recs[0]["status"] == "RECEIVED", recs)

        req = urllib.request.Request(f"{e2e.API}/reservations/{r2['id']}/receipts/{recs[0]['id']}/file")
        req.add_header("Authorization", "Bearer " + token)
        with urllib.request.urlopen(req, timeout=30) as f:
            blob = f.read()
        e2e.check("D17. sirve la imagen del comprobante", blob.startswith(b"\x89PNG"), blob[:8])

        rej = e2e.api("POST", f"/reservations/{r2['id']}/reject-receipt", {"note": "Ilegible"}, token)["data"]
        e2e.check("D18. rechaza comprobante", rej["status"] == "PAYMENT_PENDING", rej["status"])

        up2 = upload_receipt(r2["id"], token)
        e2e.check("D19. re-sube comprobante", up2["data"]["status"] == "PAYMENT_RECEIPT_SENT", up2)
        conf2 = e2e.api("POST", f"/reservations/{r2['id']}/confirm", token=token)["data"]
        e2e.check("D20. confirma con comprobante", conf2["status"] == "CONFIRMED", conf2["status"])
        recs2 = e2e.api("GET", f"/reservations/{r2['id']}/receipts", token=token)["data"]
        e2e.check("D21. comprobante aceptado", any(x["status"] == "ACCEPTED" for x in recs2), recs2)

        # ── 4. Configuración: precio VIP ─────────────────────────────────
        old_vip_price = original["vipPrice"]
        e2e.api("PATCH", "/reservations/configuration", {"vipPrice": 120000}, token)
        changed_settings = True
        e2e.clean(P3)
        r = e2e.say(P3, "¿cuánto cuesta el VIP?", "Precio")
        e2e.check_contains("D22. precio VIP configurable", r, "$120.000")
        e2e.say(P3, "olvídalo", "Precio")
        e2e.api("PATCH", "/reservations/configuration", {"vipPrice": old_vip_price}, token)
        changed_settings = False
        after = e2e.api("GET", "/reservations/configuration", token=token)["data"]
        e2e.check("D23. restaura precio VIP", float(after["vipPrice"]) == float(old_vip_price), after["vipPrice"])

        # ── 4b. Configuración: lunes festivos ───────────────────────────
        old_holiday = original["openOnHolidayMondays"]
        e2e.api("PATCH", "/reservations/configuration", {"openOnHolidayMondays": False}, token)
        changed_settings = True
        closed_holiday = e2e.api("GET", f"/reservations/day-availability?date={HOLIDAY_DATE}", token=token)["data"]
        e2e.check("D23b. desactiva lunes festivos", closed_holiday == [], closed_holiday)
        e2e.api("PATCH", "/reservations/configuration", {"openOnHolidayMondays": old_holiday}, token)
        changed_settings = False
        restored_holiday = e2e.api("GET", "/reservations/configuration", token=token)["data"]
        e2e.check_eq("D23c. restaura lunes festivos", restored_holiday["openOnHolidayMondays"], old_holiday)

        # ── 5. Configuración: hora final ─────────────────────────────────
        old_end = original["reservationEndTime"]
        e2e.api("PATCH", "/reservations/configuration", {"reservationEndTime": "14:00:00"}, token)
        changed_settings = True
        e2e.clean(P4)
        r = e2e.say(P4, f"reserva para 2 personas el 14 de noviembre a las 2 de la tarde a nombre de Mario Peña", "Hora")
        e2e.check_contains("D24. acepta hora final configurada", r, "2:00")
        e2e.check_not_contains("D24b. sin FAQ de horario pegada", r, "nuestro horario es")
        e2e.check("D25. no crea sin confirmar", e2e.reservation(P4) is None, e2e.reservation(P4))
        e2e.say(P4, "olvídalo", "Hora")
        e2e.api("PATCH", "/reservations/configuration", {"reservationEndTime": old_end}, token)
        changed_settings = False
        after = e2e.api("GET", "/reservations/configuration", token=token)["data"]
        e2e.check("D26. restaura hora final", after["reservationEndTime"].startswith(str(old_end)[:5]), after["reservationEndTime"])

        # ── 6. Bloqueo de día ────────────────────────────────────────────
        block = e2e.api("POST", "/reservations/configuration/blocks",
                        {"date": BLOCK_DATE, "reason": "Prueba E2E"}, token)["data"]
        created_blocks.append(block["id"])
        e2e.clean(P4)
        r = e2e.say(P4, f"reserva para 2 personas el 15 de noviembre a las 12:00 a nombre de Bloqueo", "Bloqueo")
        e2e.check_contains("D27. bloqueo de día aplica", r, "no estamos recibiendo reservas")
        e2e.check("D28. no crea en día bloqueado", e2e.reservation(P4) is None, e2e.reservation(P4))
        e2e.api("DELETE", f"/reservations/configuration/blocks/{block['id']}", token=token)
        created_blocks.remove(block["id"])

        # ── 7. Regla de cupo ─────────────────────────────────────────────
        rule = e2e.api("POST", "/reservations/configuration/capacity-rules",
                       {"dayOfWeek": "SUNDAY", "slotTime": "11:30", "capacity": 1}, token)["data"]
        created_rules.append(rule["id"])
        e2e.clean(P5)
        r = e2e.say(P5, f"reserva para 2 personas el 8 de noviembre a las 11:30 a nombre de Cupo Bajo", "Cupo")
        e2e.check_contains("D29. regla de cupo aplica", r, "cupo")
        e2e.check("D30. no crea sin cupo", e2e.reservation(P5) is None, e2e.reservation(P5))
        e2e.api("DELETE", f"/reservations/configuration/capacity-rules/{rule['id']}", token=token)
        created_rules.remove(rule["id"])

        # ── 8. Edición de nombre y notas desde el dashboard ──────────────
        # P12 (no P1) para no borrar la reserva "Dashboard Test" que valida D31.
        e2e.clean(P12)
        rn = e2e.api("POST", "/reservations", {
            "customerName": "Nombre Viejo", "phoneNumber": P12, "reservationType": "NORMAL",
            "date": DATE, "time": "13:00", "partySize": 2, "status": "CONFIRMED",
            "notes": "nota vieja",
        }, token)["data"]
        created_ids.append(rn["id"])
        upd = e2e.api("PATCH", f"/reservations/{rn['id']}",
                      {"customerName": "Nombre Nuevo", "notes": "nota nueva"}, token)["data"]
        e2e.check_eq("D33. edita nombre", upd["customerName"], "Nombre Nuevo")
        e2e.check_eq("D34. edita notas", upd["notes"], "nota nueva")
        got2 = e2e.api("GET", f"/reservations/{rn['id']}", token=token)["data"]
        e2e.check("D35. persiste nombre/notas",
                  got2["customerName"] == "Nombre Nuevo" and got2["notes"] == "nota nueva", got2)

        # ── 9. Concurrencia VIP: solo 1 espacio por día ──────────────────
        vip_phones = [P6, P7, P8, P9, P10]
        for p in vip_phones:
            e2e.clean(p)
        before_vip = e2e.api("GET", f"/reservations/day-availability?date={VIP_DATE}", token=token)["data"]
        slot_before = next(x for x in before_vip if x["time"].startswith("12:00"))
        normal_before = slot_before["normal"]["reserved"]
        vip_before = slot_before["vip"]["reserved"]
        vip_results = []

        def vip_create(p, idx):
            try:
                body = {"customerName": f"VIP Conc {idx}", "phoneNumber": p, "reservationType": "VIP",
                        "date": VIP_DATE, "time": "12:00", "partySize": 2, "status": "CONFIRMED"}
                r = e2e.api("POST", "/reservations", body, token)
                vip_results.append(("ok", p, r))
            except urllib.error.HTTPError as ex:
                vip_results.append(("err", p, ex.read().decode()))

        threads = [__import__("threading").Thread(target=vip_create, args=(p, i))
                   for i, p in enumerate(vip_phones)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        vip_ok = [r for r in vip_results if r[0] == "ok"]
        vip_err = [r for r in vip_results if r[0] == "err"]
        e2e.check_eq("D36. solo 1 VIP en el día", len(vip_ok), 1)
        e2e.check_eq("D37. 4 VIP rechazados", len(vip_err), 4)
        after_vip = e2e.api("GET", f"/reservations/day-availability?date={VIP_DATE}", token=token)["data"]
        slot_after = next(x for x in after_vip if x["time"].startswith("12:00"))
        e2e.check_eq("D38. VIP no consume cupo normal", slot_after["normal"]["reserved"], normal_before)
        e2e.check_eq("D39. VIP reservado 1", slot_after["vip"]["reserved"], vip_before + 1)
        for p in vip_phones:
            e2e.clean(p)

        # ── 9. Idempotency-Key en la API ──────────────────────────────────
        e2e.clean(P11)
        idem_key = f"e2e-idem-{int(time.time() * 1000)}"
        idem_body = {"customerName": "Idem Test", "phoneNumber": P11, "reservationType": "NORMAL",
                     "date": VIP_DATE, "time": "13:00", "partySize": 2, "status": "CONFIRMED"}
        idem1 = api_idempotent(idem_body, token, idem_key)
        idem2 = api_idempotent(idem_body, token, idem_key)
        e2e.check("D40. idempotencia devuelve la misma reserva",
                  idem1["data"]["reservationNumber"] == idem2["data"]["reservationNumber"], (idem1, idem2))
        e2e.check("D41. avisa que ya existía", "existente" in idem2.get("message", "").lower(), idem2)
        found = e2e.api("GET", "/reservations?q=Idem%20Test", token=token)["data"]
        e2e.check("D42. solo una reserva idempotente",
                  len([r for r in found if r["reservationNumber"] == idem1["data"]["reservationNumber"]]) == 1, found)

        # ── 10. Búsqueda/filtros finales ─────────────────────────────────
        cancelled = e2e.api("GET", "/reservations?status=CANCELLED&q=Dashboard", token=token)["data"]
        e2e.check("D31. búsqueda + filtro combinados", any(r["reservationNumber"] == created["reservationNumber"]
                                                          for r in cancelled), cancelled)
        stats2 = e2e.api("GET", "/reservations/stats", token=token)["data"]
        e2e.check("D32. stats coherentes", all(k in stats2 for k in (
            "reservationsToday", "peopleToday", "vipToday", "confirmedRevenueFromToday",
            "pendingPayments", "remainingCapacityToday")), stats2)

    finally:
        # Restaurar todo pase lo que pase
        if changed_settings:
            try:
                e2e.api("PATCH", "/reservations/configuration", {
                    "vipPrice": original["vipPrice"],
                    "reservationEndTime": original["reservationEndTime"],
                }, token)
            except Exception as ex:
                print("⚠️ no se pudo restaurar settings:", ex)
        for bid in created_blocks:
            try:
                e2e.api("DELETE", f"/reservations/configuration/blocks/{bid}", token=token)
            except Exception:
                pass
        for rid in created_rules:
            try:
                e2e.api("DELETE", f"/reservations/configuration/capacity-rules/{rid}", token=token)
            except Exception:
                pass
        for p in TEST_PHONES:
            e2e.clean(p)

    total = len(e2e.RESULTS)
    passed = sum(1 for _, ok, _ in e2e.RESULTS if ok)
    print("\n" + "=" * 78)
    print(f"RESULTADO DASHBOARD/CONFIG: {passed}/{total} checks en verde")
    for label, ok, detail in e2e.RESULTS:
        if not ok:
            print(f" - {label}: {detail}")
    print("=" * 78)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
