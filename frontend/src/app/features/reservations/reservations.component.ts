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
                          *ngIf="res.status === 'PENDIENTE'"
                          (click)="updateStatus(res.id, 'CANCELADA')">
                    ❌ Cancelar
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
}
