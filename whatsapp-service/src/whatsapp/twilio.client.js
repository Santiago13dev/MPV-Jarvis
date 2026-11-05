/**
 * WhatsApp Client — Twilio WhatsApp (Sandbox o número propio)
 *
 * Alternativa a Baileys: no hay QR ni dispositivo vinculado. Los mensajes
 * entrantes llegan por webhook HTTP de Twilio y los salientes se envían con
 * la API de Twilio (REST).
 *
 * Se activa con WA_PROVIDER=twilio y estas variables:
 *   TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_WHATSAPP_FROM (ej: whatsapp:+14155238886)
 */

const axios = require('axios');
const pino = require('pino');
const config = require('../config');

const logger = pino({ level: config.nodeEnv === 'production' ? 'warn' : 'debug' });

const API_BASE = 'https://api.twilio.com/2010-04-01';
const MAX_MEDIA_BYTES = 8 * 1024 * 1024; // mismo límite que Baileys (el backend acepta ~10 MB)

const isConfigured = () => Boolean(
  config.twilio.accountSid && config.twilio.authToken && config.twilio.from,
);

const getStatus = () => (isConfigured() ? 'CONNECTED' : 'DISCONNECTED');
const getQR = () => null;
const getQRInfo = () => ({ qr: null, generatedAt: null, expiresInMs: null });

/**
 * Normaliza un destino a formato Twilio: whatsapp:+573001234567
 * Acepta JIDs de Baileys (@s.whatsapp.net, @lid) y números sueltos.
 */
function toTwilioAddress(to) {
  if (!to) throw new Error('Destination is required');
  let value = String(to).trim();
  if (value.startsWith('whatsapp:')) value = value.slice('whatsapp:'.length);
  value = value.replace(/@s\.whatsapp\.net|@lid|@c\.us/g, '').replace(/[^\d+]/g, '');
  if (!value) throw new Error(`Invalid destination: ${to}`);
  if (!value.startsWith('+')) value = `+${value}`;
  return `whatsapp:${value}`;
}

/** Envía un mensaje (texto, media o ambos) por la API de Twilio. */
async function sendMessage({ to, body, mediaUrl }) {
  if (!isConfigured()) {
    throw new Error('Twilio no está configurado (TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_WHATSAPP_FROM)');
  }

  const params = new URLSearchParams();
  params.append('From', config.twilio.from);
  params.append('To', toTwilioAddress(to));
  if (body) params.append('Body', body);
  if (mediaUrl) params.append('MediaUrl', mediaUrl);

  const url = `${API_BASE}/Accounts/${config.twilio.accountSid}/Messages.json`;
  const { data } = await axios.post(url, params.toString(), {
    auth: { username: config.twilio.accountSid, password: config.twilio.authToken },
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    timeout: 20_000,
  });

  logger.info({ to: params.get('To'), sid: data?.sid }, '[Twilio] Message sent');
  return { key: { id: data?.sid || `twilio-${Date.now()}` } };
}

async function sendTextMessage(to, text) {
  return sendMessage({ to, body: text });
}

/** Twilio no tiene encuestas nativas: se envía la pregunta con las opciones numeradas. */
async function sendPollMessage(to, name, values) {
  const body = `${name}\n\n${values.map((v, i) => `${i + 1}. ${v}`).join('\n')}\n\nResponde con el número de tu opción.`;
  return sendTextMessage(to, body);
}

async function sendImageMessage(to, imageUrl, caption = '') {
  return sendMessage({ to, body: caption, mediaUrl: imageUrl });
}

async function sendDocumentMessage(to, docUrl) {
  return sendMessage({ to, mediaUrl: docUrl });
}

/** Twilio no soporta ubicación nativa: se envía un enlace de Google Maps. */
async function sendLocationMessage(to, latitude, longitude, name = '') {
  const label = name ? `${name}: ` : '';
  return sendTextMessage(to, `📍 ${label}https://maps.google.com/?q=${latitude},${longitude}`);
}

/** Descarga un adjunto entrante (las URLs de Twilio requieren Basic auth). */
async function downloadMedia(mediaUrl) {
  const { data, headers } = await axios.get(mediaUrl, {
    auth: { username: config.twilio.accountSid, password: config.twilio.authToken },
    responseType: 'arraybuffer',
    timeout: 20_000,
    maxContentLength: MAX_MEDIA_BYTES,
    maxBodyLength: MAX_MEDIA_BYTES,
  });
  const buffer = Buffer.from(data);
  if (!buffer.length || buffer.length > MAX_MEDIA_BYTES) {
    throw new Error(`Attachment size not allowed: ${buffer.length}`);
  }
  return { buffer, contentType: (headers['content-type'] || '').split(';')[0].trim().toLowerCase() };
}

// Sin sesión que reiniciar: se mantienen las firmas para que el backend no cambie.
const connect = async () => logger.info('[Twilio] Provider activo — no requiere QR ni sesión');
const disconnect = async () => {};
const resetSession = async () => {};

module.exports = {
  provider: 'twilio',
  connect,
  disconnect,
  resetSession,
  getStatus,
  getQR,
  getQRInfo,
  sendTextMessage,
  sendPollMessage,
  sendImageMessage,
  sendDocumentMessage,
  sendLocationMessage,
  downloadMedia,
  toTwilioAddress,
  isConfigured,
};
