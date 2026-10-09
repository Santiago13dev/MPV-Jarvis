import { Component, OnDestroy, OnInit } from '@angular/core';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { ApiService } from '../../core/services/api.service';
import {
  CapacityRule, Reservation, ReservationBlock, ReservationFilter, ReservationReceipt,
  ReservationSettings, ReservationStats, ReservationStatus, ReservationType, SlotAvailability
} from '../../core/models/models';

type Tab = 'reservations' | 'availability' | 'settings';

interface ReceiptView {
  receipt: ReservationReceipt;
  isImage: boolean;
  url: string;
  safeUrl: SafeUrl;
}

interface EditForm {
  r: Reservation;
  date: string;
  time: string;
  partySize: number;
  type: ReservationType;
  slots: string[];
  busy: boolean;
}

interface CreateForm {
  customerName: string;
  phoneNumber: string;
  receiptPhone: string;
  type: ReservationType;
  date: string;
  time: string;
  partySize: number;
  notes: string;
  status: ReservationStatus;
  slots: string[];
  busy: boolean;
  key: string;
}

/**
 * Gestión de reservas. NO contiene reglas de negocio: precios, cupos, días y horarios los decide el backend
 * (ReservationService). Esta pantalla solo muestra datos y llama a la API.
 */
@Component({
  selector: 'app-reservations',
  template: `
    <div class="page-header">
      <div>
        <h1>Reservas</h1>
        <p>Gestión de reservas, pagos y disponibilidad</p>
      </div>
      <div class="flex gap-sm">
        <button class="btn btn-secondary" (click)="refreshAll()">🔄 Actualizar</button>
        <button class="btn btn-primary" (click)="openCreate()">➕ Nueva reserva</button>
      </div>
    </div>

    <div class="notice" *ngIf="notice" [class.notice-error]="notice.type === 'error'">{{ notice.text }}</div>

    <!-- ── Indicadores ─────────────────────────────────────────── -->
    <div class="stats" *ngIf="stats">
      <div class="stat"><span class="stat-n">{{ stats.reservationsToday }}</span><span class="stat-l">Reservas de hoy</span></div>
      <div class="stat"><span class="stat-n">{{ stats.peopleToday }}</span><span class="stat-l">Personas hoy</span></div>
      <div class="stat"><span class="stat-n">{{ stats.confirmedRevenueFromToday | currency:'COP':'symbol-narrow':'1.0-0' }}</span><span class="stat-l">Ingresos confirmados (desde hoy)</span></div>
      <div class="stat" [class.stat-alert]="stats.pendingPayments > 0"><span class="stat-n">{{ stats.pendingPayments }}</span><span class="stat-l">Pagos pendientes</span></div>
      <div class="stat"><span class="stat-n">{{ stats.vipToday }}</span><span class="stat-l">Reservas VIP hoy</span></div>
      <div class="stat"><span class="stat-n">{{ stats.remainingCapacityToday }}</span><span class="stat-l">Cupos restantes hoy</span></div>
    </div>

    <div class="tabs">
      <button [class.active]="tab === 'reservations'" (click)="setTab('reservations')">📋 Reservas</button>
      <button [class.active]="tab === 'availability'" (click)="setTab('availability')">📊 Disponibilidad</button>
      <button [class.active]="tab === 'settings'" (click)="setTab('settings')">⚙️ Configuración</button>
    </div>

    <!-- ══════════════ TAB: RESERVAS ══════════════ -->
    <div *ngIf="tab === 'reservations'">
      <div class="card filters">
        <label>Desde <input type="date" [(ngModel)]="filter.from" (change)="load()"></label>
        <label>Hasta <input type="date" [(ngModel)]="filter.to" (change)="load()"></label>
        <label>Estado
          <select [(ngModel)]="statusFilter" (change)="load()">
            <option value="">Todos</option>
            <option *ngFor="let s of statuses" [value]="s">{{ statusLabels[s] }}</option>
          </select>
        </label>
        <label>Tipo
          <select [(ngModel)]="typeFilter" (change)="load()">
            <option value="">Todos</option>
            <option value="NORMAL">Normal</option>
            <option value="VIP">VIP</option>
          </select>
        </label>
        <label class="grow">Buscar
          <input type="search" placeholder="Nombre, teléfono o número" [(ngModel)]="query" (keyup.enter)="load()">
        </label>
        <button class="btn btn-secondary" (click)="load()">Buscar</button>
        <button class="btn btn-secondary" (click)="clearFilters()">Limpiar</button>
        <button class="btn btn-secondary" (click)="showToReview()">🧾 Por revisar</button>
      </div>

      <div class="card">
        <div class="table-container" *ngIf="reservations.length > 0; else emptyTpl">
          <table>
            <thead>
              <tr>
                <th>N°</th><th>Cliente</th><th>Fecha y hora</th><th>Personas</th>
                <th>Tipo</th><th>Total</th><th>Estado</th><th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let r of reservations; trackBy: trackById">
                <td data-label="N°">#{{ r.reservationNumber }}</td>
                <td data-label="Cliente"><strong>{{ r.customerName }}</strong><div class="sub">{{ r.phoneNumber }}</div></td>
                <td data-label="Fecha y hora">{{ fmtDate(r.date) }}<div class="sub">{{ fmtTime(r.time) }}</div></td>
                <td data-label="Personas">{{ r.partySize }}</td>
                <td data-label="Tipo"><span class="chip" [class.chip-vip]="r.reservationType === 'VIP'">{{ r.reservationType === 'VIP' ? '⭐ VIP' : 'Normal' }}</span></td>
                <td data-label="Total">{{ r.totalPrice | currency:'COP':'symbol-narrow':'1.0-0' }}</td>
                <td data-label="Estado">
                  <span class="status" [ngClass]="'st-' + r.status">{{ statusLabels[r.status] }}</span>
                  <div class="sub" *ngIf="r.holdExpiresAt && (r.status === 'PAYMENT_PENDING' || r.status === 'PENDING')">
                    vence {{ r.holdExpiresAt | date:'d MMM, h:mm a' }}
                  </div>
                  <div class="sub" *ngIf="r.cancelReason && (r.status === 'CANCELLED' || r.status === 'EXPIRED')">{{ r.cancelReason }}</div>
                </td>
                <td data-label="Acciones">
                  <div class="actions">
                    <button class="btn btn-sm btn-secondary" *ngIf="r.hasReceipt" (click)="openReceipts(r)">🧾 Comprobante</button>
                    <button class="btn btn-sm btn-primary" *ngIf="canAct(r)" (click)="confirm(r)">✅ Confirmar pago</button>
                    <button class="btn btn-sm btn-secondary" *ngIf="r.status === 'PAYMENT_RECEIPT_SENT'" (click)="reject(r)">↩ Rechazar</button>
                    <button class="btn btn-sm btn-secondary" *ngIf="canAct(r) || r.status === 'CONFIRMED'" (click)="openEdit(r)">✏️ Modificar</button>
                    <label class="btn btn-sm btn-secondary file-btn" *ngIf="canAct(r)">📎 Subir
                      <input type="file" hidden accept="image/*,application/pdf" (change)="upload(r, $event)">
                    </label>
                    <button class="btn btn-sm btn-danger" *ngIf="canAct(r) || r.status === 'CONFIRMED'" (click)="cancel(r)">❌ Cancelar</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="sub total-count" *ngIf="reservations.length > 0">Mostrando {{ reservations.length }} reserva(s)</div>
      </div>
    </div>

    <ng-template #emptyTpl>
      <div class="empty-state">
        <div class="empty-icon">📅</div>
        <h3>No hay reservas</h3>
        <p>No hay reservas que coincidan con los filtros.</p>
      </div>
    </ng-template>

    <!-- ══════════════ TAB: DISPONIBILIDAD ══════════════ -->
    <div *ngIf="tab === 'availability'">
      <div class="card filters">
        <label>Fecha <input type="date" [(ngModel)]="availDate" (change)="loadAvailability()"></label>
        <button class="btn btn-secondary" (click)="loadAvailability()">Consultar</button>
        <button class="btn btn-danger" *ngIf="availDate" (click)="blockDay()">🚫 Bloquear día completo</button>
      </div>

      <div class="card">
        <div *ngIf="daySlots.length === 0" class="empty-state">
          <div class="empty-icon">🗓️</div>
          <h3>Sin servicio ese día</h3>
          <p>No hay franjas configuradas para la fecha elegida (el restaurante atiende solo los días habilitados).</p>
        </div>
        <div class="slot" *ngFor="let s of daySlots">
          <div class="slot-time">{{ fmtTime(s.time) }}</div>
          <div class="slot-bar">
            <div class="bar"><div class="fill" [ngClass]="barClass(s)" [style.width.%]="pct(s)"></div></div>
            <div class="sub">{{ s.normal.reserved }} / {{ s.normal.capacity }} personas · quedan {{ s.normal.available }} ({{ pct(s) }}%)</div>
          </div>
          <span class="vip-state" [ngClass]="vipClass(s)">{{ vipLabel(s) }}</span>
          <button class="btn btn-sm btn-secondary" *ngIf="!s.blocked && !s.passed" (click)="blockSlot(s)">🚫 Bloquear</button>
          <button class="btn btn-sm btn-secondary" *ngIf="s.blocked" (click)="unblockSlot(s)">🔓 Desbloquear</button>
        </div>
      </div>

      <div class="card" *ngIf="blocks.length > 0">
        <h3>Bloqueos activos</h3>
        <div class="rule" *ngFor="let b of blocks">
          <span>{{ fmtDate(b.date) }} · {{ b.slotTime ? fmtTime(b.slotTime) : 'Día completo' }}<span class="sub" *ngIf="b.reason"> — {{ b.reason }}</span></span>
          <button class="btn btn-sm btn-danger" (click)="deleteBlock(b)">Quitar</button>
        </div>
      </div>
    </div>

    <!-- ══════════════ TAB: CONFIGURACIÓN ══════════════ -->
    <div *ngIf="tab === 'settings'">
      <div class="card" *ngIf="cfg as c">
        <h3>Reglas de reserva</h3>
        <div class="grid">
          <label>Precio por persona (normal)<input type="number" min="0" [(ngModel)]="c.normalPrice"></label>
          <label>Precio VIP (espacio completo)<input type="number" min="0" [(ngModel)]="c.vipPrice"></label>
          <label>Capacidad VIP (personas)<input type="number" min="1" [(ngModel)]="c.vipCapacity"></label>
          <label>Capacidad por franja (por defecto)<input type="number" min="1" [(ngModel)]="c.defaultSlotCapacity"></label>
          <label>Máx. personas por reserva<input type="number" min="1" [(ngModel)]="c.maxPartySize"></label>
          <label>Inicio de reservas<input type="time" [(ngModel)]="c.reservationStartTime"></label>
          <label>Fin de reservas<input type="time" [(ngModel)]="c.reservationEndTime"></label>
          <label>Intervalo entre franjas (min)<input type="number" min="5" step="5" [(ngModel)]="c.slotIntervalMinutes"></label>
          <label>Vigencia de reserva sin pago (min)<input type="number" min="1" [(ngModel)]="c.holdMinutes"></label>
        </div>
        <div class="days">
          <span>Días de atención:</span>
          <label class="check" *ngFor="let d of days">
            <input type="checkbox" [checked]="c.operatingDays.includes(d.key)" (change)="toggleDay(c, d.key)"> {{ d.label }}
          </label>
        </div>
        <label class="check"><input type="checkbox" [(ngModel)]="c.vipConsumesNormalCapacity"> La reserva VIP descuenta cupos de las reservas normales</label>
        <label class="block">Instrucciones de pago (se envían al cliente)
          <textarea rows="4" [(ngModel)]="c.paymentInstructions"></textarea>
        </label>
        <button class="btn btn-primary" (click)="saveSettings(c)">💾 Guardar configuración</button>
      </div>

      <div class="card">
        <h3>Capacidad por día y franja</h3>
        <p class="sub">La regla más específica gana (día + hora &gt; solo hora &gt; solo día &gt; capacidad por defecto).</p>
        <div class="rule" *ngFor="let r of rules">
          <span>{{ dayLabel(r.dayOfWeek) }} · {{ r.slotTime ? fmtTime(r.slotTime) : 'Todas las franjas' }} → <strong>{{ r.capacity }}</strong> personas</span>
          <button class="btn btn-sm btn-danger" (click)="deleteRule(r)">Quitar</button>
        </div>
        <div class="rule-add">
          <select [(ngModel)]="ruleForm.dayOfWeek">
            <option value="">Todos los días</option>
            <option *ngFor="let d of days" [value]="d.key">{{ d.label }}</option>
          </select>
          <input type="time" [(ngModel)]="ruleForm.slotTime">
          <input type="number" min="0" placeholder="Capacidad" [(ngModel)]="ruleForm.capacity">
          <button class="btn btn-secondary" (click)="addRule()">➕ Agregar regla</button>
        </div>
      </div>
    </div>

    <!-- ══════════════ MODAL: NUEVA RESERVA ══════════════ -->
    <div class="overlay" *ngIf="create as f" (click)="closeCreate()">
      <div class="modal" (click)="$event.stopPropagation()">
        <h3>Nueva reserva manual</h3>
        <p class="sub">Usa el mismo motor que WhatsApp: valida día, cupos y VIP, y calcula el total.</p>
        <div class="grid">
          <label>Nombre<input type="text" [(ngModel)]="f.customerName"></label>
          <label>Teléfono<input type="tel" placeholder="3001234567" [(ngModel)]="f.phoneNumber"></label>
          <label>Teléfono para comprobante (opcional)<input type="tel" [(ngModel)]="f.receiptPhone"></label>
          <label>Tipo
            <select [(ngModel)]="f.type"><option value="NORMAL">Normal</option><option value="VIP">VIP</option></select>
          </label>
          <label>Fecha<input type="date" [(ngModel)]="f.date"></label>
          <label>Personas<input type="number" min="1" [(ngModel)]="f.partySize"></label>
          <label>Hora<input type="time" [(ngModel)]="f.time"></label>
          <label>Estado inicial
            <select [(ngModel)]="f.status">
              <option value="PAYMENT_PENDING">Pago pendiente</option>
              <option value="CONFIRMED">Confirmada</option>
            </select>
          </label>
        </div>
        <button class="btn btn-sm btn-secondary" (click)="loadSlots('create')">🔍 Ver horarios disponibles</button>
        <div class="chips" *ngIf="f.slots.length > 0">
          <button class="chip-btn" *ngFor="let s of f.slots" [class.sel]="f.time === s" (click)="f.time = s">{{ fmtTime(s) }}</button>
        </div>
        <label class="block">Notas<textarea rows="2" [(ngModel)]="f.notes"></textarea></label>
        <div class="modal-actions">
          <button class="btn btn-secondary" (click)="closeCreate()">Cerrar</button>
          <button class="btn btn-primary" [disabled]="f.busy" (click)="submitCreate(f)">Crear reserva</button>
        </div>
      </div>
    </div>

    <!-- ══════════════ MODAL: MODIFICAR ══════════════ -->
    <div class="overlay" *ngIf="edit as e" (click)="closeEdit()">
      <div class="modal" (click)="$event.stopPropagation()">
        <h3>Modificar reserva #{{ e.r.reservationNumber }}</h3>
        <p class="sub">{{ e.r.customerName }} · actualmente {{ fmtDate(e.r.date) }} {{ fmtTime(e.r.time) }} · {{ e.r.partySize }} personas</p>
        <div class="grid">
          <label>Fecha<input type="date" [(ngModel)]="e.date"></label>
          <label>Hora<input type="time" [(ngModel)]="e.time"></label>
          <label>Personas<input type="number" min="1" [(ngModel)]="e.partySize"></label>
          <label>Tipo
            <select [(ngModel)]="e.type"><option value="NORMAL">Normal</option><option value="VIP">VIP</option></select>
          </label>
        </div>
        <button class="btn btn-sm btn-secondary" (click)="loadSlots('edit')">🔍 Ver horarios disponibles</button>
        <div class="chips" *ngIf="e.slots.length > 0">
          <button class="chip-btn" *ngFor="let s of e.slots" [class.sel]="e.time === s" (click)="e.time = s">{{ fmtTime(s) }}</button>
        </div>
        <p class="sub">El backend revalida cupos antes de aplicar el cambio y recalcula el precio.</p>
        <div class="modal-actions">
          <button class="btn btn-secondary" (click)="closeEdit()">Cerrar</button>
          <button class="btn btn-primary" [disabled]="e.busy" (click)="submitEdit(e)">Guardar cambios</button>
        </div>
      </div>
    </div>

    <!-- ══════════════ MODAL: COMPROBANTES ══════════════ -->
    <div class="overlay" *ngIf="receiptFor as rf" (click)="closeReceipts()">
      <div class="modal modal-wide" (click)="$event.stopPropagation()">
        <h3>Comprobantes · reserva #{{ rf.reservationNumber }}</h3>
        <p class="sub">{{ rf.customerName }} · total {{ rf.totalPrice | currency:'COP':'symbol-narrow':'1.0-0' }}</p>
        <div *ngIf="receiptViews.length === 0" class="sub">Cargando comprobantes…</div>
        <div class="receipt" *ngFor="let v of receiptViews">
          <div class="sub">
            {{ v.receipt.uploadedAt | date:'d MMM, h:mm a' }} · {{ v.receipt.status }}
            <span *ngIf="v.receipt.reviewedBy"> · revisó {{ v.receipt.reviewedBy }}</span>
            <span *ngIf="v.receipt.reviewNote"> · {{ v.receipt.reviewNote }}</span>
          </div>
          <img *ngIf="v.isImage" [src]="v.safeUrl" alt="Comprobante de pago">
          <a *ngIf="!v.isImage" class="btn btn-sm btn-secondary" [href]="v.safeUrl" target="_blank" rel="noopener">📄 Abrir PDF</a>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" (click)="closeReceipts()">Cerrar</button>
          <button class="btn btn-secondary" *ngIf="rf.status === 'PAYMENT_RECEIPT_SENT'" (click)="reject(rf); closeReceipts()">↩ Rechazar</button>
          <button class="btn btn-primary" *ngIf="canAct(rf)" (click)="confirm(rf); closeReceipts()">✅ Confirmar pago</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .flex { display: flex; } .gap-sm { gap: 8px; }
    .sub { font-size: 12px; opacity: .7; margin-top: 2px; }
    .notice { padding: 10px 14px; border-radius: 8px; margin-bottom: 12px; background: rgba(34,197,94,.12); border: 1px solid #22c55e; }
    .notice-error { background: rgba(239,68,68,.12); border-color: #ef4444; }

    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-bottom: 16px; }
    .stat { background: var(--color-bg-card, #fff); border: 1px solid var(--color-border, #e5e7eb); border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; }
    .stat-n { font-size: 22px; font-weight: 700; }
    .stat-l { font-size: 12px; opacity: .7; }
    .stat-alert { border-color: #f59e0b; background: rgba(245,158,11,.08); }

    .tabs { display: flex; gap: 6px; margin-bottom: 12px; flex-wrap: wrap; }
    .tabs button { padding: 8px 14px; border-radius: 8px; border: 1px solid var(--color-border, #e5e7eb); background: transparent; cursor: pointer; color: inherit; }
    .tabs button.active { background: var(--color-primary, #25d366); color: #fff; border-color: transparent; }

    .filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-end; margin-bottom: 12px; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; font-weight: 600; }
    label.grow { flex: 1; min-width: 180px; }
    label.block { margin: 12px 0; }
    label.check { flex-direction: row; align-items: center; gap: 6px; font-weight: 500; font-size: 13px; }
    input, select, textarea { padding: 8px 10px; border-radius: 8px; border: 1px solid var(--color-border, #d1d5db); font: inherit; background: var(--color-bg-card, #fff); color: inherit; }

    .table-container { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 10px 8px; text-align: left; border-bottom: 1px solid var(--color-border, #e5e7eb); vertical-align: top; font-size: 13px; }
    .actions { display: flex; flex-wrap: wrap; gap: 6px; }
    .file-btn { cursor: pointer; }
    .total-count { padding: 8px; }

    .chip { padding: 2px 8px; border-radius: 999px; background: rgba(107,114,128,.15); font-size: 12px; }
    .chip-vip { background: rgba(234,179,8,.2); }
    .status { padding: 3px 9px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
    .st-PENDING, .st-PAYMENT_PENDING { background: rgba(245,158,11,.18); color: #b45309; }
    .st-PAYMENT_RECEIPT_SENT { background: rgba(59,130,246,.18); color: #1d4ed8; }
    .st-CONFIRMED { background: rgba(34,197,94,.18); color: #15803d; }
    .st-CANCELLED { background: rgba(239,68,68,.15); color: #b91c1c; }
    .st-EXPIRED { background: rgba(107,114,128,.2); color: #4b5563; }

    .slot { display: grid; grid-template-columns: 80px 1fr auto auto; gap: 12px; align-items: center; padding: 10px 4px; border-bottom: 1px solid var(--color-border, #e5e7eb); }
    .slot-time { font-weight: 700; }
    .bar { height: 12px; border-radius: 6px; background: rgba(107,114,128,.18); overflow: hidden; }
    .fill { height: 100%; border-radius: 6px; }
    .fill.low { background: #22c55e; } .fill.mid { background: #f59e0b; } .fill.high { background: #ef4444; } .fill.off { background: #9ca3af; }
    .vip-state { font-size: 12px; padding: 3px 9px; border-radius: 999px; white-space: nowrap; }
    .vip-free { background: rgba(34,197,94,.18); color: #15803d; }
    .vip-busy { background: rgba(239,68,68,.15); color: #b91c1c; }
    .vip-off { background: rgba(107,114,128,.2); color: #4b5563; }

    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; margin-bottom: 12px; }
    .days { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin: 8px 0 12px; }
    .rule { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 8px 0; border-bottom: 1px dashed var(--color-border, #e5e7eb); }
    .rule-add { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }

    .overlay { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 12px; }
    .modal { background: var(--color-bg-card, #fff); color: inherit; border-radius: 14px; padding: 20px; width: 100%; max-width: 640px; max-height: 92vh; overflow-y: auto; }
    .modal-wide { max-width: 760px; }
    .modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
    .chip-btn { padding: 6px 12px; border-radius: 999px; border: 1px solid var(--color-border, #d1d5db); background: transparent; cursor: pointer; color: inherit; }
    .chip-btn.sel { background: var(--color-primary, #25d366); color: #fff; border-color: transparent; }
    .receipt { margin: 12px 0; }
    .receipt img { max-width: 100%; max-height: 60vh; border-radius: 8px; border: 1px solid var(--color-border, #e5e7eb); margin-top: 6px; }

    @media (max-width: 640px) {
      .table-container { overflow: visible; }
      table, thead, tbody, tr, td { display: block; width: 100%; }
      thead { display: none; }
      tr { border: 1px solid var(--color-border, #e5e7eb); border-radius: 12px; padding: 8px 12px; margin-bottom: 10px; }
      td { display: flex; justify-content: space-between; gap: 12px; border-bottom: 1px dashed var(--color-border, #e5e7eb); text-align: right; }
      td::before { content: attr(data-label); font-size: 11px; font-weight: 700; text-transform: uppercase; opacity: .65; text-align: left; flex-shrink: 0; }
      td:last-child { border-bottom: none; flex-direction: column; align-items: stretch; }
      .actions { justify-content: flex-end; }
      .slot { grid-template-columns: 64px 1fr; }
    }
  `]
})
export class ReservationsComponent implements OnInit, OnDestroy {
  tab: Tab = 'reservations';
  reservations: Reservation[] = [];
  stats: ReservationStats | null = null;
  notice: { type: 'ok' | 'error'; text: string } | null = null;
  private noticeTimer: ReturnType<typeof setTimeout> | null = null;

  readonly statuses: ReservationStatus[] =
    ['PAYMENT_PENDING', 'PAYMENT_RECEIPT_SENT', 'PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'];
  readonly statusLabels: Record<ReservationStatus, string> = {
    PENDING: 'Pendiente',
    PAYMENT_PENDING: 'Pago pendiente',
    PAYMENT_RECEIPT_SENT: 'Comprobante enviado',
    CONFIRMED: 'Confirmada',
    CANCELLED: 'Cancelada',
    EXPIRED: 'Expirada'
  };
  readonly days = [
    { key: 'MONDAY', label: 'Lunes' }, { key: 'TUESDAY', label: 'Martes' },
    { key: 'WEDNESDAY', label: 'Miércoles' }, { key: 'THURSDAY', label: 'Jueves' },
    { key: 'FRIDAY', label: 'Viernes' }, { key: 'SATURDAY', label: 'Sábado' },
    { key: 'SUNDAY', label: 'Domingo' }
  ];

  // Filtros
  filter: ReservationFilter = { from: this.localIso(new Date()) };
  statusFilter: ReservationStatus | '' = '';
  typeFilter: ReservationType | '' = '';
  query = '';

  // Disponibilidad
  availDate = this.localIso(new Date());
  daySlots: SlotAvailability[] = [];
  blocks: ReservationBlock[] = [];

  // Configuración
  cfg: ReservationSettings | null = null;
  rules: CapacityRule[] = [];
  ruleForm: { dayOfWeek: string; slotTime: string; capacity: number | null } =
    { dayOfWeek: '', slotTime: '', capacity: null };

  // Modales
  create: CreateForm | null = null;
  edit: EditForm | null = null;
  receiptFor: Reservation | null = null;
  receiptViews: ReceiptView[] = [];

  constructor(private api: ApiService, private sanitizer: DomSanitizer) {}

  ngOnInit(): void {
    this.refreshAll();
  }

  ngOnDestroy(): void {
    this.revokeReceiptUrls();
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
  }

  // ── Carga ─────────────────────────────────────────────────────────────

  refreshAll(): void {
    this.load();
    this.loadStats();
    if (this.tab === 'availability') this.loadAvailability();
    if (this.tab === 'settings') this.loadSettings();
  }

  load(): void {
    const f: ReservationFilter = {
      from: this.filter.from || undefined,
      to: this.filter.to || undefined,
      status: this.statusFilter ? [this.statusFilter] : [],
      type: this.typeFilter,
      q: this.query
    };
    this.api.searchReservations(f).subscribe({
      next: res => this.reservations = res.data || [],
      error: e => this.fail(e)
    });
  }

  loadStats(): void {
    this.api.getReservationStats().subscribe({
      next: res => this.stats = res.data,
      error: () => this.stats = null
    });
  }

  setTab(t: Tab): void {
    this.tab = t;
    if (t === 'availability') this.loadAvailability();
    if (t === 'settings') this.loadSettings();
  }

  clearFilters(): void {
    this.filter = {};
    this.statusFilter = '';
    this.typeFilter = '';
    this.query = '';
    this.load();
  }

  showToReview(): void {
    this.filter = {};
    this.typeFilter = '';
    this.query = '';
    this.statusFilter = 'PAYMENT_RECEIPT_SENT';
    this.load();
  }

  // ── Acciones sobre reservas ───────────────────────────────────────────

  canAct(r: Reservation): boolean {
    return r.status === 'PENDING' || r.status === 'PAYMENT_PENDING' || r.status === 'PAYMENT_RECEIPT_SENT';
  }

  confirm(r: Reservation): void {
    const warn = r.status !== 'PAYMENT_RECEIPT_SENT' && !r.hasReceipt
      ? '\n\nATENCIÓN: esta reserva no tiene comprobante registrado.' : '';
    if (!window.confirm('¿Confirmar el pago de la reserva #' + r.reservationNumber + ' de ' + r.customerName + '?' + warn)) return;
    this.api.confirmReservation(r.id).subscribe({
      next: () => this.done('Reserva #' + r.reservationNumber + ' confirmada'),
      error: e => this.fail(e)
    });
  }

  reject(r: Reservation): void {
    const note = window.prompt('Motivo del rechazo (se le informa al cliente):', '');
    if (note === null) return;
    this.api.rejectReceipt(r.id, note).subscribe({
      next: () => this.done('Comprobante rechazado'),
      error: e => this.fail(e)
    });
  }

  cancel(r: Reservation): void {
    const reason = window.prompt('¿Cancelar la reserva #' + r.reservationNumber + '? Motivo (opcional):', '');
    if (reason === null) return;
    this.api.cancelReservation(r.id, reason).subscribe({
      next: () => this.done('Reserva cancelada; el cupo quedó libre'),
      error: e => this.fail(e)
    });
  }

  upload(r: Reservation, ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const file = input.files && input.files.length ? input.files[0] : null;
    if (!file) return;
    this.api.uploadReceipt(r.id, file).subscribe({
      next: () => { input.value = ''; this.done('Comprobante registrado'); },
      error: e => { input.value = ''; this.fail(e); }
    });
  }

  // ── Comprobantes ──────────────────────────────────────────────────────

  openReceipts(r: Reservation): void {
    this.revokeReceiptUrls();
    this.receiptFor = r;
    this.receiptViews = [];
    this.api.getReceipts(r.id).subscribe({
      next: res => {
        (res.data || []).forEach(rc => {
          this.api.getReceiptFile(r.id, rc.id).subscribe({
            next: blob => {
              const url = URL.createObjectURL(blob);
              this.receiptViews.push({
                receipt: rc,
                isImage: (rc.mimeType || '').startsWith('image/'),
                url,
                safeUrl: this.sanitizer.bypassSecurityTrustUrl(url)
              });
            },
            error: e => this.fail(e)
          });
        });
      },
      error: e => this.fail(e)
    });
  }

  closeReceipts(): void {
    this.revokeReceiptUrls();
    this.receiptFor = null;
  }

  private revokeReceiptUrls(): void {
    this.receiptViews.forEach(v => URL.revokeObjectURL(v.url));
    this.receiptViews = [];
  }

  // ── Crear manual ──────────────────────────────────────────────────────

  openCreate(): void {
    this.create = {
      customerName: '', phoneNumber: '', receiptPhone: '', type: 'NORMAL',
      date: '', time: '', partySize: 2, notes: '', status: 'PAYMENT_PENDING',
      slots: [], busy: false, key: this.newKey()
    };
  }

  closeCreate(): void {
    this.create = null;
  }

  submitCreate(f: CreateForm): void {
    if (!f.customerName.trim() || !f.phoneNumber.trim() || !f.date || !f.time || !f.partySize) {
      this.error('Completa nombre, teléfono, fecha, hora y personas.');
      return;
    }
    f.busy = true;
    this.api.createReservation({
      customerName: f.customerName.trim(),
      phoneNumber: f.phoneNumber.trim(),
      receiptPhone: f.receiptPhone.trim() || undefined,
      reservationType: f.type,
      date: f.date,
      time: this.hhmm(f.time),
      partySize: Number(f.partySize),
      notes: f.notes.trim() || undefined,
      status: f.status
    }, f.key).subscribe({
      next: res => {
        this.create = null;
        this.done('Reserva #' + res.data.reservationNumber + ' creada');
      },
      error: e => { f.busy = false; this.fail(e); }
    });
  }

  // ── Modificar ─────────────────────────────────────────────────────────

  openEdit(r: Reservation): void {
    this.edit = {
      r, date: r.date, time: this.hhmm(r.time), partySize: r.partySize,
      type: r.reservationType, slots: [], busy: false
    };
  }

  closeEdit(): void {
    this.edit = null;
  }

  submitEdit(e: EditForm): void {
    const patch: { date?: string; time?: string; partySize?: number; reservationType?: ReservationType } = {};
    if (e.date && e.date !== e.r.date) patch.date = e.date;
    if (e.time && this.hhmm(e.time) !== this.hhmm(e.r.time)) patch.time = this.hhmm(e.time);
    if (Number(e.partySize) !== e.r.partySize) patch.partySize = Number(e.partySize);
    if (e.type !== e.r.reservationType) patch.reservationType = e.type;
    if (Object.keys(patch).length === 0) {
      this.error('No hay cambios para guardar.');
      return;
    }
    e.busy = true;
    this.api.updateReservation(e.r.id, patch).subscribe({
      next: () => { this.edit = null; this.done('Reserva #' + e.r.reservationNumber + ' actualizada'); },
      error: err => { e.busy = false; this.fail(err); }
    });
  }

  /** Horarios con cupo real según el backend. */
  loadSlots(target: 'create' | 'edit'): void {
    const f = target === 'create' ? this.create : this.edit;
    if (!f) return;
    const date = target === 'create' ? this.create!.date : this.edit!.date;
    const party = Number(target === 'create' ? this.create!.partySize : this.edit!.partySize);
    const type = target === 'create' ? this.create!.type : this.edit!.type;
    if (!date || !party) {
      this.error('Elige fecha y número de personas primero.');
      return;
    }
    this.api.getAvailableSlots(date, type, party).subscribe({
      next: res => {
        const slots = (res.data || []).map(t => this.hhmm(t));
        f.slots = slots;
        if (slots.length === 0) this.error('No hay horarios disponibles para esos datos.');
      },
      error: e => this.fail(e)
    });
  }

  // ── Disponibilidad y bloqueos ─────────────────────────────────────────

  loadAvailability(): void {
    if (!this.availDate) return;
    this.api.getDayAvailability(this.availDate).subscribe({
      next: res => this.daySlots = res.data || [],
      error: e => { this.daySlots = []; this.fail(e); }
    });
    this.api.getReservationBlocks(this.localIso(new Date())).subscribe({
      next: res => this.blocks = res.data || [],
      error: () => this.blocks = []
    });
  }

  pct(s: SlotAvailability): number {
    if (s.blocked || s.normal.capacity <= 0) return 0;
    return Math.min(100, Math.round((s.normal.reserved * 100) / s.normal.capacity));
  }

  barClass(s: SlotAvailability): string {
    if (s.blocked || s.passed) return 'off';
    const p = this.pct(s);
    return p >= 90 ? 'high' : p >= 60 ? 'mid' : 'low';
  }

  vipLabel(s: SlotAvailability): string {
    if (s.blocked) return 'Bloqueado';
    return s.vip.available > 0 ? 'VIP disponible' : 'VIP ocupado';
  }

  vipClass(s: SlotAvailability): string {
    if (s.blocked) return 'vip-off';
    return s.vip.available > 0 ? 'vip-free' : 'vip-busy';
  }

  blockSlot(s: SlotAvailability): void {
    const reason = window.prompt('Motivo del bloqueo de ' + this.fmtTime(s.time) + ' (opcional):', '');
    if (reason === null) return;
    this.api.addReservationBlock({ date: this.availDate, slotTime: this.hhmm(s.time), reason: reason || null }).subscribe({
      next: () => { this.notify('Franja bloqueada'); this.loadAvailability(); },
      error: e => this.fail(e)
    });
  }

  blockDay(): void {
    const reason = window.prompt('Bloquear TODO el ' + this.fmtDate(this.availDate) + '. Motivo (opcional):', '');
    if (reason === null) return;
    this.api.addReservationBlock({ date: this.availDate, slotTime: null, reason: reason || null }).subscribe({
      next: () => { this.notify('Día bloqueado'); this.loadAvailability(); },
      error: e => this.fail(e)
    });
  }

  unblockSlot(s: SlotAvailability): void {
    const t = this.hhmm(s.time);
    const b = this.blocks.find(x => x.date === this.availDate && (!x.slotTime || this.hhmm(x.slotTime) === t));
    if (!b) {
      this.error('No encontré el bloqueo. Revisa la lista de bloqueos activos.');
      return;
    }
    this.deleteBlock(b);
  }

  deleteBlock(b: ReservationBlock): void {
    this.api.deleteReservationBlock(b.id).subscribe({
      next: () => { this.notify('Bloqueo eliminado'); this.loadAvailability(); },
      error: e => this.fail(e)
    });
  }

  // ── Configuración ─────────────────────────────────────────────────────

  loadSettings(): void {
    this.api.getReservationSettings().subscribe({
      next: res => {
        const s = res.data;
        this.cfg = {
          ...s,
          operatingDays: [...(s.operatingDays || [])],
          reservationStartTime: this.hhmm(s.reservationStartTime),
          reservationEndTime: this.hhmm(s.reservationEndTime)
        };
      },
      error: e => this.fail(e)
    });
    this.api.getCapacityRules().subscribe({
      next: res => this.rules = res.data || [],
      error: () => this.rules = []
    });
  }

  toggleDay(c: ReservationSettings, key: string): void {
    const i = c.operatingDays.indexOf(key);
    if (i >= 0) c.operatingDays.splice(i, 1); else c.operatingDays.push(key);
  }

  saveSettings(c: ReservationSettings): void {
    this.api.updateReservationSettings({
      ...c,
      reservationStartTime: this.hhmm(c.reservationStartTime),
      reservationEndTime: this.hhmm(c.reservationEndTime)
    }).subscribe({
      next: () => { this.notify('Configuración guardada'); this.loadSettings(); },
      error: e => this.fail(e)
    });
  }

  addRule(): void {
    if (this.ruleForm.capacity === null || this.ruleForm.capacity < 0) {
      this.error('Indica la capacidad de la regla.');
      return;
    }
    this.api.addCapacityRule({
      dayOfWeek: this.ruleForm.dayOfWeek || null,
      slotTime: this.ruleForm.slotTime ? this.hhmm(this.ruleForm.slotTime) : null,
      capacity: Number(this.ruleForm.capacity)
    }).subscribe({
      next: () => {
        this.ruleForm = { dayOfWeek: '', slotTime: '', capacity: null };
        this.notify('Regla agregada');
        this.loadSettings();
      },
      error: e => this.fail(e)
    });
  }

  deleteRule(r: CapacityRule): void {
    this.api.deleteCapacityRule(r.id).subscribe({
      next: () => { this.notify('Regla eliminada'); this.loadSettings(); },
      error: e => this.fail(e)
    });
  }

  dayLabel(key?: string | null): string {
    if (!key) return 'Todos los días';
    return this.days.find(d => d.key === key)?.label || key;
  }

  // ── Utilidades ────────────────────────────────────────────────────────

  trackById(_: number, r: Reservation): string {
    return r.id;
  }

  /** "2026-10-10" → "sáb, 10 oct" (sin conversión de zona horaria). */
  fmtDate(d: string | null | undefined): string {
    if (!d) return '';
    const dt = new Date(d + 'T00:00:00');
    return dt.toLocaleDateString('es-CO', { weekday: 'short', day: 'numeric', month: 'short' });
  }

  /** "14:00:00" → "2:00 PM". */
  fmtTime(t: string | null | undefined): string {
    if (!t) return '';
    const [hh, mm] = t.split(':');
    const h = Number(hh);
    const suffix = h >= 12 ? 'PM' : 'AM';
    const h12 = h % 12 === 0 ? 12 : h % 12;
    return h12 + ':' + (mm || '00') + ' ' + suffix;
  }

  hhmm(t: string | null | undefined): string {
    return t ? t.substring(0, 5) : '';
  }

  private localIso(d: Date): string {
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return d.getFullYear() + '-' + m + '-' + day;
  }

  private newKey(): string {
    const c = (globalThis as { crypto?: Crypto }).crypto;
    return c && 'randomUUID' in c ? c.randomUUID() : 'k-' + Date.now() + '-' + Math.random().toString(36).slice(2);
  }

  private done(text: string): void {
    this.notify(text);
    this.load();
    this.loadStats();
    if (this.tab === 'availability') this.loadAvailability();
  }

  private notify(text: string): void {
    this.showNotice({ type: 'ok', text });
  }

  private error(text: string): void {
    this.showNotice({ type: 'error', text });
  }

  private fail(e: unknown): void {
    const err = e as { error?: { message?: string }; message?: string };
    this.error(err?.error?.message || err?.message || 'Ocurrió un error. Intenta de nuevo.');
  }

  private showNotice(n: { type: 'ok' | 'error'; text: string }): void {
    this.notice = n;
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
    this.noticeTimer = setTimeout(() => this.notice = null, 6000);
  }
}
