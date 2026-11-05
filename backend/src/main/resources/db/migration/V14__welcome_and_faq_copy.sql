-- V14 — Textos de bienvenida y FAQs alineados con las reglas de la demo.
-- Solo actualiza filas que todavía tienen el texto genérico/semilla; no pisa respuestas
-- personalizadas del restaurante.

UPDATE business_config
SET business_name = 'La Montaña del Bendito Chicharrón',
    welcome_message = '¡Hola! 👋 Bienvenido a *La Montaña del Bendito Chicharrón* 🐷🔥' || E'\n\n' ||
                      'Estamos en Sibaté, Cundinamarca. Te comparto la ubicación 📍.' || E'\n\n' ||
                      '• Reserva normal: sábados y lunes festivos.' || E'\n' ||
                      '• Espacio VIP privado: sábados, domingos y lunes festivos.' || E'\n\n' ||
                      'La reserva normal no tiene costo si solo guardas el espacio. ' ||
                      'El paquete de celebración (postre + decoración para 1 homenajeado) vale $40.000.' || E'\n\n' ||
                      '¿Quieres reservar o ver el menú? Escribe *menú* y te envío el PDF.'
WHERE business_name = 'Mi Negocio'
   OR welcome_message LIKE '¡Hola! 👋 Bienvenido. Soy el asistente virtual.%';

UPDATE faq_items
SET answer = 'La decoración y el postre tienen un valor de $40.000 para 1 homenajeado. '
             || 'Cada homenajeado adicional suma $10.000.'
WHERE question IN ('¿Cuánto cuesta la decoración?', '¿Tienen decoración para cumpleaños?');

UPDATE faq_items
SET answer = 'Sí, aceptamos Llave al 314 261 7362 (Roberto). También Nequi, efectivo y transferencia.'
WHERE question IN ('¿Aceptan Llave?', '¿Qué medios de pago reciben?', '¿Cómo pago mi reserva?',
                   '¿Cuáles son los métodos de pago?')
  AND answer NOT LIKE '%314%';

UPDATE faq_items
SET answer = 'Sí, los domingos abrimos para el espacio VIP, desde las 11:30 AM hasta agotar existencias. '
             || 'Las reservas normales son sábados y lunes festivos.'
WHERE question = '¿Abren el domingo?';

UPDATE faq_items
SET answer = 'Atendemos sábados, domingos y lunes festivos desde las 11:30 AM hasta agotar existencias. '
             || 'Las reservas normales son sábados y lunes festivos; el espacio VIP también abre los domingos. 🕐'
WHERE question = '¿Cuál es el horario de atención?';
