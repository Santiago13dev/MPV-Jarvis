/**
 * Webhook Service
 * Responsabilidad: comunicar eventos del WhatsApp Service → Spring Boot Backend
 */

const axios = require('axios');
const config = require('../config');
const pino = require('pino');

const logger = pino({ level: config.nodeEnv === 'production' ? 'info' : 'debug' });

// Cliente HTTP con timeout y headers por defecto
const httpClient = axios.create({
  baseURL: config.backendUrl,
  timeout: 10_000,
  headers: {
    'Content-Type': 'application/json',
    'X-Webhook-Secret': config.webhookSecret,
  },
});

/**
 * Extrae el contenido de texto de un mensaje de Baileys
 * Maneja los distintos tipos de mensaje
 */
function extractMessageContent(msg) {
  const messageContent = msg.message;
  if (!messageContent) return { text: null, type: 'UNKNOWN' };

  if (messageContent.conversation) {
    return { text: messageContent.conversation, type: 'TEXT' };
  }
  if (messageContent.extendedTextMessage?.text) {
    return { text: messageContent.extendedTextMessage.text, type: 'TEXT' };
  }
  if (messageContent.imageMessage) {
    return { text: messageContent.imageMessage.caption || '', type: 'IMAGE' };
  }
  if (messageContent.videoMessage) {
    return { text: messageContent.videoMessage.caption || '', type: 'VIDEO' };
  }
  if (messageContent.audioMessage) {
    return { text: null, type: 'AUDIO' };
  }
  if (messageContent.documentMessage) {
    return { text: messageContent.documentMessage.caption || '', type: 'DOCUMENT' };
  }
  if (messageContent.locationMessage) {
    return { text: null, type: 'LOCATION', location: messageContent.locationMessage };
  }
  if (messageContent.stickerMessage) {
    return { text: null, type: 'STICKER' };
  }

  return { text: null, type: 'UNKNOWN' };
}

/**
 * Reenvía mensaje entrante de WhatsApp → Spring Boot
 */
async function forwardIncomingMessage(msg) {
  const { text, type, location } = extractMessageContent(msg);

  const phone = msg.key.remoteJid.replace('@s.whatsapp.net', '').replace('@c.us', '');

  const payload = {
    waMessageId: msg.key.id,
    phone,
    displayName: msg.pushName || null,
    content: text,
    messageType: type,
    location: location || null,
    timestamp: msg.messageTimestamp
      ? new Date(Number(msg.messageTimestamp) * 1000).toISOString()
      : new Date().toISOString(),
  };

  logger.debug({ payload }, '[Webhook] Forwarding incoming message to backend');

  const response = await httpClient.post('/api/webhook/message', payload);
  logger.info({ status: response.status, phone }, '[Webhook] Message forwarded successfully');
}

/**
 * Notifica cambio de estado de sesión al backend
 */
async function notifyStatusChange(statusPayload) {
  const response = await httpClient.post('/api/webhook/session-status', statusPayload);
  logger.info({ status: response.status }, '[Webhook] Session status notified');
}

/**
 * Notifica actualización de estado de un mensaje (sent/delivered/read)
 */
async function notifyMessageStatusUpdate(update) {
  try {
    await httpClient.post('/api/webhook/message-status', update);
  } catch (err) {
    // No crítico, ignorar
    logger.warn({ err, update }, '[Webhook] Failed to notify message status update');
  }
}

module.exports = {
  forwardIncomingMessage,
  notifyStatusChange,
  notifyMessageStatusUpdate,
};
