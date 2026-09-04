#!/bin/sh
# Fix volume permissions then drop to appuser
chown -R appuser:appgroup /app/sessions /app/media 2>/dev/null || true
exec su-exec appuser node src/app.js
