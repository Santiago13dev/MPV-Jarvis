# 🗄️ Diseño de Base de Datos — PostgreSQL

## Modelo Entidad-Relación

```
users ──────────────────────────────────────────────────────┐
  │                                                          │
  └── user_roles ── roles                                   │
                                                            │
business_config ────────────────────────────────────────────┤
  │                                                          │
  ├── business_hours (horarios por día)                     │
  └── bot_prompts (prompt activo del negocio)               │
                                                            │
contacts ───────────────────────────────────────────────────┤
  │                                                          │
  └── conversations ──────────────────────────────────────► │
        │                                                    │
        ├── messages                                         │
        │     └── message_media (archivos adjuntos)         │
        └── conversation_labels (etiquetas)                  │
                                                            │
faq_items ──────────────────────────────────────────────────┤
keyword_rules ──────────────────────────────────────────────┤
auto_reply_rules ───────────────────────────────────────────┤
media_catalog ──────────────────────────────────────────────┤
audit_logs ─────────────────────────────────────────────────┘
```

---

## 📋 Esquema SQL Completo

```sql
-- ============================================================
-- SCHEMA: whatsapp_mvp
-- ============================================================

CREATE SCHEMA IF NOT EXISTS whatsapp_mvp;
SET search_path TO whatsapp_mvp;

-- ------------------------------------------------------------
-- USUARIOS DEL DASHBOARD
-- ------------------------------------------------------------
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,         -- bcrypt hash
    full_name   VARCHAR(255) NOT NULL,
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE roles (
    id    SERIAL PRIMARY KEY,
    name  VARCHAR(50) UNIQUE NOT NULL          -- ADMIN, OPERATOR
);

CREATE TABLE user_roles (
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_id INT  REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ------------------------------------------------------------
-- CONFIGURACIÓN DEL NEGOCIO
-- ------------------------------------------------------------
CREATE TABLE business_config (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name    VARCHAR(255) NOT NULL,
    business_type    VARCHAR(100),              -- restaurante, tienda, etc.
    phone_number     VARCHAR(50),               -- número WA conectado
    welcome_message  TEXT,
    off_hours_message TEXT,
    human_delay_seconds INT DEFAULT 300,        -- delay antes de transferir a humano
    ai_enabled       BOOLEAN DEFAULT TRUE,
    ai_model         VARCHAR(100) DEFAULT 'gpt-4o-mini',
    max_ai_tokens    INT DEFAULT 500,
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

-- Prompt del bot (historial de versiones)
CREATE TABLE bot_prompts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_config_id UUID REFERENCES business_config(id),
    prompt_text      TEXT NOT NULL,             -- el system prompt completo
    is_active        BOOLEAN DEFAULT FALSE,
    version          INT NOT NULL DEFAULT 1,
    created_by       UUID REFERENCES users(id),
    created_at       TIMESTAMP DEFAULT NOW()
);

-- Horarios laborales
CREATE TABLE business_hours (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_config_id UUID REFERENCES business_config(id),
    day_of_week      SMALLINT NOT NULL,         -- 0=Dom, 1=Lun, ..., 6=Sab
    open_time        TIME NOT NULL,
    close_time       TIME NOT NULL,
    is_active        BOOLEAN DEFAULT TRUE
);

-- ------------------------------------------------------------
-- CONTACTOS (números de WhatsApp que escriben)
-- ------------------------------------------------------------
CREATE TABLE contacts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone        VARCHAR(50) UNIQUE NOT NULL,   -- ej: 573001234567@c.us
    display_name VARCHAR(255),
    is_blocked   BOOLEAN DEFAULT FALSE,
    tags         TEXT[],                        -- etiquetas del contacto
    notes        TEXT,
    first_seen   TIMESTAMP DEFAULT NOW(),
    last_seen    TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- CONVERSACIONES
-- ------------------------------------------------------------
CREATE TABLE conversations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id       UUID REFERENCES contacts(id),
    status           VARCHAR(50) DEFAULT 'AUTO',
    -- AUTO | HUMAN_TAKEOVER | CLOSED | WAITING
    last_message_at  TIMESTAMP,
    unread_count     INT DEFAULT 0,
    assigned_to      UUID REFERENCES users(id), -- operador asignado
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_conversations_contact ON conversations(contact_id);
CREATE INDEX idx_conversations_status  ON conversations(status);
CREATE INDEX idx_conversations_last_msg ON conversations(last_message_at DESC);

-- Etiquetas por conversación
CREATE TABLE conversation_labels (
    conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
    label           VARCHAR(100) NOT NULL,
    PRIMARY KEY (conversation_id, label)
);

-- ------------------------------------------------------------
-- MENSAJES
-- ------------------------------------------------------------
CREATE TABLE messages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID REFERENCES conversations(id),
    wa_message_id    VARCHAR(255) UNIQUE,       -- ID original de WhatsApp
    direction        VARCHAR(10) NOT NULL,      -- INBOUND | OUTBOUND
    content          TEXT,
    message_type     VARCHAR(50) DEFAULT 'TEXT',
    -- TEXT | IMAGE | VIDEO | AUDIO | DOCUMENT | LOCATION | STICKER
    status           VARCHAR(50) DEFAULT 'SENT',
    -- SENT | DELIVERED | READ | FAILED
    processed_by     VARCHAR(50),
    -- KEYWORD | FAQ | AI | HUMAN | SYSTEM
    ai_tokens_used   INT DEFAULT 0,
    sent_at          TIMESTAMP DEFAULT NOW(),
    delivered_at     TIMESTAMP,
    read_at          TIMESTAMP
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id);
CREATE INDEX idx_messages_sent_at      ON messages(sent_at DESC);
CREATE INDEX idx_messages_wa_id        ON messages(wa_message_id);

-- Archivos adjuntos de mensajes
CREATE TABLE message_media (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id  UUID REFERENCES messages(id) ON DELETE CASCADE,
    media_type  VARCHAR(50),                    -- IMAGE | VIDEO | AUDIO | DOCUMENT
    file_url    VARCHAR(500),
    file_name   VARCHAR(255),
    file_size   BIGINT,
    mime_type   VARCHAR(100)
);

-- ------------------------------------------------------------
-- MOTOR HÍBRIDO — FAQs
-- ------------------------------------------------------------
CREATE TABLE faq_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question     TEXT NOT NULL,
    answer       TEXT NOT NULL,
    keywords     TEXT[],                        -- palabras clave para matching
    is_active    BOOLEAN DEFAULT TRUE,
    priority     INT DEFAULT 0,
    match_count  INT DEFAULT 0,                 -- cuántas veces fue usado
    created_by   UUID REFERENCES users(id),
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- MOTOR HÍBRIDO — Reglas por Keywords
-- ------------------------------------------------------------
CREATE TABLE keyword_rules (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255) NOT NULL,
    keywords     TEXT[] NOT NULL,               -- palabras que activan la regla
    match_mode   VARCHAR(20) DEFAULT 'ANY',     -- ANY | ALL | EXACT
    response     TEXT NOT NULL,
    media_id     UUID,                          -- opcional: enviar archivo
    is_active    BOOLEAN DEFAULT TRUE,
    priority     INT DEFAULT 0,
    created_at   TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- MOTOR HÍBRIDO — Auto-Reply Rules (basadas en evento)
-- ------------------------------------------------------------
CREATE TABLE auto_reply_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_type    VARCHAR(100) NOT NULL,
    -- FIRST_MESSAGE | OFF_HOURS | KEYWORD | AFTER_X_MESSAGES
    trigger_value   VARCHAR(255),
    response        TEXT NOT NULL,
    is_active       BOOLEAN DEFAULT TRUE,
    priority        INT DEFAULT 0
);

-- ------------------------------------------------------------
-- CATÁLOGO DE MULTIMEDIA
-- ------------------------------------------------------------
CREATE TABLE media_catalog (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    media_type  VARCHAR(50) NOT NULL,           -- IMAGE | VIDEO | PDF | CATALOG
    file_url    VARCHAR(500) NOT NULL,
    file_size   BIGINT,
    mime_type   VARCHAR(100),
    tags        TEXT[],
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- RATE LIMITING (anti-spam)
-- ------------------------------------------------------------
CREATE TABLE rate_limit_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone        VARCHAR(50) NOT NULL,
    message_count INT DEFAULT 1,
    window_start TIMESTAMP DEFAULT NOW(),
    is_blocked   BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_rate_limit_phone ON rate_limit_log(phone, window_start);

-- ------------------------------------------------------------
-- MÉTRICAS / ESTADÍSTICAS (tabla de agregación diaria)
-- ------------------------------------------------------------
CREATE TABLE daily_metrics (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    metric_date          DATE UNIQUE NOT NULL,
    total_messages_in    INT DEFAULT 0,
    total_messages_out   INT DEFAULT 0,
    total_ai_calls       INT DEFAULT 0,
    total_ai_tokens      INT DEFAULT 0,
    total_faq_matches    INT DEFAULT 0,
    total_keyword_matches INT DEFAULT 0,
    total_human_takeovers INT DEFAULT 0,
    new_contacts         INT DEFAULT 0,
    active_conversations INT DEFAULT 0
);

-- ------------------------------------------------------------
-- AUDIT LOGS
-- ------------------------------------------------------------
CREATE TABLE audit_logs (
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

CREATE INDEX idx_audit_logs_user    ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_created ON audit_logs(created_at DESC);

-- ------------------------------------------------------------
-- SESIÓN WHATSAPP
-- ------------------------------------------------------------
CREATE TABLE whatsapp_session (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_name    VARCHAR(100) UNIQUE DEFAULT 'default',
    status          VARCHAR(50) DEFAULT 'DISCONNECTED',
    -- DISCONNECTED | CONNECTING | QR_READY | CONNECTED | ERROR
    phone_number    VARCHAR(50),
    qr_code         TEXT,                       -- base64 del QR actual
    connected_at    TIMESTAMP,
    last_heartbeat  TIMESTAMP,
    error_message   TEXT,
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- Insertar sesión inicial
INSERT INTO whatsapp_session (session_name, status) VALUES ('default', 'DISCONNECTED');

-- ------------------------------------------------------------
-- DATOS INICIALES
-- ------------------------------------------------------------
INSERT INTO roles (name) VALUES ('ADMIN'), ('OPERATOR');

INSERT INTO business_config (
    business_name, welcome_message, off_hours_message, human_delay_seconds
) VALUES (
    'Mi Negocio',
    '¡Hola! 👋 Bienvenido. Soy el asistente virtual. ¿En qué puedo ayudarte?',
    '⏰ Estamos fuera de horario. Te responderemos en cuanto abramos. Nuestro horario es Lun-Vie 8am-6pm.',
    300
);
```

---

## 📊 Índices y Optimizaciones

Los índices más críticos para performance:
- `messages(conversation_id)` — acceso al historial de una conversación
- `messages(sent_at DESC)` — mensajes recientes primero
- `conversations(last_message_at DESC)` — inbox ordenado por reciente
- `rate_limit_log(phone, window_start)` — anti-spam lookup rápido

## 📝 Notas de Diseño

1. **UUIDs como PKs**: Mejor para sistemas distribuidos y evita enumeration attacks.
2. **JSONB en audit_logs**: Flexibilidad para capturar cualquier cambio sin schema rígido.
3. **Arrays de PostgreSQL (`TEXT[]`)**: Para tags y keywords — evita tabla intermedia innecesaria en MVP.
4. **daily_metrics**: Tabla pre-agregada para que el dashboard cargue métricas rápido sin COUNT() costosos en tiempo real.
5. **whatsapp_session**: Una sola fila, updated frecuentemente. El dashboard lee de aquí para el indicador de estado.
