/**
 * Simulador local de WhatsApp + panel de chat para probar el bot.
 *
 * - Actúa como el "whatsapp-service": recibe los envíos del backend en :3001.
 * - Sirve una interfaz web en http://localhost:3001 para chatear con el bot
 *   como si fueras un cliente.
 * - Reenvía tus mensajes al webhook del backend (:8080).
 *
 * No conecta a WhatsApp. Ideal para prueba/error rápido.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3001;
const BACKEND_WEBHOOK = process.env.BACKEND_WEBHOOK_URL || 'http://localhost:8080/api/webhook/message';
const WEBHOOK_SECRET = process.env.WA_WEBHOOK_SECRET || 'devsecret';
const LOG = path.join(__dirname, '..', 'tmp_fake_wa.log');

// Buzón de mensajes salientes capturados: [{seq, phone, to, text, at}]
let outbox = [];
let seq = 0;

function pushOutbound(parsed) {
  const to = parsed.to || '';
  const phone = to.split('@')[0];
  outbox.push({
    seq: ++seq,
    phone,
    to,
    text: parsed.text || parsed.caption || `[${parsed.type || 'mensaje'}]`,
    at: new Date().toISOString(),
  });
  if (outbox.length > 5000) outbox = outbox.slice(-3000);
}

function logLine(obj) {
  const line = JSON.stringify(obj);
  console.log(line);
  try { fs.appendFileSync(LOG, line + '\n'); } catch (e) {}
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  // ── Interfaz de chat ────────────────────────────────────────────────
  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/chat')) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(HTML);
  }

  // ── API del simulador ───────────────────────────────────────────────
  if (req.method === 'GET' && url.pathname === '/api/messages') {
    const phone = (url.searchParams.get('phone') || '').replace(/\D/g, '');
    const after = parseInt(url.searchParams.get('after') || '0', 10);
    const msgs = outbox.filter((m) => m.phone === phone && m.seq > after);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ messages: msgs, lastSeq: seq }));
  }

  if (req.method === 'POST' && url.pathname === '/api/send') {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', async () => {
      try {
        const { phone, name, text } = JSON.parse(body || '{}');
        const cleanPhone = String(phone || '').replace(/\D/g, '');
        if (!cleanPhone || !text) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ error: 'phone y text son requeridos' }));
        }
        const payload = {
          waMessageId: `sim-${cleanPhone}-${Date.now()}-${Math.floor(Math.random() * 1000)}`,
          phone: cleanPhone,
          remoteJid: `${cleanPhone}@s.whatsapp.net`,
          displayName: name || 'Cliente Prueba',
          content: text,
          messageType: 'TEXT',
        };
        const r = await fetch(BACKEND_WEBHOOK, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Webhook-Secret': WEBHOOK_SECRET },
          body: JSON.stringify(payload),
        });
        logLine({ at: new Date().toISOString(), direction: 'IN', phone: cleanPhone, text });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: r.ok }));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: String(e) }));
      }
    });
    return;
  }

  // ── Endpoints que llama el backend para ENVIAR (capturamos) ─────────
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    let parsed = {};
    try { parsed = body ? JSON.parse(body) : {}; } catch (e) {}
    logLine({ at: new Date().toISOString(), method: req.method, url: req.url, to: parsed.to || null, text: parsed.text || null });

    if (url.pathname.includes('/messages/')) {
      pushOutbound(parsed);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ ok: true }));
    }
    if (url.pathname.includes('/session/status')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ status: 'CONNECTED', phoneNumber: '573123197433' }));
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
  });
});

const HTML = `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Simulador WhatsApp — La Montaña</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         background: #0b141a; height: 100vh; display: flex; align-items: center; justify-content: center; }
  .app { width: min(760px, 98vw); height: min(880px, 96vh); display: flex; flex-direction: column;
         background: #111b21; border-radius: 14px; overflow: hidden; box-shadow: 0 20px 60px rgba(0,0,0,.5); }
  .top { background: #202c33; color: #e9edef; padding: 10px 14px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .top .title { font-weight: 600; margin-right: auto; }
  .top label { font-size: 12px; color: #8696a0; display: flex; flex-direction: column; gap: 2px; }
  .top input { background: #2a3942; border: none; border-radius: 8px; color: #e9edef; padding: 6px 8px; font-size: 13px; width: 150px; }
  .top input.name { width: 130px; }
  .btn { background: #00a884; color: #fff; border: none; border-radius: 8px; padding: 8px 12px; cursor: pointer; font-size: 13px; }
  .btn.ghost { background: #2a3942; color: #e9edef; }
  .chat { flex: 1; overflow-y: auto; padding: 16px; background: #0b141a;
          background-image: radial-gradient(rgba(255,255,255,.03) 1px, transparent 1px); background-size: 18px 18px; }
  .row { display: flex; margin-bottom: 8px; }
  .row.out { justify-content: flex-end; }
  .bubble { max-width: 78%; padding: 9px 12px; border-radius: 10px; font-size: 14px; line-height: 1.38;
            white-space: pre-wrap; word-wrap: break-word; }
  .row.in .bubble { background: #202c33; color: #e9edef; border-top-left-radius: 2px; }
  .row.out .bubble { background: #005c4b; color: #e9edef; border-top-right-radius: 2px; }
  .empty { color: #8696a0; text-align: center; margin-top: 40px; font-size: 14px; line-height: 1.6; }
  .bottom { background: #202c33; padding: 10px; display: flex; gap: 8px; }
  .bottom textarea { flex: 1; resize: none; height: 44px; background: #2a3942; border: none; border-radius: 10px;
                     color: #e9edef; padding: 12px; font-size: 14px; font-family: inherit; outline: none; }
  .send { width: 48px; border: none; border-radius: 50%; background: #00a884; color: white; font-size: 18px; cursor: pointer; }
  .hint { color: #8696a0; font-size: 11px; padding: 0 14px 10px; background: #111b21; }
</style>
</head>
<body>
<div class="app">
  <div class="top">
    <div class="title">🧪 Simulador WhatsApp</div>
    <label>Teléfono cliente<input id="phone" value=""></label>
    <label>Nombre<input id="name" class="name" value="Cliente Prueba"></label>
    <button class="btn ghost" id="new">Nueva conversación</button>
  </div>
  <div class="chat" id="chat"><div class="empty">Escribe un mensaje para empezar.<br>Prueba: <b>"Hola, quiero reservar una mesa"</b></div></div>
  <div class="hint">Backend: ${BACKEND_WEBHOOK.replace('/api/webhook/message', '')} · Sin WhatsApp real · Ctrl/Cmd+Enter para enviar</div>
  <div class="bottom">
    <textarea id="text" placeholder="Escribe como cliente…"></textarea>
    <button class="send" id="send">➤</button>
  </div>
</div>
<script>
  const chat = document.getElementById('chat');
  const phoneInput = document.getElementById('phone');
  const nameInput = document.getElementById('name');
  const textInput = document.getElementById('text');

  function randomPhone() {
    // Número colombiano ficticio único para no arrastrar estado previo
    return '573' + String(Math.floor(100000000 + Math.random() * 899999999)).slice(0, 9).padEnd(9, '0');
  }
  if (!phoneInput.value) phoneInput.value = randomPhone();

  let lastSeqForPhone = {}; // phone -> último seq visto
  function currentPhone() { return phoneInput.value.replace(/\\D/g, ''); }

  function addBubble(dir, text) {
    const row = document.createElement('div');
    row.className = 'row ' + dir;
    const b = document.createElement('div');
    b.className = 'bubble';
    b.textContent = text;
    row.appendChild(b);
    chat.appendChild(row);
    chat.scrollTop = chat.scrollHeight;
  }

  function clearChat() {
    chat.innerHTML = '<div class="empty">Nueva conversación. Escribe un mensaje para empezar.</div>';
    const p = currentPhone();
    lastSeqForPhone[p] = 0;
  }

  async function send() {
    const text = textInput.value.trim();
    const phone = currentPhone();
    if (!text || !phone) return;
    textInput.value = '';
    if (chat.querySelector('.empty')) chat.innerHTML = '';
    addBubble('out', text);
    try {
      await fetch('/api/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone, name: nameInput.value, text }),
      });
    } catch (e) {
      addBubble('in', '⚠️ Error de conexión con el backend: ' + e);
    }
  }

  async function poll() {
    const phone = currentPhone();
    const after = lastSeqForPhone[phone] || 0;
    if (!phone) return;
    try {
      const r = await fetch('/api/messages?phone=' + phone + '&after=' + after);
      const data = await r.json();
      if (data.messages && data.messages.length) {
        if (chat.querySelector('.empty')) chat.innerHTML = '';
        for (const m of data.messages) {
          addBubble('in', m.text);
          lastSeqForPhone[phone] = m.seq;
        }
      }
    } catch (e) {}
  }

  document.getElementById('send').onclick = send;
  textInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); send(); }
    if (e.key === 'Enter' && !e.shiftKey && !e.metaKey && !e.ctrlKey) { e.preventDefault(); send(); }
  });
  document.getElementById('new').onclick = () => {
    phoneInput.value = randomPhone();
    clearChat();
  };
  phoneInput.addEventListener('change', clearChat);
  setInterval(poll, 600);
</script>
</body>
</html>`;

server.listen(PORT, () => {
  console.log('🧪 Simulador de WhatsApp escuchando en http://localhost:' + PORT);
  console.log('   Backend webhook:', BACKEND_WEBHOOK);
});
