import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { WebSocketService } from '../../core/services/websocket.service';

interface MetricCard {
  icon: string;
  label: string;
  value: number;
  color: string;
}

@Component({
  selector: 'app-dashboard-home',
  template: `
    <div class="page-header">
      <div>
        <h1>Dashboard</h1>
        <p>Resumen de actividad de hoy — {{ today }}</p>
      </div>
      <button class="btn btn-secondary" (click)="loadMetrics()">🔄 Actualizar</button>
    </div>

    <!-- Metric cards -->
    <div class="metrics-grid" *ngIf="!loading; else loadingTpl">
      <div class="metric-card card" *ngFor="let m of metricCards">
        <div class="metric-icon">{{ m.icon }}</div>
        <div class="metric-value" [style.color]="m.color">{{ m.value }}</div>
        <div class="metric-label">{{ m.label }}</div>
      </div>
    </div>

    <ng-template #loadingTpl>
      <div class="empty-state">
        <div class="spinner"></div>
        <p>Cargando métricas...</p>
      </div>
    </ng-template>

    <!-- Automation rate -->
    <div class="card automation-card" *ngIf="automationRate !== null">
      <h3>Tasa de Automatización</h3>
      <div class="rate-bar-wrapper">
        <div class="rate-bar">
          <div class="rate-fill" [style.width]="automationRate + '%'"></div>
        </div>
        <span class="rate-value">{{ automationRate }}%</span>
      </div>
      <p class="text-muted text-sm" style="margin-top:8px">
        {{ automationRate }}% de los mensajes fueron respondidos automáticamente
        (sin intervención humana ni IA costosa)
      </p>
    </div>

    <!-- Real-time feed -->
    <div class="card" style="margin-top:var(--spacing-lg)" *ngIf="recentEvents.length > 0">
      <h3 style="margin-bottom:var(--spacing-md)">Actividad reciente</h3>
      <div class="event-list">
        <div class="event-item" *ngFor="let ev of recentEvents">
          <span class="event-icon">{{ ev.icon }}</span>
          <span class="event-text">{{ ev.text }}</span>
          <span class="event-time text-xs text-muted">{{ ev.time }}</span>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .metrics-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
      gap: var(--spacing-md);
      margin-bottom: var(--spacing-lg);
    }
    .metric-card {
      display: flex; flex-direction: column; align-items: center;
      text-align: center; padding: var(--spacing-lg) var(--spacing-md);
      transition: transform var(--transition-fast);
      &:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
      .metric-icon  { font-size: 36px; margin-bottom: var(--spacing-sm); }
      .metric-value { font-size: 36px; font-weight: 700; line-height: 1; }
      .metric-label { font-size: var(--font-size-sm); color: var(--color-text-secondary); margin-top: 6px; }
    }

    @media (max-width: 767px) {
      .metrics-grid { grid-template-columns: repeat(2, 1fr); gap: var(--spacing-sm); }
      .metric-card {
        padding: var(--spacing-md) var(--spacing-sm);
        &:hover { transform: none; }
        .metric-icon  { font-size: 28px; margin-bottom: 4px; }
        .metric-value { font-size: 26px; }
        .metric-label { font-size: var(--font-size-xs); }
      }
    }

    @media (max-width: 360px) {
      .metrics-grid { grid-template-columns: 1fr 1fr; }
    }

    .automation-card {
      h3 { font-size: var(--font-size-lg); font-weight: 700; margin-bottom: var(--spacing-md); }
    }
    .rate-bar-wrapper { display: flex; align-items: center; gap: var(--spacing-md); }
    .rate-bar {
      flex: 1; height: 10px; background: var(--color-bg-input);
      border-radius: var(--radius-full); overflow: hidden;
    }
    .rate-fill {
      height: 100%; background: var(--color-primary);
      border-radius: var(--radius-full);
      transition: width 1s ease;
    }
    .rate-value { font-size: var(--font-size-xl); font-weight: 700; color: var(--color-primary); min-width: 56px; }

    .event-list { display: flex; flex-direction: column; gap: var(--spacing-sm); }
    .event-item {
      display: flex; align-items: center; gap: var(--spacing-sm);
      padding: var(--spacing-sm); border-radius: var(--radius-md);
      background: var(--color-bg-input);
      .event-icon { font-size: 18px; width: 28px; text-align: center; }
      .event-text { flex: 1; font-size: var(--font-size-sm); }
      .event-time { white-space: nowrap; }
    }
  `]
})
export class DashboardHomeComponent implements OnInit, OnDestroy {
  loading = true;
  today = new Date().toLocaleDateString('es-CO', { weekday: 'long', day: 'numeric', month: 'long' });
  metricCards: MetricCard[] = [];
  automationRate: number | null = null;
  recentEvents: { icon: string; text: string; time: string }[] = [];

  private subs = new Subscription();

  constructor(private api: ApiService, private ws: WebSocketService) {}

  ngOnInit(): void {
    this.loadMetrics();

    // Escuchar mensajes en tiempo real para el feed de actividad
    this.subs.add(
      this.ws.onNewMessage().subscribe(ev => {
        const icon = ev.direction === 'INBOUND' ? '📨' : '📤';
        const processedLabel = ev.processedBy
          ? ` [${ev.processedBy}]` : '';
        this.recentEvents.unshift({
          icon,
          text: `${ev.direction === 'INBOUND' ? 'Mensaje de' : 'Respuesta a'} ${ev.displayName || ev.phone}${processedLabel}`,
          time: new Date(ev.sentAt).toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit' })
        });
        // Mantener solo los últimos 10 eventos
        if (this.recentEvents.length > 10) this.recentEvents.pop();
      })
    );
  }

  loadMetrics(): void {
    this.loading = true;
    this.api.getMetricsSummary().subscribe({
      next: (r) => {
        const d = r.data;
        const m = d?.metrics;
        const defaults = {
          totalMessagesIn: 0, totalMessagesOut: 0,
          totalAiCalls: 0, totalAiTokens: 0,
          totalFaqMatches: 0, totalKeywordMatches: 0,
          totalHumanTakeovers: 0, newContacts: 0,
          activeConversations: 0
        };
        const data = { ...defaults, ...(m || {}) };

        this.metricCards = [
          { icon: '📅', label: 'Reservas (Total)',      value: d?.totalReservations || 0,  color: 'var(--color-primary)' },
          { icon: '⏳', label: 'Reservas Pendientes',   value: d?.pendingReservations || 0, color: 'var(--color-warning)' },
          { icon: '✅', label: 'Reservas Confirmadas',  value: d?.confirmedReservations || 0, color: 'var(--color-success)' },
          { icon: '📨', label: 'Mensajes recibidos',    value: data.totalMessagesIn,       color: 'var(--color-info)' },
          { icon: '📤', label: 'Mensajes enviados',     value: data.totalMessagesOut,      color: 'var(--color-primary)' },
          { icon: '❓', label: 'FAQs respondidas',      value: data.totalFaqMatches,       color: 'var(--color-success)' },
          { icon: '🔑', label: 'Keywords activadas',    value: data.totalKeywordMatches,   color: 'var(--color-success)' },
          { icon: '🤖', label: 'Llamadas a IA',         value: data.totalAiCalls,          color: 'var(--color-warning)' },
          { icon: '👤', label: 'Transferencias humano', value: data.totalHumanTakeovers,   color: 'var(--color-error)' },
          { icon: '🆕', label: 'Nuevos contactos',      value: data.newContacts,           color: 'var(--color-info)' },
          { icon: '💬', label: 'Conversaciones activas',value: data.activeConversations,   color: 'var(--color-primary)' },
        ];

        // Calcular tasa de automatización: (FAQ + Keywords) / totalIn * 100
        const auto = data.totalFaqMatches + data.totalKeywordMatches;
        this.automationRate = data.totalMessagesIn > 0
          ? Math.round((auto / data.totalMessagesIn) * 100)
          : 0;

        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  ngOnDestroy(): void { this.subs.unsubscribe(); }
}
