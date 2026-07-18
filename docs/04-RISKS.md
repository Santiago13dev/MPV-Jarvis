# ⚠️ Riesgos Técnicos del Proyecto

## 🔴 Riesgos Críticos

### R1 — OpenWA / Baileys puede ser baneado por WhatsApp
**Probabilidad**: Media | **Impacto**: Crítico

WhatsApp bloquea activamente automatizaciones no oficiales. Un número puede ser
suspendido si envía demasiados mensajes similares o detecta comportamiento de bot.

**Mitigaciones**:
- Usar número dedicado (no el personal del dueño)
- Rate limiting estricto (máx N mensajes por hora por contacto)
- Human-like delays (entre 1-3 segundos antes de responder)
- Nunca enviar mensajes masivos no solicitados
- Usar WhatsApp Business API oficial como alternativa a futuro
- Mantener `@whiskeysockets/baileys` actualizado (fork más activo que OpenWA)

**Nota del Arquitecto**: OpenWA (también llamado `@open-wa/wa-automate`) tiene 
menos mantenimiento activo que `@whiskeysockets/baileys`. Recomiendo evaluar 
Baileys como alternativa más estable para producción.

---

### R2 — Costo de OpenAI puede escalar
**Probabilidad**: Baja-Media | **Impacto**: Medio

Si el motor híbrido falla en clasificar correctamente y todo va a IA, los costos suben.

**Mitigaciones**:
- Motor híbrido bien entrenado con FAQs exhaustivas
- Usar `gpt-4o-mini` (más económico, suficiente para soporte básico)
- Máximo de tokens por request configurable (default: 500)
- Alert cuando tokens mensuales superen umbral configurado
- Dashboard muestra costo estimado en tiempo real
- Circuit breaker: si IA falla, respuesta genérica (no reintentar indefinidamente)

---

### R3 — Pérdida de sesión WhatsApp
**Probabilidad**: Media | **Impacto**: Alto

La sesión de WhatsApp se puede perder (reinicio del VPS, error de red, actualización
de WhatsApp que invalide el protocolo).

**Mitigaciones**:
- Persistir sesión en disco (volumen Docker mapeado)
- Reconexión automática con backoff exponencial
- Notificación al dueño (email/WhatsApp de admin) cuando sesión se pierde
- QR accesible desde dashboard en cualquier momento
- Health check cada 30 segundos con auto-reconnect

---

## 🟠 Riesgos Altos

### R4 — Calidad de respuestas de IA
**Probabilidad**: Alta | **Impacto**: Medio

La IA puede responder incorrectamente sobre precios, disponibilidad o políticas si el
prompt no está bien construido.

**Mitigaciones**:
- Prompt del sistema incluye instrucción: "Si no sabes la respuesta, di que un 
  asesor te contactará"
- Revisar historial de conversaciones regularmente desde el dashboard
- Human takeover disponible para escalar cuando sea necesario
- Temperatura baja en OpenAI (0.3-0.5) para respuestas más deterministas

---

### R5 — VPS sin recursos suficientes
**Probabilidad**: Media | **Impacto**: Medio

Spring Boot + Angular + Node.js + PostgreSQL + Nginx en un VPS barato puede agotar
RAM o CPU.

**Mitigaciones**:
- Spring Boot: 512MB heap mínimo (-Xmx512m)
- PostgreSQL: limitar shared_buffers a 256MB
- Angular: solo archivos estáticos (Nginx los sirve eficientemente)
- Node.js: ~200MB en reposo con OpenWA
- VPS recomendado: 2GB RAM, 2 vCPU (ej. DigitalOcean $18/mes o Hetzner CX21)
- Swap configurado: 2GB para evitar OOM killer
- Docker resource limits en docker-compose

---

### R6 — Spam / abuso del bot
**Probabilidad**: Media | **Impacto**: Medio

Usuarios malintencionados pueden inundar el número con mensajes para generar costos
de IA o degradar el servicio.

**Mitigaciones**:
- Rate limiting: máx 10 mensajes por número en 5 minutos
- Blacklist de números en DB
- Ignorar mensajes de grupos (solo chats 1:1)
- Si un número supera el umbral, auto-bloquear temporalmente

---

## 🟡 Riesgos Medios

### R7 — Complejidad del FAQ matching
La similitud entre la pregunta del usuario y las FAQs no es trivial. Matching simple
por keywords puede fallar ("¿cuánto vale?" vs "¿cuál es el precio?").

**Mitigación**: 
- Múltiples keywords por FAQ
- En el futuro: embeddings locales (sentence-transformers) para similitud semántica
- Por ahora: pedir al dueño que ingrese muchas variantes de cada pregunta

### R8 — Mantenimiento por actualizaciones de WhatsApp
WhatsApp actualiza su protocolo y puede romper OpenWA/Baileys.

**Mitigación**:
- Mantener dependencias actualizadas
- Suscribirse a repositorio de Baileys para alerts de breaking changes
- Arquitectura desacoplada: el Node.js service es independiente, se puede actualizar
  sin tocar Spring Boot ni Angular

---

## 📋 Matriz de Riesgos

| ID  | Riesgo                       | Probabilidad | Impacto  | Estrategia    |
|-----|------------------------------|--------------|----------|---------------|
| R1  | Baneo WhatsApp               | Media        | Crítico  | Mitigar       |
| R2  | Costo IA alto                | Media        | Medio    | Mitigar       |
| R3  | Pérdida sesión               | Media        | Alto     | Mitigar       |
| R4  | Calidad IA baja              | Alta         | Medio    | Mitigar       |
| R5  | VPS sin recursos             | Media        | Medio    | Mitigar       |
| R6  | Spam/abuso                   | Media        | Medio    | Mitigar       |
| R7  | FAQ matching impreciso       | Alta         | Bajo     | Aceptar/Mejorar|
| R8  | Actualizaciones WA           | Alta         | Medio    | Monitorear    |
