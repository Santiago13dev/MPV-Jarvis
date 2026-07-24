import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subscription, EMPTY } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ApiService } from '../../core/services/api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { SessionStatus } from '../../core/models/models';

@Component({
  selector: 'app-whatsapp-status',
  template: `
    <div class="page-header">
      <div>
        <h1>Conexión WhatsApp</h1>
        <p>Gestiona la sesión de WhatsApp Business de tu negocio</p>
      </div>
    </div>

    <div class="wa-grid">

      <!-- Estado de la sesión -->
      <div class="card status-card">
        <div class="status-indicator" [class]="'status-' + status.toLowerCase()">
          <div class="status-ring">
            <span class="status-emoji">{{ statusEmoji }}</span>
          </div>
          <div class="status-info">
            <h2>{{ statusLabel }}</h2>
            <p class="text-muted text-sm" *ngIf="phone">Número: {{ phone }}</p>
            <p class="text-muted text-sm" *ngIf="connectedAt">
              Conectado desde: {{ connectedAt | date:'dd/MM/yyyy HH:mm' }}
            </p>
            <p class="text-sm" style="color:var(--color-error)" *ngIf="errorMessage">
              Error: {{ errorMessage }}
            </p>
          </div>
        </div>

        <div class="action-buttons">
          <button class="btn btn-primary" (click)="reconnect()"
            *ngIf="status !== 'CONNECTED'" [disabled]="status === 'CONNECTING'">
            <span *ngIf="status === 'CONNECTING'" class="spinner"></span>
            🔄 {{ status === 'CONNECTING' ? 'Conectando...' : 'Conectar' }}
          </button>
          <button class="btn btn-danger" (click)="disconnect()"
            *ngIf="status === 'CONNECTED'">
            ⏹ Desconectar
          </button>
          <button class="btn btn-warning" (click)="resetSession()"
            *ngIf="status !== 'CONNECTED'" [disabled]="status === 'CONNECTING'"
            title="Borrar credenciales y generar un QR nuevo">
            🔄 Reiniciar sesión
          </button>
          <button class="btn btn-secondary" (click)="refreshStatus()">
            Actualizar estado
          </button>
        </div>
      </div>

      <!-- QR Code -->
      <div class="card qr-card" *ngIf="status === 'QR_READY' || status === 'CONNECTING'">
        <h3>Escanear código QR</h3>
        <p class="text-muted text-sm" style="margin-bottom:var(--spacing-lg)">
          Abre WhatsApp en tu teléfono → Menú → Dispositivos vinculados → Vincular un dispositivo
        </p>
        <div class="qr-wrapper" *ngIf="qrCode; else waitingQr">
          <img [src]="qrCode" alt="WhatsApp QR Code" class="qr-image">
          <p class="text-xs text-muted" style="margin-top:var(--spacing-sm)">
            El QR se actualiza automáticamente cada 60 segundos
          </p>
        </div>
        <ng-template #waitingQr>
          <div class="qr-placeholder">
            <div class="spinner" style="width:32px;height:32px;border-width:3px"></div>
            <p class="text-muted" style="margin-top:var(--spacing-md)">Generando QR...</p>
          </div>
        </ng-template>
      </div>

      <!-- Instrucciones -->
      <div class="card instructions-card">
        <h3>¿Cómo conectar?</h3>
        <ol class="steps">
          <li class="step">
            <span class="step-num">1</span>
            <span>Abre WhatsApp en tu teléfono</span>
          </li>
          <li class="step">
            <span class="step-num">2</span>
            <span>Toca los tres puntos (⋮) → <strong>Dispositivos vinculados</strong></span>
          </li>
          <li class="step">
            <span class="step-num">3</span>
            <span>Toca <strong>Vincular un dispositivo</strong></span>
          </li>
          <li class="step">
            <span class="step-num">4</span>
            <span>Escanea el código QR de la izquierda</span>
          </li>
          <li class="step">
            <span class="step-num">5</span>
            <span>¡Listo! El estado cambiará a <strong style="color:var(--color-success)">Conectado</strong></span>
          </li>
        </ol>

        <div class="warning-box" style="margin-top:var(--spacing-lg)">
          <strong>⚠️ Importante:</strong>
          <ul style="margin-top:8px;padding-left:20px;font-size:var(--font-size-sm);color:var(--color-text-secondary)">
            <li>Mantén el teléfono con batería y conexión a internet</li>
            <li>No cierres WhatsApp en el teléfono</li>
            <li>La sesión persiste incluso si reinicias el servidor</li>
            <li>Si el QR no aparece o da error, usa <strong>"Reiniciar sesión"</strong> para generar uno nuevo</li>
          </ul>
        </div>
      </div>

    </div>
  `,
  styles: [`
    .wa-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--spacing-lg);
      @media (max-width: 900px) { grid-template-columns: 1fr; }
    }

    @media (max-width: 767px) {
      .wa-grid { gap: var(--spacing-md); }
      .wa-grid .card { padding: var(--spacing-md); }
    }

    /* Status card */
    .status-card { grid-column: 1 / -1; }
    .status-indicator {
      display: flex; align-items: center; gap: var(--spacing-lg);
      padding: var(--spacing-md) 0;
    }

    @media (max-width: 480px) {
      .status-indicator {
        flex-direction: column;
        align-items: center;
        text-align: center;
        gap: var(--spacing-md);
      }
      .status-info h2 { font-size: var(--font-size-xl); }
    }

    .status-ring {
      width: 80px; height: 80px; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      font-size: 36px; border: 3px solid var(--color-border);
      flex-shrink: 0;
    }
    .status-info h2 { font-size: var(--font-size-2xl); font-weight: 700; }

    .status-connected    .status-ring { border-color: var(--color-success); background: rgba(37,211,102,0.1); }
    .status-qr_ready     .status-ring { border-color: var(--color-warning); background: rgba(245,158,11,0.1); }
    .status-connecting   .status-ring { border-color: var(--color-info);    background: rgba(59,130,246,0.1); }
    .status-disconnected .status-ring { border-color: var(--color-border);  background: var(--color-bg-input); }
    .status-error        .status-ring { border-color: var(--color-error);   background: rgba(239,68,68,0.1); }

    .action-buttons {
      display: flex; gap: var(--spacing-sm);
      margin-top: var(--spacing-lg);
      padding-top: var(--spacing-lg);
      border-top: 1px solid var(--color-border);
      flex-wrap: wrap;
    }

    .btn-warning {
      background: rgba(245,158,11,0.15);
      color: var(--color-warning, #f59e0b);
      border: 1px solid rgba(245,158,11,0.3);
    }
    .btn-warning:hover:not(:disabled) {
      background: rgba(245,158,11,0.25);
    }

    @media (max-width: 480px) {
      .action-buttons { flex-direction: column; }
      .action-buttons .btn { width: 100%; justify-content: center; }
    }

    /* QR card */
    .qr-card h3 { font-size: var(--font-size-lg); font-weight: 700; margin-bottom: var(--spacing-sm); }
    .qr-wrapper { display: flex; flex-direction: column; align-items: center; }
    .qr-image {
      width: 240px; height: 240px;
      max-width: 100%;
      border-radius: var(--radius-md);
      border: 4px solid var(--color-primary);
      box-shadow: var(--shadow-glow);
    }
    .qr-placeholder {
      display: flex; flex-direction: column; align-items: center;
      justify-content: center; height: 200px;
    }

    @media (max-width: 480px) {
      .qr-image { width: 200px; height: 200px; }
      .qr-placeholder { height: 160px; }
    }

    /* Instructions */
    .instructions-card h3 { font-size: var(--font-size-lg); font-weight: 700; margin-bottom: var(--spacing-md); }
    .steps { list-style: none; display: flex; flex-direction: column; gap: var(--spacing-md); }
    .step {
      display: flex; align-items: flex-start; gap: var(--spacing-md);
      font-size: var(--font-size-md); color: var(--color-text-secondary);
      .step-num {
        width: 28px; height: 28px; border-radius: 50%;
        background: rgba(37,211,102,0.15); color: var(--color-primary);
        display: flex; align-items: center; justify-content: center;
        font-weight: 700; font-size: var(--font-size-sm); flex-shrink: 0;
      }
    }
    .warning-box {
      background: rgba(245,158,11,0.08); border: 1px solid rgba(245,158,11,0.3);
      border-radius: var(--radius-md); padding: var(--spacing-md);
      font-size: var(--font-size-sm); color: var(--color-warning);
    }

    @media (max-width: 480px) {
      .step { font-size: var(--font-size-sm); gap: var(--spacing-sm); }
    }
  `]
})
export class WhatsappStatusComponent implements OnInit, OnDestroy {
  status: SessionStatus = 'DISCONNECTED';
  phone = '';
  qrCode: string | null = null;
  connectedAt: string | null = null;
  errorMessage: string | null = null;

  private subs = new Subscription();
  private qrPollingInterval: any;

  get statusLabel(): string {
    const m: Record<SessionStatus, string> = {
      CONNECTED: 'Conectado ✓', CONNECTING: 'Conectando...',
      QR_READY: 'Esperando QR', DISCONNECTED: 'Desconectado', ERROR: 'Error'
    };
    return m[this.status] || this.status;
  }

  get statusEmoji(): string {
    const m: Record<SessionStatus, string> = {
      CONNECTED: '✅', CONNECTING: '⏳', QR_READY: '📷',
      DISCONNECTED: '📵', ERROR: '❌'
    };
    return m[this.status] || '❓';
  }

  constructor(private api: ApiService, private ws: WebSocketService) {}

  ngOnInit(): void {
    this.refreshStatus();

    // Actualización en tiempo real via WebSocket
    this.subs.add(
      this.ws.onSessionStatus().pipe(
        catchError(() => EMPTY)
      ).subscribe(e => {
        this.status = e.status;
        if (e.phoneNumber) this.phone = e.phoneNumber;
        if (e.qrCode) this.qrCode = e.qrCode;
      })
    );

    // Polling del QR cada 20 segundos si está esperando
    this.qrPollingInterval = setInterval(() => {
      if (this.status === 'QR_READY' || this.status === 'CONNECTING') {
        this.api.getQrCode().pipe(
          catchError(() => EMPTY)
        ).subscribe(r => {
          if (r?.data) {
            if (r.data.qr) this.qrCode = r.data.qr;
            if (r.data.status && r.data.status !== 'ERROR') this.status = r.data.status as SessionStatus;
          }
        });
      }
    }, 20000);
  }

  refreshStatus(): void {
    this.api.getSessionStatus().pipe(
      catchError(() => EMPTY)
    ).subscribe(r => {
      if (r?.data) {
        this.status       = r.data.status;
        this.phone        = r.data.phoneNumber || '';
        this.qrCode       = r.data.qrCode || null;
        this.connectedAt  = r.data.connectedAt || null;
        this.errorMessage = r.data.errorMessage || null;
      }
    });
    this.api.getQrCode().pipe(
      catchError(() => EMPTY)
    ).subscribe(r => {
      if (r?.data) {
        if (r.data.qr) this.qrCode = r.data.qr;
        if (r.data.status && r.data.status !== 'ERROR') this.status = r.data.status as SessionStatus;
      }
    });
  }

  reconnect(): void {
    this.status = 'CONNECTING';
    this.api.reconnectWhatsapp().pipe(
      catchError(() => EMPTY)
    ).subscribe(() => this.refreshStatus());
  }

  disconnect(): void {
    if (!confirm('¿Deseas desconectar WhatsApp? Deberás escanear el QR nuevamente para reconectarte.')) return;
    this.api.disconnectWhatsapp().pipe(
      catchError(() => EMPTY)
    ).subscribe(() => {
      this.status = 'DISCONNECTED';
      this.qrCode = null;
    });
  }

  resetSession(): void {
    if (!confirm('¿Reiniciar la sesión? Se borrarán las credenciales y se generará un QR nuevo. Deberás escanear el QR con tu teléfono.')) return;
    this.status = 'CONNECTING';
    this.qrCode = null;
    this.api.resetWhatsapp().pipe(
      catchError(() => EMPTY)
    ).subscribe(() => this.refreshStatus());
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
    clearInterval(this.qrPollingInterval);
  }
}
