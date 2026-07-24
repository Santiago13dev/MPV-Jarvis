import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router, NavigationEnd } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { SessionStatus } from '../../core/models/models';

@Component({
  selector: 'app-topbar',
  template: `
    <header class="topbar">
      <div class="topbar-left">
        <h2 class="page-title">{{ pageTitle }}</h2>
      </div>
      <div class="topbar-right">
        <!-- Estado sesión WhatsApp -->
        <div class="session-pill" [class]="'pill-' + sessionStatus.toLowerCase()">
          <span class="status-dot"
            [class.connected]="sessionStatus === 'CONNECTED'"
            [class.connecting]="sessionStatus === 'CONNECTING' || sessionStatus === 'QR_READY'"
            [class.disconnected]="sessionStatus === 'DISCONNECTED' || sessionStatus === 'ERROR'">
          </span>
          <span>{{ sessionLabel }}</span>
        </div>
      </div>
    </header>
  `,
  styles: [`
    .topbar {
      height: var(--topbar-height);
      padding-top: var(--safe-top);
      background: var(--color-bg-surface);
      border-bottom: 1px solid var(--color-border);
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--spacing-sm);
      padding-left: var(--content-padding);
      padding-right: var(--content-padding);
      flex-shrink: 0;
    }
    .topbar-left { min-width: 0; flex: 1; }
    .page-title {
      font-size: var(--font-size-lg);
      font-weight: 600;
      color: var(--color-text-primary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .topbar-right { display: flex; align-items: center; gap: var(--spacing-md); flex-shrink: 0; }

    .session-pill {
      display: flex;
      align-items: center;
      gap: var(--spacing-sm);
      padding: 6px 14px;
      border-radius: var(--radius-full);
      font-size: var(--font-size-sm);
      font-weight: 500;
      border: 1px solid var(--color-border);
      background: var(--color-bg-input);
      white-space: nowrap;
    }

    @media (max-width: 767px) {
      .page-title { font-size: var(--font-size-md); }
    }

    /* En pantallas muy angostas solo se muestra el punto de estado */
    @media (max-width: 380px) {
      .session-pill { padding: 6px; gap: 0; }
      .session-pill span:last-child { display: none; }
    }
    .pill-connected  { border-color: rgba(37,211,102,0.4); background: rgba(37,211,102,0.08); color: var(--color-success); }
    .pill-qr_ready   { border-color: rgba(245,158,11,0.4); background: rgba(245,158,11,0.08); color: var(--color-warning); }
    .pill-connecting { border-color: rgba(59,130,246,0.4); background: rgba(59,130,246,0.08); color: var(--color-info); }
    .pill-disconnected, .pill-error {
      border-color: rgba(239,68,68,0.4); background: rgba(239,68,68,0.08); color: var(--color-error);
    }
  `]
})
export class TopbarComponent implements OnInit, OnDestroy {
  sessionStatus: SessionStatus = 'DISCONNECTED';
  pageTitle = 'Dashboard';

  private subs = new Subscription();

  private routeTitles: Record<string, string> = {
    '/dashboard':     'Dashboard',
    '/whatsapp':      'Conexión WhatsApp',
    '/conversations': 'Conversaciones',
    '/faqs':          'FAQs',
    '/config':        'Configuración',
  };

  get sessionLabel(): string {
    const labels: Record<SessionStatus, string> = {
      CONNECTED:    'WhatsApp Conectado',
      CONNECTING:   'Conectando...',
      QR_READY:     'Escanear QR',
      DISCONNECTED: 'Desconectado',
      ERROR:        'Error de conexión',
    };
    return labels[this.sessionStatus] || this.sessionStatus;
  }

  constructor(
    private apiService: ApiService,
    private wsService: WebSocketService,
    private router: Router
  ) {}

  ngOnInit(): void {
    // Título dinámico según la ruta
    this.subs.add(
      this.router.events
        .pipe(filter(e => e instanceof NavigationEnd))
        .subscribe((e) => {
          const ne = e as NavigationEnd;
          const base = '/' + ne.urlAfterRedirects.split('/')[1];
          this.pageTitle = this.routeTitles[base] || 'Dashboard';
        })
    );

    // Cargar estado inicial
    this.apiService.getSessionStatus().subscribe(r => {
      if (r?.data?.status) this.sessionStatus = r.data.status as SessionStatus;
    });

    // Actualizar en tiempo real vía WebSocket
    this.subs.add(
      this.wsService.onSessionStatus().subscribe(e => {
        this.sessionStatus = e.status;
      })
    );
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
  }
}
