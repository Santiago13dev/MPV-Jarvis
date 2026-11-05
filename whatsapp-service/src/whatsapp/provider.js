/**
 * Selector de proveedor de WhatsApp según WA_PROVIDER.
 *  - "baileys" (default): sesión por QR / dispositivo vinculado
 *  - "twilio": WhatsApp Cloud vía Twilio (sin QR)
 * Ambos exponen la misma interfaz para rutas y app.
 */
const config = require('../config');

module.exports = config.provider === 'twilio'
  ? require('./twilio.client')
  : require('./client');
