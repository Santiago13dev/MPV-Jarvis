-- ============================================================
-- BACKUP DE FAQs — La Montaña del Bendito Chicharrón
-- Generado: 2026-07-29
-- Fuente: docker exec wamvp_postgres psql -U wamvp_user -d whatsapp_mvp
--
-- Para restaurar:
--   docker exec -i wamvp_postgres psql -U wamvp_user -d whatsapp_mvp < faq_backup.sql
-- ============================================================

DELETE FROM faq_items;

INSERT INTO faq_items (id, question, answer, keywords, is_active, priority) VALUES

-- 1. Horario (prioridad 90)
('2ed7e829-76ae-44ed-a7f6-1ae57cce788b',
 '¿Cuál es el horario de atención?',
 E'Nuestro horario de atención es:\n\n- Sábados y domingos: 11:30 a. m. hasta agotar existencias.\n- Lunes festivos: 11:30 a. m. hasta agotar existencias.',
 ARRAY['horario', 'hora', 'abren', 'cierran', 'atienden', 'dias'],
 TRUE, 90),

-- 2. Cómo reservar (prioridad 90)
('42e33ead-32af-416d-a0f9-7ebbb28013e7',
 '¿Cómo puedo reservar?',
 E'Contamos con los siguientes horarios para reservas:\n\n- 11:30 a. m.\n- 12:00 m.\n- 12:30 p. m.\n- 1:00 p. m.\n\nSi deseas reservar para un horario posterior, haremos lo posible por ayudarte según la disponibilidad del restaurante. En estos casos, algunos platos deberán solicitarse con anticipación.\n\n¿Deseas hacer la reserva ahora? Escribe *reservar* y te ayudo. 😊',
 ARRAY['reservar', 'reserva', 'mesa', 'cita', 'apartar'],
 TRUE, 90),

-- 3. Domicilios (prioridad 80)
('a1fd8d0a-94db-4c08-b792-cd7336c49a92',
 '¿Tienen servicio de domicilio?',
 E'No. Actualmente no contamos con servicio de domicilio.',
 ARRAY['domicilio', 'delivery', 'envio', 'a domicilio'],
 TRUE, 80),

-- 4. Medios de pago (prioridad 80)
('ddb36a49-7786-4207-b5c8-c3765b46b0b0',
 '¿Qué medios de pago reciben?',
 E'Recibimos:\n\n- Efectivo.\n- Nequi.\n- Transferencia bancaria.\n- Pago por Llave.',
 ARRAY['pago', 'pagar', 'efectivo', 'nequi', 'transferencia', 'tarjeta', 'llave'],
 TRUE, 80),

-- 5. Mascotas (prioridad 70)
('25be1ff2-b9e4-47a8-9920-e984f9271801',
 '¿Puedo llevar mi mascota?',
 E'¡Sí! Somos un restaurante pet friendly.\n\nSolo te pedimos traer una bolsa para recoger los residuos de tu mascota.',
 ARRAY['mascota', 'perro', 'gato', 'pet friendly', 'mascotas'],
 TRUE, 70),

-- 6. Parqueadero (prioridad 70)
('7ad402b3-4ec2-4fd6-a7a7-9972a30a25bd',
 '¿Tienen parqueadero?',
 E'Sí. Contamos con parqueadero público para carros y motos.',
 ARRAY['parqueadero', 'parking', 'carro', 'moto', 'estacionamiento'],
 TRUE, 70),

-- 7. Datos para reserva (prioridad 70)
('cc4f75c2-0f19-43cc-a11e-d7057ac751c6',
 '¿Qué información necesito dar para reservar?',
 E'RESERVA - BENDITO CHICHARRÓN\n\nPor favor envíanos la siguiente información:\n\n- Nombre de quien realiza la reserva.\n- Fecha de la reserva.\n- Número de personas.\n- Motivo de la celebración.\n- Nombre del homenajeado (si aplica).\n- Hora de llegada.\n\nGracias por elegirnos y permitirnos ser parte de tus fechas especiales.\n\n¿Deseas hacer la reserva ahora? Escribe *reservar* y te ayudo. 😊',
 ARRAY['datos reserva', 'formulario reserva', 'informacion reserva', 'que necesito para reservar'],
 TRUE, 70),

-- 8. Cancelar reserva (prioridad 70)
('bb3f76e7-50b5-4555-845e-bae3f0567f01',
 '¿Qué pasa si no puedo asistir a mi reserva?',
 E'Si no puedes asistir, por favor avísanos con anticipación.\n\nEl dinero de la reserva no es reembolsable, pero se conserva como saldo para reprogramar la reserva en una nueva fecha.',
 ARRAY['cancelar reserva', 'no puedo asistir', 'cambiar reserva', 'reembolso', 'reprogramar'],
 TRUE, 70),

-- 9. Disponibilidad de mesas (prioridad 65)
('c1f7c366-fb77-4164-9d13-0c46e47663b0',
 '¿Hay mesas disponibles?',
 E'El restaurante cuenta con 112 mesas.\n\nLa disponibilidad depende del momento de tu visita.',
 ARRAY['mesas disponibles', 'disponibilidad', 'cupo', 'hay mesa'],
 TRUE, 65),

-- 10. Tipo de carne (prioridad 60)
('b27a8613-c9e2-4ceb-889a-73efefc2e024',
 '¿Qué tipo de carne utilizan?',
 E'Trabajamos con cerdo certificado de excelente calidad y ofrecemos nuestro reconocido churrasco calentano.',
 ARRAY['carne', 'cerdo', 'chicharron', 'churrasco', 'calidad carne'],
 TRUE, 60),

-- 11. Confirmar reserva (prioridad 60)
('d3aafed9-832b-4147-a869-4a1373f06701',
 '¿Cómo sé que mi reserva quedó confirmada?',
 E'¡Perfecto! Tu reserva ha quedado confirmada.\n\nTe recordamos que el tiempo máximo de espera es de 15 minutos. Después de ese tiempo, la reserva estará sujeta a la disponibilidad de mesas.',
 ARRAY['confirmar reserva', 'confirmacion', 'reserva confirmada', 'tiempo de espera'],
 TRUE, 60),

-- 12. Decoración (prioridad 60)
('c4b7558d-c71b-422a-b442-c735d3d7749c',
 '¿Tienen decoración para cumpleaños o celebraciones?',
 E'Sí. Contamos con decoración para celebraciones.\n\nValor: $40.000 COP.',
 ARRAY['decoracion', 'cumpleaños', 'celebracion', 'cumple', 'globos'],
 TRUE, 60),

-- 13. Factura electrónica (prioridad 55)
('9b9b53bd-ad30-426f-a8ef-f7ed5e38a471',
 '¿Expiden factura electrónica?',
 E'Sí. Emitimos factura electrónica.',
 ARRAY['factura', 'facturacion electronica', 'factura electronica'],
 TRUE, 55),

-- 14. Accesibilidad (prioridad 55)
('006f7d77-64ee-4116-99dc-714a573d2b9f',
 '¿El restaurante tiene acceso para personas con discapacidad?',
 E'Actualmente no contamos con adecuaciones especiales para personas con discapacidad.',
 ARRAY['discapacidad', 'accesibilidad', 'silla de ruedas', 'acceso'],
 TRUE, 55),

-- 15. Música en vivo (prioridad 50)
('8a25fbba-d413-492a-8d4d-343f75ebb6b0',
 '¿Tienen música en vivo?',
 E'Sí, ocasionalmente ofrecemos música en vivo los domingos.',
 ARRAY['musica', 'musica en vivo', 'banda', 'orquesta'],
 TRUE, 50),

-- 16. WiFi (prioridad 50)
('61b862fb-c3d7-49e1-80ad-b9a70c3d7d98',
 '¿Tienen WiFi?',
 E'No contamos con servicio de WiFi.',
 ARRAY['wifi', 'internet', 'red'],
 TRUE, 50),

-- 17. Horario (catch-all bajo, prioridad 10)
('f91715b1-3b29-4c49-8767-2dbeda2cf0ac',
 '¿Cuál es el horario de atención?',
 E'Nuestro horario es de viernes a domingo de 11PM hasta agotar existencias. ¡Te esperamos! 🕐',
 ARRAY['horario', 'hora', 'atienden', 'abierto', 'cerrado', 'cuando', 'schedule'],
 TRUE, 10),

-- 18. Colombia (prioridad 0)
('d9c7b63d-4bec-41ad-891a-e6005c85d91f',
 'cuando juega colombia',
 E'sabado',
 ARRAY['colombia', 'partido', 'juega'],
 TRUE, 0),

-- 19. Promoción (prioridad 0)
('54caef83-2a99-467f-93c3-97858938d2ea',
 'promocion dia de hoy?',
 E'50% de descuento en el segundo plato',
 ARRAY['promocion', 'descuento'],
 TRUE, 0);

-- Resetear secuencia de IDs
SELECT setval('faq_items_id_seq', (SELECT MAX(id::text::uuid) FROM faq_items));
