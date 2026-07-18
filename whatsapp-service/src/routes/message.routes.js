/**
 * Message Routes — POST /messages/send
 * Spring Boot llama estos endpoints para enviar mensajes de salida
 */

const express = require('express');
const router = express.Router();
const waClient = require('../whatsapp/client');
const config = require('../config');

// Middleware: validar webhook secret
function validateSecret(req, res, next) {
  const secret = req.headers['x-webhook-secret'];
  if (secret !== config.webhookSecret) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
}

// POST /messages/send/text
router.post('/send/text', validateSecret, async (req, res) => {
  const { to, text } = req.body;
  if (!to || !text) {
    return res.status(400).json({ error: 'Fields "to" and "text" are required' });
  }
  try {
    const result = await waClient.sendTextMessage(to, text);
    res.json({ success: true, messageId: result.key.id });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /messages/send/image
router.post('/send/image', validateSecret, async (req, res) => {
  const { to, imageUrl, caption } = req.body;
  if (!to || !imageUrl) {
    return res.status(400).json({ error: 'Fields "to" and "imageUrl" are required' });
  }
  try {
    const result = await waClient.sendImageMessage(to, imageUrl, caption);
    res.json({ success: true, messageId: result.key.id });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /messages/send/document
router.post('/send/document', validateSecret, async (req, res) => {
  const { to, docUrl, filename, mimetype } = req.body;
  if (!to || !docUrl || !filename) {
    return res.status(400).json({ error: 'Fields "to", "docUrl" and "filename" are required' });
  }
  try {
    const result = await waClient.sendDocumentMessage(to, docUrl, filename, mimetype);
    res.json({ success: true, messageId: result.key.id });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /messages/send/location
router.post('/send/location', validateSecret, async (req, res) => {
  const { to, latitude, longitude, name } = req.body;
  if (!to || latitude === undefined || longitude === undefined) {
    return res.status(400).json({ error: 'Fields "to", "latitude" and "longitude" are required' });
  }
  try {
    const result = await waClient.sendLocationMessage(to, latitude, longitude, name);
    res.json({ success: true, messageId: result.key.id });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;
