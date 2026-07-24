import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { Conversation, ConversationStatus } from '../../core/models/models';

@Component({
  selector: 'app-conversations',
  template: `
    <div class="page-header">
      <div>
        <h1>Conversaciones</h1>
        <p>{{ total }} conversaciones · {{ unreadTotal }} sin leer</p>
      </div>
    </div>

    <!-- Filtros y búsqueda -->
    <div class="filters-bar">
      <input
        class="form-control search-input"
        [(ngModel)]="search"
        (ngModelChange)="onSearch()"
        placeholder="🔍 Buscar por nombre o teléfono...">

      <div class="filter-tabs">
        <button
          *ngFor="let f of filters"
          class="filter-tab"
          [class.active]="activeFilter === f.value"
          (click)="setFilter(f.value)">
          {{ f.label }}
        </button>
      </div>
    </div>

    <!-- Lista -->
    <div class="conv-list" *ngIf="conversations.length > 0; else emptyTpl">
      <div
        class="conv-item"
        *ngFor="let c of conversations"
        (click)="open(c.id)"
        [class.has-unread]="c.unreadCount > 0">

        <!-- Avatar -->
        <div class="conv-avatar" [class.avatar-takeover]="c.status === 'HUMAN_TAKEOVER'">
          {{ getInitial(c) }}
        </div>

        <!-- Info -->
        <div class="conv-body">
          <div class="conv-top">
            <span class="conv-name">{{ c.contact.displayName || c.contact.phone }}</span>
            <span class="conv-time text-xs text-muted">
              {{ c.lastMessageAt | date:'HH:mm' }}
            </span>
          </div>
          <div class="conv-bottom">
            <span class="conv-phone text-sm text-muted">{{ c.contact.phone }}</span>
            <span class="badge"
              [class.badge-success]="c.status === 'AUTO'"
              [class.badge-warning]="c.status === 'HUMAN_TAKEOVER'"
              [class.badge-muted]="c.status === 'CLOSED' || c.status === 'WAITING'">
              {{ statusLabel(c.status) }}
            </span>
          </div>
        </div>

        <!-- Unread badge -->
        <div class="unread-badge" *ngIf="c.unreadCount > 0">
          {{ c.unreadCount }}
        </div>

        <!-- Delete button -->
        <button class="btn-delete" (click)="deleteConversation($event, c.id)" title="Eliminar">
          ✕
        </button>
      </div>
    </div>

    <ng-template #emptyTpl>
      <div class="empty-state">
        <div class="empty-icon">💬</div>
        <h3>Sin conversaciones</h3>
        <p>Cuando lleguen mensajes de WhatsApp aparecerán aquí automáticamente</p>
      </div>
    </ng-template>
  `,
  styles: [`
    .filters-bar {
      display: flex;
      align-items: center;
      gap: var(--spacing-md);
      margin-bottom: var(--spacing-lg);
      flex-wrap: wrap;
    }
    .search-input { max-width: 300px; }
    .filter-tabs { display: flex; gap: 4px; }
    .filter-tab {
      padding: 7px 14px; border-radius: var(--radius-md);
      border: 1px solid var(--color-border); background: var(--color-bg-input);
      color: var(--color-text-secondary); font-size: var(--font-size-sm);
      cursor: pointer; transition: all var(--transition-fast);
      white-space: nowrap;
      min-height: 36px;
      &:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
      &.active { background: rgba(232,132,92,0.12); border-color: var(--color-primary); color: var(--color-primary); font-weight: 600; }
    }

    @media (max-width: 767px) {
      .filters-bar { gap: var(--spacing-sm); }
      .search-input { max-width: none; width: 100%; }
      .filter-tabs {
        width: 100%;
        overflow-x: auto;
        -webkit-overflow-scrolling: touch;
        padding-bottom: 2px;
        &::-webkit-scrollbar { display: none; }
      }
      .filter-tab { flex-shrink: 0; }
    }

    .conv-list { display: flex; flex-direction: column; gap: 6px; }
    .conv-item {
      display: flex; align-items: center; gap: var(--spacing-md);
      background: var(--color-bg-card); border: 1px solid var(--color-border);
      border-radius: var(--radius-lg); padding: var(--spacing-md);
      cursor: pointer; transition: all var(--transition-fast);
      &:hover { border-color: var(--color-primary); transform: translateX(3px); box-shadow: var(--shadow-sm); }
      &.has-unread { border-left: 3px solid var(--color-primary); }
    }

    @media (max-width: 767px) {
      .conv-item {
        gap: var(--spacing-sm);
        padding: 10px var(--spacing-sm);
        &:hover { transform: none; }
        &:active { background: var(--color-bg-hover); }
      }
      .conv-name { font-size: var(--font-size-sm); }
      .conv-phone { display: none; }
    }

    .conv-avatar {
      width: 48px; height: 48px; border-radius: 50%;
      background: var(--color-bg-input); color: var(--color-primary);
      display: flex; align-items: center; justify-content: center;
      font-weight: 700; font-size: var(--font-size-lg); flex-shrink: 0;
      border: 2px solid transparent;
      &.avatar-takeover { border-color: var(--color-warning); }
    }

    @media (max-width: 767px) {
      .conv-avatar { width: 42px; height: 42px; font-size: var(--font-size-md); }
    }

    .conv-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
    .conv-top  { display: flex; justify-content: space-between; align-items: center; }
    .conv-bottom { display: flex; justify-content: space-between; align-items: center; }
    .conv-name { font-weight: 600; font-size: var(--font-size-md); }

    .unread-badge {
      min-width: 22px; height: 22px; border-radius: var(--radius-full);
      background: var(--color-primary); color: #FFFFFF;
      display: flex; align-items: center; justify-content: center;
      font-size: var(--font-size-xs); font-weight: 700; padding: 0 6px;
    }

    .btn-delete {
      width: 28px; height: 28px; border-radius: var(--radius-full);
      border: none; background: transparent; color: var(--color-text-muted);
      cursor: pointer; display: flex; align-items: center; justify-content: center;
      font-size: 14px; flex-shrink: 0; transition: all var(--transition-fast);
      opacity: 0;
      &:hover { background: rgba(239,68,68,0.1); color: #ef4444; }
    }
    .conv-item:hover .btn-delete { opacity: 1; }

    @media (max-width: 767px) {
      .btn-delete { opacity: 1; width: 24px; height: 24px; font-size: 12px; }
    }
  `]
})
export class ConversationsComponent implements OnInit, OnDestroy {
  conversations: Conversation[] = [];
  total = 0;
  unreadTotal = 0;
  search = '';
  activeFilter = '';
  private subs = new Subscription();

  filters = [
    { label: 'Todas',        value: '' },
    { label: '🤖 Auto',      value: 'AUTO' },
    { label: '👤 Con humano', value: 'HUMAN_TAKEOVER' },
    { label: '✅ Cerradas',  value: 'CLOSED' },
  ];

  constructor(
    private api: ApiService,
    private ws: WebSocketService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.load();
    // Actualización en tiempo real
    this.subs.add(
      this.ws.onConversationUpdate().subscribe(() => this.load())
    );
    this.subs.add(
      this.ws.onNewMessage().subscribe(() => this.load())
    );
  }

  load(): void {
    this.api.getConversations(0, 100, this.activeFilter || undefined, this.search || undefined)
      .subscribe(r => {
        this.conversations = r.data?.content || [];
        this.total         = r.data?.totalElements || 0;
        this.unreadTotal   = this.conversations.reduce((sum, c) => sum + (c.unreadCount || 0), 0);
      });
  }

  onSearch(): void { this.load(); }

  setFilter(val: string): void {
    this.activeFilter = val;
    this.load();
  }

  open(id: string): void { this.router.navigate(['/conversations', id]); }

  deleteConversation(event: Event, id: string): void {
    event.stopPropagation();
    if (!confirm('¿Eliminar esta conversación?')) return;
    this.api.deleteConversation(id).subscribe(() => this.load());
  }

  getInitial(c: Conversation): string {
    return (c.contact.displayName || c.contact.phone || '?').charAt(0).toUpperCase();
  }

  statusLabel(s: ConversationStatus): string {
    const m: Record<ConversationStatus, string> = {
      AUTO: '🤖 Auto', HUMAN_TAKEOVER: '👤 Humano',
      WAITING: '⏳ Espera', CLOSED: '✅ Cerrado'
    };
    return m[s] || s;
  }

  ngOnDestroy(): void { this.subs.unsubscribe(); }
}
