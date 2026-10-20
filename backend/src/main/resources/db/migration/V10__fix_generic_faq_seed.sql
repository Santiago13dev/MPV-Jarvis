-- Corrige las FAQs genéricas de la semilla V2 (horario/pagos/pedidos) que no corresponden
-- al restaurante. Es condicional: si producción ya tiene una respuesta propia, NO la toca.
-- También agrega datos oficiales que faltaban en la BD local (factura, cancelación, capacidad,
-- música y tiempo de espera), sin duplicar si ya existen.

UPDATE faq_items
SET answer = 'Atendemos sábados y domingos desde las 11:30 AM hasta agotar existencias, y también los lunes festivos. Las reservas son en horarios de 11:30 AM, 12:00 PM, 12:30 PM y 1:00 PM. 🕐',
    keywords = ARRAY['horario','horario de atencion','a que hora','abren','cierran','atienden','abierto','cerrado','cuando','schedule'],
    updated_at = NOW()
WHERE question = '¿Cuál es el horario de atención?'
  AND answer LIKE 'Nuestro horario es de lunes a viernes de 8am a 6pm%';

UPDATE faq_items
SET answer = 'Aceptamos Nequi, efectivo, transferencia y Llave. 💳',
    keywords = ARRAY['pago','pagar','efectivo','transferencia','tarjeta','nequi','llave','payment'],
    updated_at = NOW()
WHERE question = '¿Cuáles son los métodos de pago?'
  AND answer LIKE 'Aceptamos efectivo, transferencia bancaria y tarjetas%';

UPDATE faq_items
SET answer = 'Puedes escribirnos por WhatsApp o visitarnos en el restaurante. Para una reserva, dime fecha, hora y número de personas y la gestiono por aquí. 😊',
    keywords = ARRAY['pedido','orden','comprar','ordenar','pedir','order'],
    updated_at = NOW()
WHERE question = '¿Cómo puedo hacer un pedido?'
  AND answer LIKE 'Para hacer tu pedido puedes escribirnos aquí mismo por WhatsApp o visitarnos en nuestra tienda%';

INSERT INTO faq_items (question, answer, keywords, priority, is_active)
SELECT '¿Emiten factura electrónica?',
       'Sí, emitimos factura electrónica. Pídela al momento de pagar o escríbenos por WhatsApp. 🧾',
       ARRAY['factura','factura electronica','facturacion','electronica','rut'],
       9, TRUE
WHERE NOT EXISTS (SELECT 1 FROM faq_items WHERE question = '¿Emiten factura electrónica?');

INSERT INTO faq_items (question, answer, keywords, priority, is_active)
SELECT '¿Cuál es la política de cancelación?',
       'Si cancelas, no hay devolución en dinero: el valor queda disponible para otra fecha. Te recomendamos avisarnos con anticipación. 📅',
       ARRAY['politica de cancelacion','cancelacion','cancelar','devolucion','devolver el dinero'],
       9, TRUE
WHERE NOT EXISTS (SELECT 1 FROM faq_items WHERE question = '¿Cuál es la política de cancelación?');

INSERT INTO faq_items (question, answer, keywords, priority, is_active)
SELECT '¿Cuántas mesas tienen?',
       'Tenemos capacidad para 112 mesas. 😊',
       ARRAY['mesas','capacidad','aforo','cuantas mesas'],
       8, TRUE
WHERE NOT EXISTS (SELECT 1 FROM faq_items WHERE question = '¿Cuántas mesas tienen?');

INSERT INTO faq_items (question, answer, keywords, priority, is_active)
SELECT '¿Hay música en vivo?',
       'Ocasionalmente tenemos música en vivo los domingos. 🎶',
       ARRAY['musica','musica en vivo','en vivo','banda'],
       8, TRUE
WHERE NOT EXISTS (SELECT 1 FROM faq_items WHERE question = '¿Hay música en vivo?');

INSERT INTO faq_items (question, answer, keywords, priority, is_active)
SELECT '¿Cuánto tiempo puedo esperar?',
       'La reserva se mantiene por 15 minutos; después queda sujeta a disponibilidad. ⏳',
       ARRAY['tiempo de espera','espera','demora','15 minutos'],
       8, TRUE
WHERE NOT EXISTS (SELECT 1 FROM faq_items WHERE question = '¿Cuánto tiempo puedo esperar?');
