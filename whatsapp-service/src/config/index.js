require('dotenv').config();

module.exports = {
  port: parseInt(process.env.PORT || '3001'),
  sessionName: process.env.SESSION_NAME || 'default',
  sessionsDir: process.env.SESSIONS_DIR || './sessions',

  // Proveedor de WhatsApp: "baileys" (QR) o "twilio" (Cloud, sin QR)
  provider: (process.env.WA_PROVIDER || 'baileys').toLowerCase(),

  twilio: {
    accountSid: process.env.TWILIO_ACCOUNT_SID || '',
    authToken: process.env.TWILIO_AUTH_TOKEN || '',
    // Número de Twilio que aparece como remitente, ej: whatsapp:+14155238886
    from: process.env.TWILIO_WHATSAPP_FROM || '',
    // URL pública del webhook (necesaria si se valida la firma detrás de un túnel)
    publicUrl: process.env.TWILIO_PUBLIC_URL || '',
    validateSignature: (process.env.TWILIO_VALIDATE_SIGNATURE || 'false').toLowerCase() === 'true',
  },

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
