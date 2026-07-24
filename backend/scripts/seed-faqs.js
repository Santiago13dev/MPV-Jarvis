/**
 * seed-faqs.js
 * ------------------------------------------------------------------
 * Carga masiva de FAQs para "La Montaña del Bendito Chicharrón"
 * contra el backend Spring Boot (POST /api/faqs).
 *
 * Uso:
 *   node backend/scripts/seed-faqs.js
 *
 * Requiere Node.js 18+ (usa fetch nativo).
 * Lee las credenciales de admin y la URL del backend desde el .env
 * ubicado en la raíz del proyecto (../../.env respecto a este archivo).
 * También puedes sobreescribirlas con variables de entorno:
 *   BASE_URL, ADMIN_EMAIL, ADMIN_PASSWORD
 * ------------------------------------------------------------------
 */

const fs = require('fs');
const path = require('path');

// ── Cargar .env de la raíz del proyecto (sin dependencias externas) ──────────
function loadEnvFile(envPath) {
  const result = {};
  if (!fs.existsSync(envPath)) return result;
  const content = fs.readFileSync(envPath, 'utf-8');
  for (const rawLine of content.split('\n')) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eqIdx = line.indexOf('=');
    if (eqIdx === -1) continue;
    const key = line.slice(0, eqIdx).trim();
    let value = line.slice(eqIdx + 1).trim();
    // Quitar comillas envolventes si existen
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    result[key] = value;
  }
  return result;
}

const rootEnv = loadEnvFile(path.join(__dirname, '..', '..', '.env'));

const BASE_URL       = process.env.BASE_URL       || 'http://localhost:8080';
const ADMIN_EMAIL    = process.env.ADMIN_EMAIL    || rootEnv.ADMIN_EMAIL;
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || rootEnv.ADMIN_PASSWORD;

if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
  console.error('❌ No encontré ADMIN_EMAIL / ADMIN_PASSWORD (ni en .env ni en variables de entorno).');
  process.exit(1);
}

// ── Las 16 FAQs extraídas de la base de conocimiento ─────────────────────────
const faqs = [
  {
    question: '¿Cuál es el horario de atención?',
    answer:
      'Nuestro horario de atención es:\n' +
      '- Sábados y domingos: 11:30 a. m. hasta agotar existencias.\n' +
      '- Lunes festivos: 11:30 a. m. hasta agotar existencias.',
    keywords: ['horario', 'hora', 'abren', 'cierran', 'atienden', 'dias'],
    priority: 90
  },
  {
    question: '¿Cómo puedo reservar?',
    answer:
      'Contamos con los siguientes horarios para reservas:\n' +
      '- 11:10 a. m.\n- 12:00 m.\n- 12:30 p. m.\n- 1:00 p. m.\n\n' +
      'Si deseas reservar para un horario posterior, haremos lo posible por ayudarte según la disponibilidad ' +
      'del restaurante. En estos casos, algunos platos deberán solicitarse con anticipación.',
    keywords: ['reservar', 'reserva', 'mesa', 'cita', 'apartar'],
    priority: 90
  },
  {
    question: '¿Qué información necesito dar para reservar?',
    answer:
      'RESERVA - BENDITO CHICHARRÓN\n\n' +
      'Por favor envíanos la siguiente información:\n' +
      '- Nombre de quien realiza la reserva.\n' +
      '- Fecha de la reserva.\n' +
      '- Número de personas.\n' +
      '- Motivo de la celebración.\n' +
      '- Nombre del homenajeado (si aplica).\n' +
      '- Hora de llegada.\n\n' +
      'Gracias por elegirnos y permitirnos ser parte de tus fechas especiales.',
    keywords: ['datos reserva', 'formulario reserva', 'informacion reserva', 'que necesito para reservar'],
    priority: 70
  },
  {
    question: '¿Cómo sé que mi reserva quedó confirmada?',
    answer:
      '¡Perfecto! Tu reserva ha quedado confirmada.\n\n' +
      'Te recordamos que el tiempo máximo de espera es de 15 minutos. Después de ese tiempo, ' +
      'la reserva estará sujeta a la disponibilidad de mesas.',
    keywords: ['confirmar reserva', 'confirmacion', 'reserva confirmada', 'tiempo de espera'],
    priority: 60
  },
  {
    question: '¿Tienen decoración para cumpleaños o celebraciones?',
    answer: 'Sí. Contamos con decoración para celebraciones.\n\nValor: $40.000 COP.',
    keywords: ['decoracion', 'cumpleaños', 'celebracion', 'cumple', 'globos'],
    priority: 60
  },
  {
    question: '¿Qué pasa si no puedo asistir a mi reserva?',
    answer:
      'Si no puedes asistir, por favor avísanos con anticipación.\n\n' +
      'El dinero de la reserva no es reembolsable, pero se conserva como saldo para reprogramar ' +
      'la reserva en una nueva fecha.',
    keywords: ['cancelar reserva', 'no puedo asistir', 'cambiar reserva', 'reembolso', 'reprogramar'],
    priority: 70
  },
  {
    question: '¿Qué medios de pago reciben?',
    answer: 'Recibimos:\n- Efectivo.\n- Nequi.\n- Transferencia bancaria.\n- Pago por Llave.',
    keywords: ['pago', 'pagar', 'efectivo', 'nequi', 'transferencia', 'tarjeta', 'llave'],
    priority: 80
  },
  {
    question: '¿Tienen servicio de domicilio?',
    answer: 'No. Actualmente no contamos con servicio de domicilio.',
    keywords: ['domicilio', 'delivery', 'envio', 'a domicilio'],
    priority: 80
  },
  {
    question: '¿Tienen parqueadero?',
    answer: 'Sí. Contamos con parqueadero público para carros y motos.',
    keywords: ['parqueadero', 'parking', 'carro', 'moto', 'estacionamiento'],
    priority: 70
  },
  {
    question: '¿Puedo llevar mi mascota?',
    answer:
      '¡Sí! Somos un restaurante pet friendly.\n\n' +
      'Solo te pedimos traer una bolsa para recoger los residuos de tu mascota.',
    keywords: ['mascota', 'perro', 'gato', 'pet friendly', 'mascotas'],
    priority: 70
  },
  {
    question: '¿Hay mesas disponibles?',
    answer:
      'El restaurante cuenta con 112 mesas.\n\n' +
      'La disponibilidad depende del momento de tu visita.',
    keywords: ['mesas disponibles', 'disponibilidad', 'cupo', 'hay mesa'],
    priority: 65
  },
  {
    question: '¿Qué tipo de carne utilizan?',
    answer:
      'Trabajamos con cerdo certificado de excelente calidad y ofrecemos nuestro reconocido ' +
      'churrasco calentano.',
    keywords: ['carne', 'cerdo', 'chicharron', 'churrasco', 'calidad carne'],
    priority: 60
  },
  {
    question: '¿Tienen WiFi?',
    answer: 'No contamos con servicio de WiFi.',
    keywords: ['wifi', 'internet', 'red'],
    priority: 50
  },
  {
    question: '¿Expiden factura electrónica?',
    answer: 'Sí. Emitimos factura electrónica.',
    keywords: ['factura', 'facturacion electronica', 'factura electronica'],
    priority: 55
  },
  {
    question: '¿Tienen música en vivo?',
    answer: 'Sí, ocasionalmente ofrecemos música en vivo los domingos.',
    keywords: ['musica', 'musica en vivo', 'banda', 'orquesta'],
    priority: 50
  },
  {
    question: '¿El restaurante tiene acceso para personas con discapacidad?',
    answer: 'Actualmente no contamos con adecuaciones especiales para personas con discapacidad.',
    keywords: ['discapacidad', 'accesibilidad', 'silla de ruedas', 'acceso'],
    priority: 55
  }
];

// ── Ejecutar ──────────────────────────────────────────────────────────────
async function main() {
  console.log(`\n🔐 Iniciando sesión en ${BASE_URL} como ${ADMIN_EMAIL}...`);

  const loginRes = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD })
  });

  const loginBody = await loginRes.json().catch(() => null);

  if (!loginRes.ok || !loginBody?.data?.accessToken) {
    console.error('❌ No se pudo iniciar sesión.', loginBody?.message || loginRes.status);
    process.exit(1);
  }

  const token = loginBody.data.accessToken;
  console.log('✅ Sesión iniciada correctamente.\n');

  let created = 0;
  let failed  = 0;

  for (const faq of faqs) {
    try {
      const res = await fetch(`${BASE_URL}/api/faqs`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify(faq)
      });

      const body = await res.json().catch(() => null);

      if (res.ok && body?.success) {
        created++;
        console.log(`✅ Creada: "${faq.question}"`);
      } else {
        failed++;
        console.log(`❌ Falló: "${faq.question}" → ${body?.message || res.status}`);
      }
    } catch (err) {
      failed++;
      console.log(`❌ Error de red en: "${faq.question}" → ${err.message}`);
    }
  }

  console.log(`\n📊 Resumen: ${created} creadas, ${failed} fallidas de ${faqs.length} totales.\n`);
}

main().catch(err => {
  console.error('❌ Error inesperado:', err);
  process.exit(1);
});
