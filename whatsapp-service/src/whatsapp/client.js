/**
 * WhatsApp Client — Baileys Session Manager
 *
 * Responsabilidades:
 *  - Inicializar y mantener la sesión de WhatsApp
 *  - Gestionar el QR code
 *  - Reconexión automática con backoff exponencial
 *  - Emitir eventos al resto del sistema
 */

const {
  default: makeWASocket,
  DisconnectReason,
  useMultiFileAuthState,
  fetchLatestBaileysVersion,
  makeCacheableSignalKeyStore,
  isJidBroadcast,
} = require('@whiskeysockets/baileys');
const path = require('path');
const pino = require('pino');
const QRCode = require('qrcode');
const config = require('../config');
const webhookService = require('./webhook.service');

const logger = pino({ level: config.nodeEnv === 'production' ? 'info' : 'debug' });

// ── Estado interno del módulo ──────────────────────────────────────────────────
let sock = null;
let qrBase64 = null;
let sessionStatus = 'DISCONNECTED'; // DISCONNECTED | CONNECTING | QR_READY | CONNECTED | ERROR
let reconnectAttempts = 0;
let reconnectTimer = null;

// ── Getters públicos ────────────────────────────────────────────────────────────
const getStatus = () => sessionStatus;
const getQR = () => qrBase64;
const getSocket = () => sock;

/**
 * Actualiza el estado y notifica al backend via webhook
 */
async function setStatus(newStatus, extra = {}) {
  sessionStatus = newStatus;
  logger.info({ status: newStatus, ...extra }, '[WA] Status changed');
  try {
    await webhookService.notifyStatusChange({ status: newStatus, ...extra });
  } catch (err) {
    logger.warn({ err }, '[WA] Could not notify status change to backend');
  }
}

/**
 * Inicializa (o reinicia) la conexión de WhatsApp
 */
async function connect() {
  try {
    await setStatus('CONNECTING');
    qrBase64 = null;

    const sessionsPath = path.resolve(config.sessionsDir, config.sessionName);
    const { state, saveCreds } = await useMultiFileAuthState(sessionsPath);
    const { version } = await fetchLatestBaileysVersion();

    logger.info({ version }, '[WA] Baileys version');

    sock = makeWASocket({
      version,
      logger: pino({ level: 'silent' }), // silenciar logs internos de baileys
      printQRInTerminal: config.nodeEnv !== 'production',
      auth: {
        creds: state.creds,
        keys: makeCacheableSignalKeyStore(state.keys, pino({ level: 'silent' })),
      },
      // Configuraciones para reducir probabilidad de baneo
      defaultQueryTimeoutMs: 60_000,
      keepAliveIntervalMs: 30_000,
      retryRequestDelayMs: 2_000,
      // Ignorar mensajes de broadcast/estado
      shouldIgnoreJid: jid => isJidBroadcast(jid),
    });

    // ── Event Listeners ─────────────────────────────────────────────────────────

    sock.ev.on('creds.update', saveCreds);

    sock.ev.on('connection.update', async (update) => {
      const { connection, lastDisconnect, qr } = update;

      // QR code disponible
      if (qr) {
        try {
          qrBase64 = await QRCode.toDataURL(qr);
          await setStatus('QR_READY', { qrCode: qrBase64 });
          logger.info('[WA] QR code generated — waiting for scan');
        } catch (err) {
          logger.error({ err }, '[WA] Failed to generate QR base64');
        }
      }

      // Conexión establecida
      if (connection === 'open') {
        qrBase64 = null;
        reconnectAttempts = 0;
        const phoneNumber = sock.user?.id?.split(':')[0] || 'unknown';
        await setStatus('CONNECTED', { phoneNumber });
        logger.info({ phoneNumber }, '[WA] Connected successfully');
      }

      // Conexión cerrada
      if (connection === 'close') {
        const statusCode = lastDisconnect?.error?.output?.statusCode;
        const shouldReconnect = statusCode !== DisconnectReason.loggedOut;

        logger.warn({ statusCode, shouldReconnect }, '[WA] Connection closed');

        if (shouldReconnect) {
          scheduleReconnect();
        } else {
          // Usuario cerró sesión desde el teléfono — borrar credenciales
          await setStatus('DISCONNECTED', { reason: 'LOGGED_OUT' });
          logger.info('[WA] Session logged out — credentials cleared');
        }
      }
    });

    // Mensajes entrantes
    sock.ev.on('messages.upsert', async ({ messages, type }) => {
      if (type !== 'notify') return;

      for (const msg of messages) {
        // Ignorar mensajes propios y de estado
        if (msg.key.fromMe) continue;
        if (msg.key.remoteJid === 'status@broadcast') continue;

        try {
          await webhookService.forwardIncomingMessage(msg);
        } catch (err) {
          logger.error({ err, msgId: msg.key.id }, '[WA] Failed to forward message to backend');
        }
      }
    });

    // Actualizaciones de estado de mensajes (sent/delivered/read)
    sock.ev.on('messages.update', async (updates) => {
      for (const update of updates) {
        if (update.update.status) {
          try {
            await webhookService.notifyMessageStatusUpdate({
              waMessageId: update.key.id,
              status: update.update.status,
            });
          } catch (err) {
            logger.warn({ err }, '[WA] Failed to forward status update');
          }
        }
      }
    });

  } catch (err) {
    logger.error({ err }, '[WA] Fatal error during connect()');
    await setStatus('ERROR', { error: err.message });
    scheduleReconnect();
  }
}

/**
 * Reconexión con backoff exponencial
 * Delay = min(base * 2^intentos, 60 segundos)
 */
function scheduleReconnect() {
  if (reconnectAttempts >= config.maxReconnectAttempts) {
    logger.error('[WA] Max reconnect attempts reached — giving up');
    setStatus('ERROR', { error: 'Max reconnect attempts reached' });
    return;
  }

  const delay = Math.min(
    config.reconnectBaseDelayMs * Math.pow(2, reconnectAttempts),
    60_000
  );
  reconnectAttempts++;

  logger.info({ attempt: reconnectAttempts, delayMs: delay }, '[WA] Scheduling reconnect');
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(connect, delay);
}

/**
 * Desconectar manualmente (logout)
 */
async function disconnect() {
  try {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    if (sock) {
      await sock.logout();
      sock = null;
    }
    await setStatus('DISCONNECTED', { reason: 'MANUAL' });
    logger.info('[WA] Disconnected manually');
  } catch (err) {
    logger.error({ err }, '[WA] Error during disconnect');
    throw err;
  }
}

/**
 * Enviar mensaje de texto
 */
async function sendTextMessage(to, text) {
  if (!sock || sessionStatus !== 'CONNECTED') {
    throw new Error('WhatsApp not connected');
  }
  // Asegurar formato JID correcto: 573001234567@s.whatsapp.net
  const jid = to.includes('@') ? to : `${to}@s.whatsapp.net`;

  // Delay humanizado (1-3 segundos) para reducir detección de bot
  const humanDelay = 1000 + Math.random() * 2000;
  await new Promise(r => setTimeout(r, humanDelay));

  await sock.sendPresenceUpdate('composing', jid);
  await new Promise(r => setTimeout(r, 800 + Math.random() * 700));
  await sock.sendPresenceUpdate('paused', jid);

  const result = await sock.sendMessage(jid, { text });
  logger.info({ to: jid, msgId: result.key.id }, '[WA] Text message sent');
  return result;
}

/**
 * Enviar imagen
 */
async function sendImageMessage(to, imageUrl, caption = '') {
  if (!sock || sessionStatus !== 'CONNECTED') throw new Error('WhatsApp not connected');
  const jid = to.includes('@') ? to : `${to}@s.whatsapp.net`;
  const result = await sock.sendMessage(jid, {
    image: { url: imageUrl },
    caption,
  });
  logger.info({ to: jid, msgId: result.key.id }, '[WA] Image message sent');
  return result;
}

/**
 * Enviar documento/PDF
 */
async function sendDocumentMessage(to, docUrl, filename, mimetype = 'application/pdf') {
  if (!sock || sessionStatus !== 'CONNECTED') throw new Error('WhatsApp not connected');
  const jid = to.includes('@') ? to : `${to}@s.whatsapp.net`;
  const result = await sock.sendMessage(jid, {
    document: { url: docUrl },
    fileName: filename,
    mimetype,
  });
  logger.info({ to: jid, msgId: result.key.id }, '[WA] Document sent');
  return result;
}

/**
 * Enviar ubicación GPS
 */
async function sendLocationMessage(to, latitude, longitude, name = '') {
  if (!sock || sessionStatus !== 'CONNECTED') throw new Error('WhatsApp not connected');
  const jid = to.includes('@') ? to : `${to}@s.whatsapp.net`;
  const result = await sock.sendMessage(jid, {
    location: { degreesLatitude: latitude, degreesLongitude: longitude, name },
  });
  logger.info({ to: jid }, '[WA] Location sent');
  return result;
}

module.exports = {
  connect,
  disconnect,
  getStatus,
  getQR,
  getSocket,
  sendTextMessage,
  sendImageMessage,
  sendDocumentMessage,
  sendLocationMessage,
};
