-- ============================================================
-- La Montaña del Bendito Chicharrón — Datos del restaurante
-- Ejecutar: docker exec -i wamvp_postgres psql -U wamvp_user -d whatsapp_mvp < /dev/stdin < seed-restaurant.sql
-- O copiar al VPS y ejecutar con: psql ... -f seed-restaurant.sql
-- ============================================================

-- 1. Actualizar configuración del negocio
UPDATE business_config SET
    business_name = 'La Montaña del Bendito Chicharrón',
    welcome_message = E'👋 ¡Hola! Bienvenido(a) a La Montaña del Bendito Chicharrón. 🤎🐷\n\nSerá un gusto atenderte. Si necesitas información sobre horarios, reservas, celebraciones, medios de pago o cualquier otra consulta, escríbenos y con gusto te ayudaremos.\n\n¿En qué podemos ayudarte hoy?',
    off_hours_message = E'⏰ Estamos fuera de horario laboral. Te responderemos en cuanto abramos.\n\n📍 La Montaña del Bendito Chicharrón\n🕐 Sábados y domingos: 11:30 a.m. - agotar existencias\n🕐 Lunes festivos: 11:30 a.m. - agotar existencias',
    ai_enabled = FALSE
WHERE business_name = 'Mi Negocio';

-- 2. Limpiar FAQs existentes (las 3 semilla)
DELETE FROM faq_items;

-- 3. Insertar todas las FAQs del restaurante
INSERT INTO faq_items (question, answer, keywords, is_active, priority) VALUES

-- 1. Información del restaurante
(
    '¿Cuál es el nombre del restaurante?',
    E'🍽️ ¡Bienvenido a La Montaña del Bendito Chicharrón! ¿En qué podemos ayudarte?',
    ARRAY['restaurante', 'nombre', 'cómo se llama', 'cuál es el nombre', 'donde estan', 'qué es esto'],
    TRUE, 20
),

-- 2. Horario
(
    '¿Cuál es el horario de atención?',
    E'🕒 Nuestro horario es:\n\n• Sábados y domingos: desde las 11:30 a. m. hasta agotar existencias.\n• Lunes festivos: también atendemos desde las 11:30 a. m. hasta agotar existencias.',
    ARRAY['horario', 'abren', 'atienden', 'abiertos', 'qué días', 'cuándo abren', 'hora', 'horarios', 'a que hora', 'que horas'],
    TRUE, 19
),

-- 3. Reservas
(
    '¿Realizan reservas?',
    E'📅 Sí realizamos reservas.\n\nLos horarios disponibles para reservar son:\n\n• 11:30 a. m.\n• 12:00 p. m.\n• 12:30 p. m.\n• 1:00 p. m.\n\nDespués de ese horario también podemos colaborar según la disponibilidad, pero los platos deben solicitarse con anticipación.\n\n¿Deseas hacer la reserva ahora? Escribe *reservar* y te ayudo. 😊',
    ARRAY['reserva', 'reservar', 'reservación', 'reservacion', 'agendar', 'mesa', 'apartar mesa', 'apartar'],
    TRUE, 18
),

-- 4. Cómo reservar
(
    '¿Cómo puedo hacer una reserva?',
    E'🎉 Para registrar tu reserva por favor envíanos la siguiente información:\n\n• Nombre de quien reserva\n• Fecha de la reserva\n• Número de personas\n• Motivo de la celebración\n• Nombre del homenajeado\n• Hora de llegada\n\n¡Gracias por elegirnos y permitirnos ser parte de tus fechas especiales!\n\n¿Deseas hacer la reserva ahora? Escribe *reservar* y te ayudo. 😊',
    ARRAY['hacer reserva', 'quiero reservar', 'reservar mesa', 'cumpleaños', 'cumpleanos', 'celebración', 'celebracion'],
    TRUE, 17
),

-- 5. Valor de la decoración
(
    '¿Cuánto cuesta la decoración?',
    E'🎈 La decoración tiene un valor de $40.000 COP.',
    ARRAY['decoración', 'decoracion', 'decorar', 'cumpleaños', 'cumpleanos', 'sorpresa', 'decoración mesa'],
    TRUE, 16
),

-- 6. Confirmación de reserva
(
    '¿Cómo confirmo mi reserva?',
    E'✅ ¡Perfecto! Tu reserva queda confirmada.\n\nRecuerda que el tiempo máximo de espera es de 15 minutos. Después de ese tiempo la mesa quedará sujeta a la disponibilidad del restaurante.',
    ARRAY['reserva confirmada', 'confirmé', 'confirme', 'listo', 'confirmar', 'confirmación'],
    TRUE, 15
),

-- 7. Cancelaciones o reprogramaciones
(
    '¿Puedo cancelar o reprogramar mi reserva?',
    E'📅 Si no puedes asistir, por favor avísanos con anticipación.\n\nEl dinero de la reserva no se devuelve, pero se conserva para reprogramar tu reserva para otra fecha.',
    ARRAY['cancelar reserva', 'cancelar', 'no puedo asistir', 'cambiar fecha', 'reprogramar', 'no voy a ir'],
    TRUE, 14
),

-- 8. Métodos de pago
(
    '¿Cuáles son los métodos de pago?',
    E'💳 Recibimos los siguientes medios de pago:\n\n• Nequi\n• Efectivo\n• Transferencia bancaria\n• Pago por llave',
    ARRAY['cómo pagar', 'como pagar', 'medios de pago', 'tarjeta', 'efectivo', 'nequi', 'transferencia', 'pago', 'pagar'],
    TRUE, 13
),

-- 9. Domicilios
(
    '¿Hacen domicilios?',
    E'🚫 Actualmente no contamos con servicio de domicilio.',
    ARRAY['domicilio', 'domicilios', 'envíos', 'envios', 'rappi', 'llevan', 'delivery', 'pedir a domicilio'],
    TRUE, 12
),

-- 10. Parqueadero
(
    '¿Tienen parqueadero?',
    E'🚗 Contamos con parqueadero público para carros y motos.',
    ARRAY['parqueadero', 'estacionamiento', 'carro', 'moto', 'parking', 'donde parqueo'],
    TRUE, 11
),

-- 11. Mascotas
(
    '¿Puedo ir con mi mascota?',
    E'🐶 ¡Sí! Somos Pet Friendly.\n\nSolo te pedimos traer una bolsa para recoger los residuos de tu mascota.',
    ARRAY['mascotas', 'mascota', 'perro', 'gato', 'petfriendly', 'pet friendly', 'animales', 'puedo llevar mi perro'],
    TRUE, 10
),

-- 12. Disponibilidad de mesas
(
    '¿Cuántas mesas tienen disponibles?',
    E'🪑 Contamos con 112 mesas. La disponibilidad puede variar según la afluencia de clientes, por lo que recomendamos reservar con anticipación.',
    ARRAY['mesas', 'disponibilidad', 'lleno', 'cupos', 'cuántas mesas', 'hay espacio', 'lugares'],
    TRUE, 9
),

-- 13. Calidad de la carne
(
    '¿Qué calidad de carne tienen?',
    E'🐷 Nuestro cerdo es certificado, de pedigrí, y también ofrecemos churrasco calentano.',
    ARRAY['cerdo', 'carne', 'calidad', 'certificado', 'chicharrón', 'chicharron', 'churrasco', 'qué carne'],
    TRUE, 8
),

-- 14. WiFi
(
    '¿Tienen WiFi?',
    E'📶 Actualmente no contamos con servicio de WiFi.',
    ARRAY['wifi', 'wi-fi', 'internet', 'contraseña', 'senal', 'señal'],
    TRUE, 7
),

-- 15. Factura electrónica
(
    '¿Emiten factura electrónica?',
    E'🧾 Sí, emitimos factura electrónica.',
    ARRAY['factura', 'factura electrónica', 'facturacion', 'facturación', 'facturar'],
    TRUE, 6
),

-- 16. Música en vivo
(
    '¿Tienen música en vivo?',
    E'🎶 Ocasionalmente contamos con música en vivo los domingos.',
    ARRAY['música', 'musica', 'cantante', 'en vivo', 'show', 'fiesta', 'karaoke'],
    TRUE, 5
),

-- 17. Accesibilidad
(
    '¿Tienen accesibilidad para personas con discapacidad?',
    E'♿ Actualmente no contamos con adecuaciones especiales para personas con discapacidad.',
    ARRAY['silla de ruedas', 'discapacidad', 'accesibilidad', 'personas discapacitadas', 'movilidad reducida'],
    TRUE, 4
),

-- 18. Bienvenida (catch-all)
(
    'Mensaje de bienvenida',
    E'👋 ¡Hola! Bienvenido(a) a La Montaña del Bendito Chicharrón. 🤎🐷\n\nEstoy aquí para ayudarte con información sobre:\n\n🍽️ Horarios\n📅 Reservas\n💳 Medios de pago\n🚗 Parqueadero\n🐶 Mascotas\n📍 Información general\n\n¿En qué puedo ayudarte?',
    ARRAY['hola', 'buenas', 'buen día', 'buen dia', 'información', 'inicio', 'hey', 'qué tal', 'que tal', 'buenas tardes', 'buenos días', 'buenas noches'],
    TRUE, 1
),

-- 19. Catch-all: no entiende
(
    'Respuesta cuando no entiende la pregunta',
    E'🤖 Lo siento, no entendí tu consulta.\n\nPuedes preguntarme sobre:\n\n• Horarios\n• Reservas\n• Decoraciones\n• Medios de pago\n• Parqueadero\n• Mascotas\n• Facturación\n• Domicilios\n• Música en vivo\n\nEstaré encantado de ayudarte.',
    ARRAY['ayuda', 'no entiendo', 'qué dices', 'que dices', 'options', 'opciones'],
    TRUE, 0
);

-- 4. Actualizar horarios del negocio (sábado y domingo 11:30-15:00, lunes festivos 11:30-15:00)
DELETE FROM business_hours;

INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 1, '00:00', '00:00', FALSE FROM business_config LIMIT 1; -- Lunes (festivo)
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 2, '00:00', '00:00', FALSE FROM business_config LIMIT 1; -- Martes
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 3, '00:00', '00:00', FALSE FROM business_config LIMIT 1; -- Miércoles
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 4, '00:00', '00:00', FALSE FROM business_config LIMIT 1; -- Jueves
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 5, '00:00', '00:00', FALSE FROM business_config LIMIT 1; -- Viernes
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 6, '11:30', '15:00', TRUE FROM business_config LIMIT 1;  -- Sábado
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 0, '11:30', '15:00', TRUE FROM business_config LIMIT 1;  -- Domingo
