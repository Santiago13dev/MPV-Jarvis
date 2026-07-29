import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { FaqItem, CreateFaqRequest } from '../../core/models/models';

@Component({
  selector: 'app-faqs',
  template: `
    <div class="page-header">
      <div>
        <h1>FAQs</h1>
        <p>Preguntas frecuentes para respuesta automática — sin gastar tokens de IA</p>
      </div>
      <button class="btn btn-primary" (click)="openModal()">+ Nueva FAQ</button>
    </div>

    <!-- Stats rápidas -->
    <div class="faq-stats" *ngIf="faqs.length > 0">
      <div class="stat-chip">
        <strong>{{ activeFaqs }}</strong> activas
      </div>
      <div class="stat-chip">
        <strong>{{ totalMatches }}</strong> respuestas automáticas totales
      </div>
      <div class="stat-chip top-chip" *ngIf="topFaq">
        🏆 Más usada: <strong>{{ topFaq.question | slice:0:40 }}...</strong> ({{ topFaq.matchCount }})
      </div>
    </div>

    <!-- Tabla -->
    <div class="table-container" *ngIf="faqs.length > 0; else emptyTpl">
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>Pregunta</th>
            <th>Respuesta</th>
            <th>Keywords</th>
            <th>Usos</th>
            <th>Estado</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let faq of faqs; let i = index"
              [class.row-inactive]="!faq.isActive">
            <td class="text-muted text-sm">{{ i + 1 }}</td>
            <td>
              <strong class="faq-question">{{ faq.question }}</strong>
            </td>
            <td>
              <span class="faq-answer text-muted">
                {{ faq.answer | slice:0:80 }}{{ faq.answer.length > 80 ? '...' : '' }}
              </span>
            </td>
            <td>
              <div class="keywords-wrap">
                <span class="badge badge-muted kw-badge" *ngFor="let kw of (faq.keywords || []) | slice:0:4">
                  {{ kw }}
                </span>
                <span class="text-xs text-muted" *ngIf="(faq.keywords?.length || 0) > 4">
                  +{{ faq.keywords!.length - 4 }}
                </span>
              </div>
            </td>
            <td>
              <span class="uses-count">{{ faq.matchCount }}</span>
            </td>
            <td>
              <span class="badge"
                [class.badge-success]="faq.isActive"
                [class.badge-muted]="!faq.isActive">
                {{ faq.isActive ? 'Activa' : 'Inactiva' }}
              </span>
            </td>
            <td>
              <div class="flex gap-sm">
                <button class="btn btn-ghost btn-sm" (click)="editFaq(faq)" title="Editar">✏️</button>
                <button class="btn btn-ghost btn-sm" (click)="toggleFaq(faq)"
                  [title]="faq.isActive ? 'Desactivar' : 'Activar'">
                  {{ faq.isActive ? '⏸️' : '▶️' }}
                </button>
                <button class="btn btn-ghost btn-sm" (click)="deleteFaq(faq.id)" title="Eliminar"
                  style="color:var(--color-error)">🗑️</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <ng-template #emptyTpl>
      <div class="empty-state">
        <div class="empty-icon">❓</div>
        <h3>Sin FAQs todavía</h3>
        <p>Crea preguntas frecuentes para que el bot responda automáticamente sin usar IA</p>
        <button class="btn btn-primary" (click)="openModal()">+ Crear primera FAQ</button>
      </div>
    </ng-template>

    <!-- Modal crear / editar -->
    <div class="modal-overlay" *ngIf="showModal" (click)="closeModal()">
      <div class="modal-dialog card" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ editingId ? 'Editar FAQ' : 'Nueva FAQ' }}</h2>
          <button class="btn btn-ghost btn-icon" (click)="closeModal()">✕</button>
        </div>

        <div class="modal-body">
          <div class="form-group">
            <label>Pregunta *</label>
            <input
              class="form-control"
              [(ngModel)]="form.question"
              placeholder="¿Cuál es el horario de atención?">
          </div>

          <div class="form-group">
            <label>Respuesta *</label>
            <textarea
              class="form-control"
              [(ngModel)]="form.answer"
              rows="5"
              placeholder="Nuestro horario de atención es de lunes a viernes de 8am a 6pm...">
            </textarea>
          </div>

          <div class="form-group">
            <label>Keywords (separadas por coma)</label>
            <input
              class="form-control"
              [(ngModel)]="keywordsRaw"
              placeholder="horario, hora, abierto, cerrado, atienden">
            <small class="text-xs text-muted" style="margin-top:4px;display:block">
              El bot buscará estas palabras en el mensaje del cliente para activar esta respuesta
            </small>
          </div>

          <div class="form-group">
            <label>Prioridad (número mayor = se evalúa primero)</label>
            <input
              class="form-control"
              type="number"
              [(ngModel)]="form.priority"
              min="0" max="100">
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn btn-secondary" (click)="closeModal()">Cancelar</button>
          <button
            class="btn btn-primary"
            (click)="save()"
            [disabled]="saving || !form.question || !form.answer">
            <span *ngIf="saving" class="spinner" style="width:16px;height:16px;border-width:2px"></span>
            {{ editingId ? 'Guardar cambios' : 'Crear FAQ' }}
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .faq-stats {
      display: flex; gap: var(--spacing-sm); flex-wrap: wrap;
      margin-bottom: var(--spacing-lg);
    }

    @media (max-width: 767px) {
      .stat-chip { font-size: var(--font-size-xs); padding: 5px 10px; }
      .top-chip strong { display: inline-block; max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: bottom; }
    }
    .stat-chip {
      padding: 6px 14px; border-radius: var(--radius-full);
      background: var(--color-bg-input); border: 1px solid var(--color-border);
      font-size: var(--font-size-sm); color: var(--color-text-secondary);
      strong { color: var(--color-text-primary); }
    }
    .top-chip { border-color: rgba(245,158,11,0.4); background: rgba(245,158,11,0.08); color: var(--color-warning); }

    .row-inactive td { opacity: 0.5; }

    /* ── Tabla → tarjetas en mobile ───────────────────────── */
    @media (max-width: 700px) {
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
      tr.row-inactive td { opacity: 1; }

      td {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--spacing-md);
        padding: 7px 0;
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
          padding-top: 2px;
        }
      }
      td:last-child { border-bottom: none; }
      td:nth-of-type(1) { display: none; } /* # */
      td:nth-of-type(2)::before { content: 'Pregunta'; }
      td:nth-of-type(3)::before { content: 'Respuesta'; }
      td:nth-of-type(3) .faq-answer { text-align: right; }
      td:nth-of-type(4)::before { content: 'Keywords'; }
      td:nth-of-type(4) .keywords-wrap { justify-content: flex-end; }
      td:nth-of-type(5)::before { content: 'Usos'; }
      td:nth-of-type(6)::before { content: 'Estado'; }
      td:nth-of-type(7)::before { content: 'Acciones'; }
    }

    .faq-question { font-size: var(--font-size-sm); }
    .faq-answer   { font-size: var(--font-size-sm); }
    .keywords-wrap { display: flex; flex-wrap: wrap; gap: 3px; }
    .kw-badge { font-size: 10px; padding: 1px 6px; }
    .uses-count { font-weight: 700; color: var(--color-primary); }

    /* Modal */
    .modal-overlay {
      position: fixed; inset: 0; background: rgba(0,0,0,0.75);
      display: flex; align-items: center; justify-content: center;
      z-index: 1000; padding: var(--spacing-lg);
    }
    .modal-dialog {
      width: 100%; max-width: 580px;
      display: flex; flex-direction: column;
      max-height: 90vh;
    }
    .modal-header {
      display: flex; align-items: center; justify-content: space-between;
      padding-bottom: var(--spacing-md); border-bottom: 1px solid var(--color-border);
      flex-shrink: 0;
      h2 { font-size: var(--font-size-xl); font-weight: 700; }
    }
    .modal-body {
      display: flex; flex-direction: column; gap: var(--spacing-md);
      overflow-y: auto; flex: 1; min-height: 0;
      -webkit-overflow-scrolling: touch;
    }
    .modal-footer {
      display: flex; justify-content: flex-end; gap: var(--spacing-sm);
      padding-top: var(--spacing-md); border-top: 1px solid var(--color-border);
      flex-shrink: 0;
    }

    @media (max-width: 640px) {
      .modal-overlay { padding: 0; align-items: flex-end; }
      .modal-dialog {
        max-width: none;
        height: calc(100dvh - 40px);
        height: calc(100vh - 40px);
        max-height: none;
        border-radius: var(--radius-xl) var(--radius-xl) 0 0;
        animation: slideUp var(--transition-md) ease;
      }
      .modal-footer {
        padding-bottom: env(safe-area-inset-bottom, 16px);
      }
    }
    @keyframes slideUp { from { transform: translateY(24px); opacity: 0.6; } to { transform: translateY(0); opacity: 1; } }
  `]
})
export class FaqsComponent implements OnInit {
  faqs: FaqItem[] = [];
  showModal = false;
  editingId: string | null = null;
  saving = false;
  keywordsRaw = '';

  form: CreateFaqRequest = { question: '', answer: '', keywords: [], priority: 0 };

  get activeFaqs(): number { return this.faqs.filter(f => f.isActive).length; }
  get totalMatches(): number { return this.faqs.reduce((s, f) => s + (f.matchCount || 0), 0); }
  get topFaq(): FaqItem | null {
    return [...this.faqs].sort((a, b) => (b.matchCount || 0) - (a.matchCount || 0))[0] || null;
  }

  constructor(private api: ApiService) {}

  ngOnInit(): void { this.load(); }

  load(): void {
    this.api.getFaqs().subscribe(r => {
      this.faqs = (r.data || []).sort((a, b) => (b.priority || 0) - (a.priority || 0));
    });
  }

  openModal(): void {
    this.editingId = null;
    this.form = { question: '', answer: '', keywords: [], priority: 0 };
    this.keywordsRaw = '';
    this.showModal = true;
  }

  editFaq(faq: FaqItem): void {
    this.editingId = faq.id;
    this.form = {
      question: faq.question,
      answer:   faq.answer,
      keywords: faq.keywords || [],
      priority: faq.priority || 0
    };
    this.keywordsRaw = (faq.keywords || []).join(', ');
    this.showModal = true;
  }

  closeModal(): void { this.showModal = false; this.saving = false; }

  save(): void {
    if (!this.form.question || !this.form.answer) return;
    this.saving = true;
    this.form.keywords = this.keywordsRaw
      .split(',')
      .map(k => k.trim().toLowerCase())
      .filter(Boolean);

    const obs = this.editingId
      ? this.api.updateFaq(this.editingId, this.form)
      : this.api.createFaq(this.form);

    obs.subscribe({
      next: () => { this.closeModal(); this.load(); },
      error: () => { this.saving = false; }
    });
  }

  toggleFaq(faq: FaqItem): void {
    this.api.toggleFaq(faq.id).subscribe(() => this.load());
  }

  deleteFaq(id: string): void {
    if (!confirm('¿Eliminar esta FAQ? Esta acción no se puede deshacer.')) return;
    this.api.deleteFaq(id).subscribe(() => this.load());
  }
}
