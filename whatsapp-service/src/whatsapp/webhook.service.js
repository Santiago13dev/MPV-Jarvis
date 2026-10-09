/**
 * Webhook Service
 * Responsabilidad: comunicar eventos del WhatsApp Service → Spring Boot Backend
 */

const axios = require('axios');
const { downloadMediaMessage } = require('@whiskeysockets/baileys');
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

// ── Comprobantes de pago (imagen / PDF) ───────────────────────────────────────
// Solo se descargan y reenvían imágenes y PDFs (posibles comprobantes). El backend los guarda en disco
// y los asocia a la reserva pendiente de pago del cliente.
const MAX_MEDIA_BYTES = 8 * 1024 * 1024; // el backend acepta hasta 10 MB; base64 añade ~33 %
const ALLOWED_MEDIA_MIME = new Set([
  'image/jpeg',
  'image/jpg',
  'image/png',
  'image/webp',
  'application/pdf',
]);

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
    return {
      text: messageContent.imageMessage.caption || '',
      type: 'IMAGE',
      mimeType: messageContent.imageMessage.mimetype,
      fileLength: Number(messageContent.imageMessage.fileLength || 0),
    };
  }
  if (messageContent.videoMessage) {
    return { text: messageContent.videoMessage.caption || '', type: 'VIDEO' };
  }
  if (messageContent.audioMessage) {
    return { text: null, type: 'AUDIO' };
  }
  if (messageContent.documentMessage) {
    return {
      text: messageContent.documentMessage.caption || '',
      type: 'DOCUMENT',
      mimeType: messageContent.documentMessage.mimetype,
      fileLength: Number(messageContent.documentMessage.fileLength || 0),
    };
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
 * Descarga el adjunto si es una imagen/PDF razonable. Devuelve { mediaBase64, mediaMimeType } o {}.
 * Cualquier fallo es no crítico: el mensaje se reenvía igual y el backend lo deriva a un asesor.
 */
async function downloadReceiptMedia(msg, sock, { type, mimeType, fileLength }) {
  if (type !== 'IMAGE' && type !== 'DOCUMENT') return {};

  const mime = (mimeType || '').split(';')[0].trim().toLowerCase();
  if (!ALLOWED_MEDIA_MIME.has(mime)) {
    logger.debug({ mime }, '[Webhook] Attachment type not eligible as receipt — skipping download');
    return {};
  }
  if (fileLength && fileLength > MAX_MEDIA_BYTES) {
    logger.warn({ fileLength }, '[Webhook] Attachment too large — skipping download');
    return {};
  }

  try {
    const buffer = await downloadMediaMessage(
      msg,
      'buffer',
      {},
      {
        logger,
        reuploadRequest: sock ? sock.updateMediaMessage : undefined,
      },
    );
    if (!buffer || buffer.length === 0 || buffer.length > MAX_MEDIA_BYTES) {
      logger.warn({ size: buffer ? buffer.length : 0 }, '[Webhook] Downloaded attachment empty or too large');
      return {};
    }
    return { mediaBase64: buffer.toString('base64'), mediaMimeType: mime === 'image/jpg' ? 'image/jpeg' : mime };
  } catch (err) {
    logger.warn({ err: err.message, msgId: msg.key.id }, '[Webhook] Could not download attachment');
    return {};
  }
}

/**
 * Reenvía mensaje entrante de WhatsApp → Spring Boot
 * @param msg  mensaje de Baileys
 * @param sock socket de Baileys (necesario para descargar adjuntos)
 */
async function forwardIncomingMessage(msg, sock) {
  const extracted = extractMessageContent(msg);
  const { text, type, location } = extracted;

  const remoteJid = msg.key.remoteJid;
  const phone = remoteJid
    .replace('@s.whatsapp.net', '')
    .replace('@c.us', '')
    .replace('@lid', '');

  const media = await downloadReceiptMedia(msg, sock, extracted);

  const payload = {
    waMessageId: msg.key.id,
    phone,
    remoteJid,
    displayName: msg.pushName || null,
    content: text,
    messageType: type,
    location: location || null,
    timestamp: msg.messageTimestamp
      ? new Date(Number(msg.messageTimestamp) * 1000).toISOString()
      : new Date().toISOString(),
    ...media,
  };

  // No loguear el adjunto (base64 enorme y dato sensible)
  const { mediaBase64, ...loggable } = payload;
  logger.debug({ payload: loggable, hasMedia: !!mediaBase64 }, '[Webhook] Forwarding incoming message to backend');

  const response = await httpClient.post('/api/webhook/message', payload, {
    // Con adjunto el POST puede ser de varios MB: más tiempo y sin el límite por defecto de 10 MB
    timeout: mediaBase64 ? 30_000 : 10_000,
    maxBodyLength: Infinity,
    maxContentLength: Infinity,
  });
  logger.info({ status: response.status, phone, hasMedia: !!mediaBase64 }, '[Webhook] Message forwarded successfully');
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
