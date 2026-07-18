import { Component, OnInit, OnDestroy, AfterViewChecked, ElementRef, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { Message, Conversation } from '../../core/models/models';

@Component({
  selector: 'app-conversation-detail',
  template: `
    <div class="detail-wrapper">

      <!-- Header -->
      <div class="detail-header">
        <button class="btn btn-ghost btn-sm" (click)="goBack()">← Volver</button>
        <div class="header-contact" *ngIf="conversation">
          <div class="contact-avatar">{{ getInitial() }}</div>
          <div class="contact-info">
            <strong>{{ conversation.contact.displayName || conversation.contact.phone }}</strong>
            <span class="text-sm text-muted">{{ conversation.contact.phone }}</span>
          </div>
        </div>
        <div class="header-actions" *ngIf="conversation">
          <span class="badge"
            [class.badge-success]="conversation.status === 'AUTO'"
            [class.badge-warning]="conversation.status === 'HUMAN_TAKEOVER'"
            [class.badge-muted]="conversation.status === 'CLOSED'">
            {{ conversation.status }}
          </span>
          <button
            class="btn btn-secondary btn-sm"
            (click)="takeover()"
            *ngIf="conversation.status === 'AUTO'"
            title="Tomar control manualmente">
            👤 Tomar control
          </button>
          <button
            class="btn btn-secondary btn-sm"
            (click)="release()"
            *ngIf="conversation.status === 'HUMAN_TAKEOVER'"
            title="Devolver al bot">
            🤖 Devolver al bot
          </button>
        </div>
      </div>

      <!-- Messages area -->
      <div class="messages-area" #messagesContainer>
        <div class="loading-messages" *ngIf="loadingMessages">
          <div class="spinner"></div>
        </div>

        <div *ngFor="let msg of messages; trackBy: trackById">
          <!-- Separador de fecha -->
          <div class="date-separator" *ngIf="shouldShowDate(msg, messages)">
            {{ msg.sentAt | date:'EEEE, d MMMM':'':'es' }}
          </div>

          <!-- Burbuja de mensaje -->
          <div class="message-row" [class.outbound]="msg.direction === 'OUTBOUND'">
            <div class="bubble" [class.bubble-out]="msg.direction === 'OUTBOUND'">
              <p class="bubble-text">{{ msg.content || '📎 Archivo adjunto' }}</p>
              <div class="bubble-footer">
                <span class="bubble-time">{{ msg.sentAt | date:'HH:mm' }}</span>
                <span class="processed-badge" *ngIf="msg.processedBy && msg.direction === 'OUTBOUND'"
                  [class]="getProcessedClass(msg.processedBy)">
                  {{ getProcessedLabel(msg.processedBy) }}
                </span>
                <span class="ai-tokens text-xs text-muted" *ngIf="msg.aiTokensUsed && msg.aiTokensUsed > 0">
                  {{ msg.aiTokensUsed }} tokens
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="empty-state" *ngIf="!loadingMessages && messages.length === 0">
          <div class="empty-icon">💬</div>
          <p>Sin mensajes aún</p>
        </div>

        <!-- Scroll anchor -->
        <div #scrollAnchor></div>
      </div>

      <!-- Reply bar -->
      <div class="reply-bar">
        <div class="reply-input-wrapper">
          <textarea
            class="form-control reply-input"
            [(ngModel)]="replyText"
            placeholder="Escribe un mensaje..."
            (keydown)="onKeydown($event)"
            rows="1"
            [disabled]="sending">
          </textarea>
        </div>
        <button
          class="btn btn-primary send-btn"
          (click)="send()"
          [disabled]="!replyText.trim() || sending">
          <span *ngIf="sending" class="spinner" style="width:16px;height:16px;border-width:2px"></span>
          <span *ngIf="!sending">↑</span>
        </button>
      </div>

    </div>
  `,
  styles: [`
    .detail-wrapper {
      display: flex;
      flex-direction: column;
      height: calc(100vh - var(--topbar-height) - var(--spacing-lg) * 2);
      background: var(--color-bg-card);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      overflow: hidden;
    }

    /* Header */
    .detail-header {
      display: flex;
      align-items: center;
      gap: var(--spacing-md);
      padding: var(--spacing-md) var(--spacing-lg);
      border-bottom: 1px solid var(--color-border);
      background: var(--color-bg-surface);
      flex-shrink: 0;
    }
    .header-contact {
      display: flex; align-items: center; gap: var(--spacing-sm); flex: 1;
    }
    .contact-avatar {
      width: 38px; height: 38px; border-radius: 50%;
      background: rgba(37,211,102,0.15); color: var(--color-primary);
      display: flex; align-items: center; justify-content: center;
      font-weight: 700; flex-shrink: 0;
    }
    .contact-info { display: flex; flex-direction: column; }
    .header-actions { display: flex; align-items: center; gap: var(--spacing-sm); }

    /* Messages */
    .messages-area {
      flex: 1;
      overflow-y: auto;
      padding: var(--spacing-md) var(--spacing-lg);
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .loading-messages { display: flex; justify-content: center; padding: var(--spacing-lg); }

    .date-separator {
      text-align: center;
      font-size: var(--font-size-xs);
      color: var(--color-text-muted);
      padding: var(--spacing-sm) 0;
      text-transform: capitalize;
    }

    .message-row {
      display: flex;
      justify-content: flex-start;
      margin: 2px 0;
      &.outbound { justify-content: flex-end; }
    }

    .bubble {
      max-width: 68%;
      padding: 10px 14px;
      border-radius: 16px 16px 16px 4px;
      background: var(--color-bg-input);
      border: 1px solid var(--color-border);

      &.bubble-out {
        border-radius: 16px 16px 4px 16px;
        background: rgba(37,211,102,0.12);
        border-color: rgba(37,211,102,0.25);
      }
    }
    .bubble-text {
      font-size: var(--font-size-md);
      line-height: 1.5;
      word-break: break-word;
      white-space: pre-wrap;
    }
    .bubble-footer {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-top: 5px;
    }
    .bubble-time { font-size: var(--font-size-xs); color: var(--color-text-muted); }

    .processed-badge {
      font-size: 10px;
      padding: 1px 6px;
      border-radius: var(--radius-full);
      font-weight: 600;
      text-transform: uppercase;
      &.pb-keyword { background: rgba(59,130,246,0.15); color: var(--color-info); }
      &.pb-faq     { background: rgba(37,211,102,0.15); color: var(--color-success); }
      &.pb-ai      { background: rgba(245,158,11,0.15); color: var(--color-warning); }
      &.pb-human   { background: rgba(239,68,68,0.15);  color: var(--color-error); }
      &.pb-system  { background: var(--color-bg-hover); color: var(--color-text-muted); }
    }

    /* Reply bar */
    .reply-bar {
      display: flex;
      align-items: flex-end;
      gap: var(--spacing-sm);
      padding: var(--spacing-md) var(--spacing-lg);
      border-top: 1px solid var(--color-border);
      background: var(--color-bg-surface);
      flex-shrink: 0;
    }
    .reply-input-wrapper { flex: 1; }
    .reply-input {
      resize: none;
      max-height: 120px;
      overflow-y: auto;
      line-height: 1.5;
    }
    .send-btn {
      width: 44px; height: 44px; padding: 0;
      border-radius: var(--radius-md);
      justify-content: center;
      font-size: 18px;
      flex-shrink: 0;
    }
  `]
})
export class ConversationDetailComponent implements OnInit, OnDestroy, AfterViewChecked {
  @ViewChild('scrollAnchor') scrollAnchor!: ElementRef;

  conversationId = '';
  conversation: Conversation | null = null;
  messages: Message[] = [];
  replyText = '';
  loadingMessages = true;
  sending = false;
  private subs = new Subscription();
  private shouldScrollToBottom = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private api: ApiService,
    private ws: WebSocketService
  ) {}

  ngOnInit(): void {
    this.conversationId = this.route.snapshot.paramMap.get('id')!;
    this.loadMessages();

    // Escuchar nuevos mensajes en tiempo real
    this.subs.add(
      this.ws.onNewMessage().subscribe(ev => {
        if (ev.conversationId === this.conversationId) {
          this.loadMessages();
        }
      })
    );
  }

  loadMessages(): void {
    this.api.getMessages(this.conversationId).subscribe(r => {
      this.messages = r.data?.content || [];
      this.loadingMessages = false;
      this.shouldScrollToBottom = true;
    });
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollToBottom) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  scrollToBottom(): void {
    try {
      this.scrollAnchor?.nativeElement?.scrollIntoView({ behavior: 'smooth' });
    } catch (_) {}
  }

  onKeydown(event: KeyboardEvent): void {
    // Enter sin Shift = enviar
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  send(): void {
    const text = this.replyText.trim();
    if (!text || this.sending) return;

    this.sending = true;
    this.api.sendMessage(this.conversationId, text).subscribe({
      next: () => {
        this.replyText = '';
        this.sending = false;
        this.loadMessages();
      },
      error: () => { this.sending = false; }
    });
  }

  takeover(): void {
    this.api.takeover(this.conversationId).subscribe(() => {
      if (this.conversation) this.conversation.status = 'HUMAN_TAKEOVER';
    });
  }

  release(): void {
    this.api.releaseConversation(this.conversationId).subscribe(() => {
      if (this.conversation) this.conversation.status = 'AUTO';
    });
  }

  goBack(): void { this.router.navigate(['/conversations']); }

  getInitial(): string {
    return (this.conversation?.contact?.displayName ||
            this.conversation?.contact?.phone || '?').charAt(0).toUpperCase();
  }

  trackById(_: number, msg: Message): string { return msg.id; }

  shouldShowDate(msg: Message, all: Message[]): boolean {
    const idx = all.indexOf(msg);
    if (idx === 0) return true;
    const prev = all[idx - 1];
    const prevDate = new Date(prev.sentAt).toDateString();
    const currDate = new Date(msg.sentAt).toDateString();
    return prevDate !== currDate;
  }

  getProcessedLabel(p: string): string {
    const m: Record<string, string> = {
      KEYWORD: 'Keyword', FAQ: 'FAQ', AI: 'IA', HUMAN: 'Humano', SYSTEM: 'Sistema'
    };
    return m[p] || p;
  }

  getProcessedClass(p: string): string {
    const m: Record<string, string> = {
      KEYWORD: 'pb-keyword', FAQ: 'pb-faq', AI: 'pb-ai', HUMAN: 'pb-human', SYSTEM: 'pb-system'
    };
    return m[p] || '';
  }

  ngOnDestroy(): void { this.subs.unsubscribe(); }
}
