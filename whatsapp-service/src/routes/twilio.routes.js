/**
 * Twilio Routes — webhook de mensajes entrantes de WhatsApp (sin QR)
 *
 * Twilio envía un POST application/x-www-form-urlencoded con:
 *   From=whatsapp:+57..., WaId=57..., ProfileName, Body, MessageSid, NumMedia, MediaUrl0...
 *
 * Se responde 200 con TwiML vacío (sin respuesta automática propia: el bot contesta
 * por la API REST desde el backend).
 */

const express = require('express');
const crypto = require('crypto');
const pino = require('pino');
const router = express.Router();
const config = require('../config');
const twilioClient = require('../whatsapp/twilio.client');
const webhookService = require('../whatsapp/webhook.service');

const logger = pino({ level: config.nodeEnv === 'production' ? 'info' : 'debug' });

const ALLOWED_MEDIA = new Set(['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'application/pdf']);

/** Valida X-Twilio-Signature (opcional: requiere TWILIO_PUBLIC_URL exacta al configurada en Twilio). */
function isValidSignature(req) {
  if (!config.twilio.validateSignature) return true;
  const signature = req.headers['x-twilio-signature'];
  if (!signature || !config.twilio.publicUrl) {
    logger.warn('[Twilio] Firma no validable (falta header o TWILIO_PUBLIC_URL)');
    return false;
  }
  const params = Object.keys(req.body || {}).sort();
  const data = config.twilio.publicUrl + params.map((key) => key + req.body[key]).join('');
  const expected = crypto
    .createHmac('sha1', config.twilio.authToken)
    .update(Buffer.from(data, 'utf8'))
    .digest('base64');
  try {
    return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(String(signature)));
  } catch (_) {
    return false;
  }
}

/** Extrae el número del remitente en dígitos (ej: 573001234567). */
function senderPhone(body) {
  const raw = body.WaId || body.From || '';
  return String(raw).replace('whatsapp:', '').replace(/\D/g, '');
}

/** Descarga el primer adjunto válido (comprobante de pago) si existe. */
async function firstAllowedMedia(body) {
  const count = parseInt(body.NumMedia || '0', 10);
  for (let i = 0; i < count; i++) {
    const url = body[`MediaUrl${i}`];
    const mime = String(body[`MediaContentType${i}`] || '').toLowerCase();
    if (!url || !ALLOWED_MEDIA.has(mime)) continue;
    try {
      const { buffer, contentType } = await twilioClient.downloadMedia(url);
      return { mediaBase64: buffer.toString('base64'), mediaMimeType: contentType || mime };
    } catch (err) {
      logger.warn({ err: err.message, i }, '[Twilio] No se pudo descargar el adjunto');
    }
  }
  return {};
}

router.post('/webhook/twilio', async (req, res) => {
  if (!isValidSignature(req)) {
    logger.warn('[Twilio] Firma inválida — se descarta el mensaje');
    return res.status(403).type('text/xml').send('<Response></Response>');
  }

  const body = req.body || {};

  // Callbacks de estado (sent/delivered/read): no se procesan como mensajes
  if (body.MessageStatus || body.SmsStatus) {
    logger.debug({ status: body.MessageStatus || body.SmsStatus }, '[Twilio] Status callback');
    return res.status(204).end();
  }

  // Responder rápido a Twilio y procesar en segundo plano
  res.status(200).type('text/xml').send('<Response></Response>');

  try {
    const phone = senderPhone(body);
    if (!phone) {
      logger.warn('[Twilio] Mensaje sin remitente válido');
      return;
    }

    const media = await firstAllowedMedia(body);
    const mime = String(body.MediaContentType0 || '').toLowerCase();
    const hasMedia = Boolean(media.mediaBase64);
    const messageType = hasMedia ? (mime === 'application/pdf' ? 'DOCUMENT' : 'IMAGE') : 'TEXT';

    const payload = {
      waMessageId: body.MessageSid || `twilio-${Date.now()}`,
      phone,
      // El backend responde a este JID; el cliente Twilio lo normaliza a whatsapp:+...
      remoteJid: `${phone}@s.whatsapp.net`,
      displayName: body.ProfileName || null,
      content: body.Body || '',
      messageType,
      timestamp: new Date().toISOString(),
      ...media,
    };

    logger.info({ phone, type: messageType, hasMedia }, '[Twilio] Mensaje entrante');
    await webhookService.forwardExternalMessage(payload);
  } catch (err) {
    logger.error({ err: err.message }, '[Twilio] Error procesando mensaje entrante');
  }
});

// GET de cortesía para verificar que la URL es alcanzable
router.get('/webhook/twilio', (_req, res) => {
  res.json({ provider: 'twilio', status: twilioClient.getStatus(), configured: twilioClient.isConfigured() });
});

module.exports = router;
