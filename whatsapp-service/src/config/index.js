require('dotenv').config();

module.exports = {
  port: parseInt(process.env.PORT || '3001'),
  sessionName: process.env.SESSION_NAME || 'default',
  sessionsDir: process.env.SESSIONS_DIR || './sessions',

  // Backend Spring Boot webhook URL
  backendWebhookUrl: process.env.BACKEND_WEBHOOK_URL || 'http://localhost:8080/api/webhook/message',
  backendUrl: process.env.BACKEND_URL || 'http://localhost:8080',

  // Shared secret para validar webhooks entre servicios
  webhookSecret: process.env.WA_WEBHOOK_SECRET || 'devsecret',

  // Rate limiting interno
  maxReconnectAttempts: parseInt(process.env.MAX_RECONNECT_ATTEMPTS || '10'),
  reconnectBaseDelayMs: parseInt(process.env.RECONNECT_BASE_DELAY_MS || '3000'),

  // Admin phone for disconnect notifications
  adminPhone: process.env.ADMIN_PHONE || '',

  nodeEnv: process.env.NODE_ENV || 'development',
};
