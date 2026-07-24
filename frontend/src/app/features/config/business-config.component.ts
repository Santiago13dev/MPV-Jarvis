import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { BusinessConfig, BusinessHours } from '../../core/models/models';

@Component({
  selector: 'app-business-config',
  template: `
    <div class="page-header">
      <div><h1>Configuración</h1><p>Personaliza el comportamiento del bot y el negocio</p></div>
    </div>

    <div class="config-grid" *ngIf="config">

      <!-- Información del negocio -->
      <div class="card">
        <h3>🏢 Información del Negocio</h3>
        <div class="form-group">
          <label>Nombre del negocio *</label>
          <input class="form-control" [(ngModel)]="config.businessName" placeholder="Mi Negocio">
        </div>
        <div class="form-group">
          <label>Tipo de negocio</label>
          <input class="form-control" [(ngModel)]="config.businessType" placeholder="Restaurante, Tienda, Clínica...">
        </div>
        <div class="form-group">
          <label>Mensaje de bienvenida</label>
          <textarea class="form-control" [(ngModel)]="config.welcomeMessage" rows="3"
            placeholder="¡Hola! 👋 Bienvenido. Soy el asistente virtual. ¿En qué puedo ayudarte?"></textarea>
        </div>
        <div class="form-group">
          <label>Mensaje fuera de horario</label>
          <textarea class="form-control" [(ngModel)]="config.offHoursMessage" rows="3"
            placeholder="⏰ Estamos fuera de horario. Te responderemos pronto."></textarea>
        </div>
        <div class="form-group">
          <label>Segundos antes de transferir a humano</label>
          <input class="form-control" type="number" [(ngModel)]="config.humanDelaySeconds" min="0" max="3600">
        </div>
        <div class="form-group">
          <label>Teléfono del asesor (notificaciones)</label>
          <input class="form-control" [(ngModel)]="config.adminPhone" placeholder="573123197433">
          <small class="text-xs text-muted">Número al que se envía la alerta cuando un cliente necesita atención humana</small>
        </div>
        <div class="toggle-row">
          <div>
            <strong>Inteligencia Artificial</strong>
            <p class="text-sm text-muted">Activar IA para mensajes que no tengan FAQ ni keyword</p>
          </div>
          <label class="toggle-switch">
            <input type="checkbox" [(ngModel)]="config.aiEnabled">
            <span class="toggle-slider"></span>
          </label>
        </div>
        <div class="form-group" *ngIf="config.aiEnabled">
          <label>Tokens máximos por respuesta IA</label>
          <input class="form-control" type="number" [(ngModel)]="config.maxAiTokens" min="100" max="2000">
          <small class="text-xs text-muted">Más tokens = respuestas más largas y más costosas</small>
        </div>
        <button class="btn btn-primary" (click)="saveConfig()" [disabled]="savingConfig">
          <span *ngIf="savingConfig" class="spinner" style="width:16px;height:16px;border-width:2px"></span>
          💾 Guardar configuración
        </button>
        <div class="alert alert-success" *ngIf="savedConfig" style="margin-top:var(--spacing-sm)">
          ✅ Configuración guardada correctamente
        </div>
      </div>

      <!-- Horarios laborales -->
      <div class="card">
        <h3>🕐 Horarios Laborales</h3>
        <p class="text-sm text-muted" style="margin-bottom:var(--spacing-md)">
          Fuera de estos horarios el bot enviará el mensaje de "fuera de horario"
        </p>
        <div class="hours-list">
          <div class="hours-row" *ngFor="let h of hours">
            <div class="day-toggle">
              <label class="toggle-switch mini">
                <input type="checkbox" [(ngModel)]="h.isActive">
                <span class="toggle-slider"></span>
              </label>
              <span class="day-name" [class.day-active]="h.isActive">{{ dayNames[h.dayOfWeek] }}</span>
            </div>
            <div class="time-inputs" [class.disabled]="!h.isActive">
              <input class="form-control time-input" type="time" [(ngModel)]="h.openTime" [disabled]="!h.isActive">
              <span class="time-sep">—</span>
              <input class="form-control time-input" type="time" [(ngModel)]="h.closeTime" [disabled]="!h.isActive">
            </div>
          </div>
        </div>
        <button class="btn btn-primary" (click)="saveHours()" [disabled]="savingHours" style="margin-top:var(--spacing-md)">
          <span *ngIf="savingHours" class="spinner" style="width:16px;height:16px;border-width:2px"></span>
          💾 Guardar horarios
        </button>
        <div class="alert alert-success" *ngIf="savedHours" style="margin-top:var(--spacing-sm)">
          ✅ Horarios guardados
        </div>
      </div>

    </div>

    <div class="empty-state" *ngIf="!config">
      <div class="spinner" style="width:32px;height:32px;border-width:3px"></div>
      <p>Cargando configuración...</p>
    </div>
  `,
  styles: [`
    .config-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--spacing-lg);
      h3 { font-size: var(--font-size-lg); font-weight: 700; margin-bottom: var(--spacing-lg); }
      @media (max-width: 860px) { grid-template-columns: 1fr; }
    }

    @media (max-width: 767px) {
      .config-grid { gap: var(--spacing-md); }
      .config-grid .card { padding: var(--spacing-md); }
      .config-grid .btn { width: 100%; justify-content: center; }
    }

    .toggle-row {
      display: flex; align-items: center; justify-content: space-between;
      gap: var(--spacing-md);
      padding: var(--spacing-md) 0; border-top: 1px solid var(--color-border);
      border-bottom: 1px solid var(--color-border); margin: var(--spacing-md) 0;
    }

    /* Toggle switch */
    .toggle-switch {
      position: relative; display: inline-block; width: 46px; height: 24px; cursor: pointer;
      input { opacity: 0; width: 0; height: 0; }
      &.mini { width: 38px; height: 20px; }
    }
    .toggle-slider {
      position: absolute; inset: 0; background: var(--color-bg-input);
      border: 1px solid var(--color-border); border-radius: var(--radius-full);
      transition: all var(--transition-md);
      &::before {
        content: ''; position: absolute; width: 18px; height: 18px;
        left: 2px; top: 2px; background: var(--color-text-muted);
        border-radius: 50%; transition: all var(--transition-md);
      }
    }
    input:checked + .toggle-slider {
      background: rgba(37,211,102,0.2); border-color: var(--color-primary);
      &::before { transform: translateX(22px); background: var(--color-primary); }
    }
    .mini .toggle-slider::before { width: 14px; height: 14px; }
    .mini input:checked + .toggle-slider::before { transform: translateX(18px); }

    /* Hours */
    .hours-list { display: flex; flex-direction: column; gap: var(--spacing-sm); }
    .hours-row {
      display: flex; align-items: center; justify-content: space-between;
      gap: var(--spacing-sm);
      padding: 8px 0; border-bottom: 1px solid var(--color-border);
      &:last-child { border-bottom: none; }
    }
    .day-toggle { display: flex; align-items: center; gap: var(--spacing-sm); width: 110px; flex-shrink: 0; }
    .day-name { font-size: var(--font-size-sm); font-weight: 500; color: var(--color-text-muted);
      &.day-active { color: var(--color-text-primary); } }
    .time-inputs { display: flex; align-items: center; gap: var(--spacing-sm);
      &.disabled { opacity: 0.4; pointer-events: none; } }
    .time-input { width: 100px; padding: 6px 10px; font-size: var(--font-size-sm); }
    .time-sep { color: var(--color-text-muted); }

    @media (max-width: 420px) {
      .hours-row { flex-wrap: wrap; }
      .day-toggle { width: auto; }
      .time-inputs { width: 100%; justify-content: flex-end; }
      .time-input { width: 90px; min-height: 36px; padding: 5px 8px; }
    }

    @media (max-width: 340px) {
      .time-inputs { justify-content: space-between; }
      .time-input { flex: 1; width: auto; }
    }
  `]
})
export class BusinessConfigComponent implements OnInit {
  config: BusinessConfig | null = null;
  hours: BusinessHours[] = [];
  savingConfig = false;
  savingHours  = false;
  savedConfig  = false;
  savedHours   = false;

  dayNames = ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado'];

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.api.getBusinessConfig().subscribe(r => { if (r.data) this.config = r.data; });
    this.api.getBusinessHours().subscribe(r => {
      this.hours = (r.data || []).sort((a, b) => a.dayOfWeek - b.dayOfWeek);
    });
  }

  saveConfig(): void {
    if (!this.config) return;
    this.savingConfig = true;
    this.api.updateBusinessConfig(this.config).subscribe({
      next: () => {
        this.savingConfig = false;
        this.savedConfig = true;
        setTimeout(() => this.savedConfig = false, 3000);
      },
      error: () => { this.savingConfig = false; }
    });
  }

  saveHours(): void {
    this.savingHours = true;
    this.api.updateBusinessHours(this.hours).subscribe({
      next: () => {
        this.savingHours = false;
        this.savedHours = true;
        setTimeout(() => this.savedHours = false, 3000);
      },
      error: () => { this.savingHours = false; }
    });
  }
}
