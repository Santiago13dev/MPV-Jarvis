import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { Reservation, ReservationStatus } from '../../core/models/models';

@Component({
  selector: 'app-reservations',
  template: `
    <div class="page-header">
      <div>
        <h1>Reservas</h1>
        <p>Gestión de reservas de clientes</p>
      </div>
      <button class="btn btn-primary" (click)="load()">🔄 Actualizar</button>
    </div>

    <div class="card">
      <div class="table-container" *ngIf="reservations.length > 0; else emptyTpl">
        <table>
          <thead>
            <tr>
              <th>Cliente</th>
              <th>Teléfono</th>
              <th>Personas</th>
              <th>Fecha/Hora</th>
              <th>Monto</th>
              <th>Estado</th>
              <th>Acciones</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let res of reservations">
              <td class="font-medium">{{ res.customerName }}</td>
              <td>{{ res.phoneNumber }}</td>
              <td>{{ res.peopleCount || '-' }}</td>
              <td>{{ res.reservationDate | date:'short' }}</td>
              <td>{{ res.amount | currency }}</td>
              <td>
                <span class="badge"
                  [class.badge-warning]="res.status === 'PENDIENTE'"
                  [class.badge-success]="res.status === 'CONFIRMADA'"
                  [class.badge-error]="res.status === 'CANCELADA'">
                  {{ res.status }}
                </span>
              </td>
              <td>
                <div class="flex gap-sm">
                  <button class="btn btn-sm btn-secondary" 
                          *ngIf="res.status === 'PENDIENTE'"
                          (click)="updateStatus(res.id, 'CONFIRMADA')">
                    ✅ Confirmar
                  </button>
                  <button class="btn btn-sm btn-danger" 
                          *ngIf="res.status !== 'CANCELADA'"
                          (click)="updateStatus(res.id, 'CANCELADA')">
                    ❌ Cancelar
                  </button>
                  <button class="btn btn-sm btn-danger-outline" 
                          (click)="deleteReservation(res.id)">
                    🗑
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <ng-template #emptyTpl>
      <div class="empty-state">
        <div class="empty-icon">📅</div>
        <h3>No hay reservas</h3>
        <p>Aún no se han creado reservas en el sistema.</p>
      </div>
    </ng-template>
  `,
  styles: [`
    .table-container {
      margin-top: var(--spacing-md);
    }

    .btn-danger-outline {
      background: transparent;
      border: 1px solid #ef4444;
      color: #ef4444;
      &:hover { background: rgba(239,68,68,0.1); }
    }

    /* ── Tabla → tarjetas en mobile (sin tocar el HTML) ────────── */
    @media (max-width: 640px) {
      .table-container { overflow: visible; border: none; background: none; }

      table, thead, tbody, tr, td { display: block; width: 100%; }
      thead { display: none; }

      tr {
        background: var(--color-bg-card);
        border: 1px solid var(--color-border);
        border-radius: var(--radius-lg);
        padding: var(--spacing-sm) var(--spacing-md);
        margin-bottom: var(--spacing-sm);
        box-shadow: var(--shadow-sm);
      }

      td {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        padding: 8px 0;
        border-bottom: 1px dashed var(--color-border);
        text-align: right;

        &::before {
          content: attr(data-label);
          font-size: var(--font-size-xs);
          font-weight: 600;
          text-transform: uppercase;
          letter-spacing: 0.5px;
          color: var(--color-text-secondary);
          text-align: left;
          flex-shrink: 0;
        }
      }
      td:last-child { border-bottom: none; }
      td:nth-of-type(1)::before { content: 'Cliente'; }
      td:nth-of-type(2)::before { content: 'Teléfono'; }
      td:nth-of-type(3)::before { content: 'Personas'; }
      td:nth-of-type(4)::before { content: 'Fecha/Hora'; }
      td:nth-of-type(5)::before { content: 'Monto'; }
      td:nth-of-type(6)::before { content: 'Estado'; }
      td:nth-of-type(7) { flex-direction: column; align-items: stretch; }
      td:nth-of-type(7)::before { content: 'Acciones'; margin-bottom: 6px; }
      td:nth-of-type(7) .flex { justify-content: flex-end; }
    }
  `]
})
export class ReservationsComponent implements OnInit {
  reservations: Reservation[] = [];

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.getReservations().subscribe(r => {
      if (r.success) {
        this.reservations = r.data || [];
      }
    });
  }

  updateStatus(id: string, status: ReservationStatus): void {
    this.api.updateReservationStatus(id, status).subscribe(r => {
      if (r.success) {
        this.load();
      }
    });
  }

  deleteReservation(id: string): void {
    if (!confirm('¿Eliminar esta reserva?')) return;
    this.api.deleteReservation(id).subscribe(() => this.load());
  }
}
