/**
 * Session Routes — GET /session/status, GET /session/qr, POST /session/disconnect
 */

const express = require('express');
const router = express.Router();
const waClient = require('../whatsapp/client');

// GET /health — health check para Docker
router.get('/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime() });
});

// GET /session/status
router.get('/status', (req, res) => {
  res.json({
    status: waClient.getStatus(),
    timestamp: new Date().toISOString(),
  });
});

// GET /session/qr — retorna el QR en base64 si está disponible
router.get('/qr', (req, res) => {
  const qr = waClient.getQR();
  const status = waClient.getStatus();

  if (status === 'CONNECTED') {
    return res.json({ status: 'CONNECTED', qr: null });
  }

  if (!qr) {
    return res.status(202).json({
      status,
      message: 'QR not ready yet. Retry in a few seconds.',
      qr: null,
    });
  }

  res.json({ status, qr });
});

// POST /session/reconnect — forzar reconexión
router.post('/reconnect', async (req, res) => {
  try {
    await waClient.connect();
    res.json({ message: 'Reconnect initiated' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /session/disconnect — logout manual
router.post('/disconnect', async (req, res) => {
  try {
    await waClient.disconnect();
    res.json({ message: 'Disconnected successfully' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /session/reset — borrar sesión y reconectar con QR nuevo
router.post('/reset', async (req, res) => {
  try {
    await waClient.resetSession();
    res.json({ message: 'Session reset successfully' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;
