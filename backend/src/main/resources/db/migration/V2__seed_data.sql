-- V2: Seed data inicial
INSERT INTO roles (name) VALUES ('ADMIN'), ('OPERATOR') ON CONFLICT DO NOTHING;

INSERT INTO whatsapp_session (session_name, status)
VALUES ('default', 'DISCONNECTED') ON CONFLICT DO NOTHING;

INSERT INTO business_config (
    business_name, welcome_message, off_hours_message, human_delay_seconds, ai_enabled
) VALUES (
    'Mi Negocio',
    '¡Hola! 👋 Bienvenido. Soy el asistente virtual. ¿En qué puedo ayudarte?',
    '⏰ Estamos fuera de horario laboral. Te responderemos en cuanto abramos. Horario: Lun-Vie 8am-6pm.',
    300,
    TRUE
) ON CONFLICT DO NOTHING;

-- Horario laboral por defecto: Lunes a Viernes 8am-6pm
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 1, '08:00', '18:00', TRUE FROM business_config LIMIT 1;
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 2, '08:00', '18:00', TRUE FROM business_config LIMIT 1;
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 3, '08:00', '18:00', TRUE FROM business_config LIMIT 1;
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 4, '08:00', '18:00', TRUE FROM business_config LIMIT 1;
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 5, '08:00', '18:00', TRUE FROM business_config LIMIT 1;
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 6, '09:00', '13:00', TRUE FROM business_config LIMIT 1;
INSERT INTO business_hours (business_config_id, day_of_week, open_time, close_time, is_active)
SELECT id, 0, '00:00', '00:00', FALSE FROM business_config LIMIT 1;

-- FAQ de ejemplo
INSERT INTO faq_items (question, answer, keywords, is_active, priority)
VALUES
(
    '¿Cuál es el horario de atención?',
    'Nuestro horario es de lunes a viernes de 8am a 6pm y sábados de 9am a 1pm. ¡Te esperamos! 🕐',
    ARRAY['horario', 'hora', 'atienden', 'abierto', 'cerrado', 'cuando', 'schedule'],
    TRUE, 10
),
(
    '¿Cómo puedo hacer un pedido?',
    'Para hacer tu pedido puedes escribirnos aquí mismo por WhatsApp o visitarnos en nuestra tienda. ¡Con gusto te atendemos! 😊',
    ARRAY['pedido', 'orden', 'comprar', 'ordenar', 'pedir', 'order'],
    TRUE, 9
),
(
    '¿Cuáles son los métodos de pago?',
    'Aceptamos efectivo, transferencia bancaria y tarjetas de crédito/débito. 💳',
    ARRAY['pago', 'pagar', 'efectivo', 'transferencia', 'tarjeta', 'payment', 'precio'],
    TRUE, 8
);
