FROM node:20-alpine

WORKDIR /app

# Instalar dependencias del sistema necesarias para Baileys
RUN apk add --no-cache \
    git \
    chromium \
    nss \
    freetype \
    harfbuzz \
    ca-certificates \
    ttf-freefont \
    python3 \
    make \
    g++ \
    su-exec

# Copiar package files y instalar dependencias
COPY package*.json ./
RUN npm install --omit=dev

# Copiar código fuente
COPY src/ ./src/
COPY public/ ./public/

# Crear directorio de sesiones (será un volumen)
RUN mkdir -p /app/sessions /app/media

# Usuario no-root por seguridad
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN chown -R appuser:appgroup /app
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 3001

ENTRYPOINT ["/app/entrypoint.sh"]
