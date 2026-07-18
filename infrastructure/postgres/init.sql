-- ============================================================
-- WhatsApp MVP — Schema inicial PostgreSQL
-- Este archivo es ejecutado por Docker al crear el container
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
-- USUARIOS
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,
    full_name   VARCHAR(255) NOT NULL,
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS roles (
    id    SERIAL PRIMARY KEY,
    name  VARCHAR(50) UNIQUE NOT NULL
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_id INT  REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ============================================================
-- CONFIGURACIÓN DE NEGOCIO
-- ============================================================
CREATE TABLE IF NOT EXISTS business_config (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name     VARCHAR(255) NOT NULL,
    business_type     VARCHAR(100),
    phone_number      VARCHAR(50),
    welcome_message   TEXT,
    off_hours_message TEXT,
    human_delay_seconds INT DEFAULT 300,
    ai_enabled        BOOLEAN DEFAULT TRUE,
    ai_model          VARCHAR(100) DEFAULT 'gpt-4o-mini',
    max_ai_tokens     INT DEFAULT 500,
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS bot_prompts (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_config_id UUID REFERENCES business_config(id),
    prompt_text        TEXT NOT NULL,
    is_active          BOOLEAN DEFAULT FALSE,
    version            INT NOT NULL DEFAULT 1,
    created_by         UUID REFERENCES users(id),
    created_at         TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS business_hours (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_config_id UUID REFERENCES business_config(id),
    day_of_week        SMALLINT NOT NULL,
    open_time          TIME NOT NULL,
    close_time         TIME NOT NULL,
    is_active          BOOLEAN DEFAULT TRUE
);

-- ============================================================
-- CONTACTOS Y CONVERSACIONES
-- ============================================================
CREATE TABLE IF NOT EXISTS contacts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone        VARCHAR(50) UNIQUE NOT NULL,
    display_name VARCHAR(255),
    is_blocked   BOOLEAN DEFAULT FALSE,
    tags         TEXT[],
    notes        TEXT,
    first_seen   TIMESTAMP DEFAULT NOW(),
    last_seen    TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS conversations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id      UUID REFERENCES contacts(id),
    status          VARCHAR(50) DEFAULT 'AUTO',
    last_message_at TIMESTAMP,
    unread_count    INT DEFAULT 0,
    assigned_to     UUID REFERENCES users(id),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS conversation_labels (
    conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
    label           VARCHAR(100) NOT NULL,
    PRIMARY KEY (conversation_id, label)
);

-- ============================================================
-- MENSAJES
-- ============================================================
CREATE TABLE IF NOT EXISTS messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID REFERENCES conversations(id),
    wa_message_id   VARCHAR(255) UNIQUE,
    direction       VARCHAR(10) NOT NULL,
    content         TEXT,
    message_type    VARCHAR(50) DEFAULT 'TEXT',
    status          VARCHAR(50) DEFAULT 'SENT',
    processed_by    VARCHAR(50),
    ai_tokens_used  INT DEFAULT 0,
    sent_at         TIMESTAMP DEFAULT NOW(),
    delivered_at    TIMESTAMP,
    read_at         TIMESTAMP
);

CREATE TABLE IF NOT EXISTS message_media (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id  UUID REFERENCES messages(id) ON DELETE CASCADE,
    media_type  VARCHAR(50),
    file_url    VARCHAR(500),
    file_name   VARCHAR(255),
    file_size   BIGINT,
    mime_type   VARCHAR(100)
);

-- ============================================================
-- MOTOR HÍBRIDO
-- ============================================================
CREATE TABLE IF NOT EXISTS faq_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question    TEXT NOT NULL,
    answer      TEXT NOT NULL,
    keywords    TEXT[],
    is_active   BOOLEAN DEFAULT TRUE,
    priority    INT DEFAULT 0,
    match_count INT DEFAULT 0,
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS keyword_rules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    keywords    TEXT[] NOT NULL,
    match_mode  VARCHAR(20) DEFAULT 'ANY',
    response    TEXT NOT NULL,
    is_active   BOOLEAN DEFAULT TRUE,
    priority    INT DEFAULT 0,
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS auto_reply_rules (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_type  VARCHAR(100) NOT NULL,
    trigger_value VARCHAR(255),
    response      TEXT NOT NULL,
    is_active     BOOLEAN DEFAULT TRUE,
    priority      INT DEFAULT 0
);

-- ============================================================
-- MULTIMEDIA, RATE LIMIT, MÉTRICAS, AUDITORÍA
-- ============================================================
CREATE TABLE IF NOT EXISTS media_catalog (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    media_type  VARCHAR(50) NOT NULL,
    file_url    VARCHAR(500) NOT NULL,
    file_size   BIGINT,
    mime_type   VARCHAR(100),
    tags        TEXT[],
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rate_limit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone         VARCHAR(50) NOT NULL,
    message_count INT DEFAULT 1,
    window_start  TIMESTAMP DEFAULT NOW(),
    is_blocked    BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS daily_metrics (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    metric_date           DATE UNIQUE NOT NULL,
    total_messages_in     INT DEFAULT 0,
    total_messages_out    INT DEFAULT 0,
    total_ai_calls        INT DEFAULT 0,
    total_ai_tokens       INT DEFAULT 0,
    total_faq_matches     INT DEFAULT 0,
    total_keyword_matches INT DEFAULT 0,
    total_human_takeovers INT DEFAULT 0,
    new_contacts          INT DEFAULT 0,
    active_conversations  INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id),
    action      VARCHAR(255) NOT NULL,
    entity_type VARCHAR(100),
    entity_id   VARCHAR(255),
    old_value   JSONB,
    new_value   JSONB,
    ip_address  VARCHAR(50),
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS whatsapp_session (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_name   VARCHAR(100) UNIQUE DEFAULT 'default',
    status         VARCHAR(50) DEFAULT 'DISCONNECTED',
    phone_number   VARCHAR(50),
    qr_code        TEXT,
    connected_at   TIMESTAMP,
    last_heartbeat TIMESTAMP,
    error_message  TEXT,
    updated_at     TIMESTAMP DEFAULT NOW()
);

-- ============================================================
-- RESERVACIONES
-- ============================================================
CREATE TABLE IF NOT EXISTS reservations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_name     VARCHAR(255) NOT NULL,
    phone_number      VARCHAR(50) NOT NULL,
    reservation_date  TIMESTAMP NOT NULL,
    amount            NUMERIC(10, 2),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    notes             TEXT,
    conversation_id   UUID REFERENCES conversations(id),
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);

-- ============================================================
-- ÍNDICES
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_conversations_contact   ON conversations(contact_id);
CREATE INDEX IF NOT EXISTS idx_conversations_status    ON conversations(status);
CREATE INDEX IF NOT EXISTS idx_conversations_last_msg  ON conversations(last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_conversation   ON messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_messages_sent_at        ON messages(sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_wa_id          ON messages(wa_message_id);
CREATE INDEX IF NOT EXISTS idx_rate_limit_phone        ON rate_limit_log(phone, window_start);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user         ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created      ON audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reservations_status     ON reservations(status);
CREATE INDEX IF NOT EXISTS idx_reservations_date       ON reservations(reservation_date DESC);

-- ============================================================
-- DATOS INICIALES
-- ============================================================
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
