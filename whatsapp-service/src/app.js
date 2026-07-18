/**
 * WhatsApp Service — Express App Entry Point
 */

require('dotenv').config();
const express = require('express');
const pino = require('pino');
const config = require('./config');
const waClient = require('./whatsapp/client');
const sessionRoutes = require('./routes/session.routes');
const messageRoutes = require('./routes/message.routes');

const logger = pino({
  level: config.nodeEnv === 'production' ? 'info' : 'debug',
  transport: config.nodeEnv !== 'production' ? { target: 'pino-pretty' } : undefined,
});

const app = express();
app.use(express.json({ limit: '10mb' }));

// ── Health check ────────────────────────────────────────────────────────────────
app.get('/health', (req, res) => {
  res.json({
    service: 'whatsapp-service',
    status: 'UP',
    waStatus: waClient.getStatus(),
    timestamp: new Date().toISOString(),
  });
});

// ── Routes ──────────────────────────────────────────────────────────────────────
app.use('/session', sessionRoutes);
app.use('/messages', messageRoutes);

// ── Global Error Handler ────────────────────────────────────────────────────────
app.use((err, req, res, _next) => {
  logger.error({ err }, 'Unhandled error');
  res.status(500).json({ error: 'Internal Server Error', message: err.message });
});

// ── Start ────────────────────────────────────────────────────────────────────────
app.listen(config.port, async () => {
  logger.info({ port: config.port }, '🚀 WhatsApp Service started');

  // Auto-iniciar sesión al arrancar
  try {
    await waClient.connect();
  } catch (err) {
    logger.error({ err }, 'Failed to start WhatsApp connection on startup');
  }
});

// Graceful shutdown
process.on('SIGTERM', async () => {
  logger.info('SIGTERM received — shutting down gracefully');
  try { await waClient.disconnect(); } catch (_) {}
  process.exit(0);
});

process.on('unhandledRejection', (reason) => {
  logger.error({ reason }, 'Unhandled Promise Rejection');
});
