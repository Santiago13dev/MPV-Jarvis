import { Component } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { AuthUser } from '../../core/models/models';

@Component({
  selector: 'app-sidebar',
  template: `
    <aside class="sidebar">

      <!-- Brand -->
      <div class="brand">
        <span class="brand-icon">💬</span>
        <span class="brand-name">WA Dashboard</span>
      </div>

      <!-- Nav links -->
      <nav class="nav">
        <a routerLink="/dashboard"     routerLinkActive="active" class="nav-item">
          <span class="nav-icon">📊</span>
          <span class="nav-label">Dashboard</span>
        </a>
        <a routerLink="/whatsapp"      routerLinkActive="active" class="nav-item">
          <span class="nav-icon">📱</span>
          <span class="nav-label">WhatsApp</span>
        </a>
        <a routerLink="/conversations" routerLinkActive="active" class="nav-item">
          <span class="nav-icon">💬</span>
          <span class="nav-label">Conversaciones</span>
        </a>
        <a routerLink="/reservations"  routerLinkActive="active" class="nav-item">
          <span class="nav-icon">📅</span>
          <span class="nav-label">Reservas</span>
        </a>
        <a routerLink="/faqs"          routerLinkActive="active" class="nav-item">
          <span class="nav-icon">❓</span>
          <span class="nav-label">FAQs</span>
        </a>
        <a routerLink="/config"        routerLinkActive="active" class="nav-item">
          <span class="nav-icon">⚙️</span>
          <span class="nav-label">Configuración</span>
        </a>
      </nav>

      <!-- Footer -->
      <div class="sidebar-footer">
        <div class="user-info">
          <div class="user-avatar">{{ userInitial }}</div>
          <div class="user-details">
            <div class="user-name">{{ user?.fullName }}</div>
            <div class="user-role text-xs text-muted">{{ user?.role }}</div>
          </div>
        </div>
        <button class="btn btn-ghost btn-icon" (click)="logout()" title="Cerrar sesión">
          🚪
        </button>
      </div>

    </aside>
  `,
  styles: [`
    .sidebar {
      width: var(--sidebar-width);
      background: var(--color-bg-surface);
      border-right: 1px solid var(--color-border);
      display: flex;
      flex-direction: column;
      padding: var(--spacing-md);
      flex-shrink: 0;
      overflow: hidden;
    }

    /* Brand */
    .brand {
      display: flex;
      align-items: center;
      gap: var(--spacing-sm);
      padding: var(--spacing-md) var(--spacing-sm);
      margin-bottom: var(--spacing-lg);
      border-bottom: 1px solid var(--color-border);
      padding-bottom: var(--spacing-lg);
    }
    .brand-icon { font-size: 28px; }
    .brand-name {
      font-size: var(--font-size-lg);
      font-weight: 700;
      color: var(--color-primary);
      white-space: nowrap;
    }

    /* Nav */
    .nav {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .nav-item {
      display: flex;
      align-items: center;
      gap: var(--spacing-sm);
      padding: 10px var(--spacing-md);
      border-radius: var(--radius-md);
      color: var(--color-text-secondary);
      font-size: var(--font-size-md);
      font-weight: 500;
      text-decoration: none;
      transition: all var(--transition-fast);
      cursor: pointer;

      .nav-icon { font-size: 18px; width: 24px; text-align: center; }
      .nav-label { white-space: nowrap; }

      &:hover {
        background: var(--color-bg-hover);
        color: var(--color-text-primary);
        text-decoration: none;
      }
      &.active {
        background: rgba(232, 132, 92, 0.12);
        color: var(--color-primary);
        font-weight: 600;
        border-left: 3px solid var(--color-primary);
        padding-left: calc(var(--spacing-md) - 3px);
      }
    }

    /* Footer */
    .sidebar-footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--spacing-sm);
      border-top: 1px solid var(--color-border);
      padding-top: var(--spacing-md);
      margin-top: var(--spacing-md);
    }
    .user-info {
      display: flex;
      align-items: center;
      gap: var(--spacing-sm);
      min-width: 0;
    }
    .user-avatar {
      width: 34px;
      height: 34px;
      border-radius: 50%;
      background: rgba(232, 132, 92, 0.15);
      color: var(--color-primary);
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 700;
      font-size: var(--font-size-md);
      flex-shrink: 0;
    }
    .user-details { min-width: 0; }
    .user-name {
      font-size: var(--font-size-sm);
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  `]
})
export class SidebarComponent {
  user: AuthUser | null;
  userInitial: string;

  constructor(private authService: AuthService) {
    this.user = this.authService.getCurrentUser();
    this.userInitial = (this.user?.fullName || 'A').charAt(0).toUpperCase();
  }

  logout(): void {
    this.authService.logout();
  }
}
