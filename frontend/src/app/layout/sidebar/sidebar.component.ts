import { Component } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { AuthUser } from '../../core/models/models';

@Component({
  selector: 'app-sidebar',
  template: `
    <!-- ============ SIDEBAR DESKTOP/TABLET ============ -->
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

    <!-- ============ BOTTOM NAV MOBILE ============ -->
    <nav class="mobile-bottom-nav">
      <a routerLink="/dashboard" routerLinkActive="active" class="mnav-item" (click)="closeMore()">
        <span class="mnav-icon">📊</span>
        <span class="mnav-label">Inicio</span>
      </a>
      <a routerLink="/whatsapp" routerLinkActive="active" class="mnav-item" (click)="closeMore()">
        <span class="mnav-icon">📱</span>
        <span class="mnav-label">WhatsApp</span>
      </a>
      <a routerLink="/conversations" routerLinkActive="active" class="mnav-item" (click)="closeMore()">
        <span class="mnav-icon">💬</span>
        <span class="mnav-label">Chats</span>
      </a>
      <a routerLink="/reservations" routerLinkActive="active" class="mnav-item" (click)="closeMore()">
        <span class="mnav-icon">📅</span>
        <span class="mnav-label">Reservas</span>
      </a>
      <button type="button" class="mnav-item mnav-more" [class.active]="moreOpen" (click)="toggleMore()">
        <span class="mnav-icon">☰</span>
        <span class="mnav-label">Más</span>
      </button>
    </nav>

    <!-- Backdrop + hoja inferior con el resto de opciones -->
    <div class="more-backdrop" *ngIf="moreOpen" (click)="closeMore()"></div>
    <div class="more-sheet" [class.open]="moreOpen">
      <div class="more-handle"></div>

      <div class="more-user">
        <div class="user-avatar">{{ userInitial }}</div>
        <div class="user-details">
          <div class="user-name">{{ user?.fullName }}</div>
          <div class="user-role text-xs text-muted">{{ user?.role }}</div>
        </div>
      </div>

      <a routerLink="/faqs" routerLinkActive="active" class="more-link" (click)="closeMore()">
        <span class="nav-icon">❓</span> FAQs
      </a>
      <a routerLink="/config" routerLinkActive="active" class="more-link" (click)="closeMore()">
        <span class="nav-icon">⚙️</span> Configuración
      </a>
      <button type="button" class="more-link more-logout" (click)="logout()">
        <span class="nav-icon">🚪</span> Cerrar sesión
      </button>
    </div>
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

    /* En móvil la sidebar lateral desaparece: la navegación vive en la bottom nav */
    @media (max-width: 767px) {
      .sidebar { display: none; }
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

    /* ============ BOTTOM NAV (mobile) ============ */
    .mobile-bottom-nav {
      display: none;
    }

    @media (max-width: 767px) {
      .mobile-bottom-nav {
        display: flex;
        position: fixed;
        left: 0; right: 0; bottom: 0;
        z-index: 200;
        height: calc(var(--bottom-nav-height) + var(--safe-bottom));
        padding-bottom: var(--safe-bottom);
        background: var(--color-bg-surface);
        border-top: 1px solid var(--color-border);
        box-shadow: 0 -4px 16px rgba(45,27,14,0.08);
      }
      .mnav-item {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 2px;
        border: none;
        background: transparent;
        color: var(--color-text-secondary);
        font-family: var(--font-family);
        text-decoration: none;
        cursor: pointer;
        min-height: 44px;

        .mnav-icon { font-size: 19px; line-height: 1; }
        .mnav-label { font-size: 10px; font-weight: 600; }

        &.active {
          color: var(--color-primary);
        }
        &:active { background: var(--color-bg-hover); }
      }
    }

    /* ============ SHEET "Más" (mobile) ============ */
    .more-backdrop {
      display: none;
    }
    .more-sheet { display: none; }

    @media (max-width: 767px) {
      .more-backdrop {
        display: block;
        position: fixed;
        inset: 0;
        background: rgba(45,27,14,0.45);
        z-index: 300;
        animation: fadeIn var(--transition-md) ease;
      }

      .more-sheet {
        display: flex;
        flex-direction: column;
        position: fixed;
        left: 0; right: 0; bottom: 0;
        z-index: 301;
        background: var(--color-bg-card);
        border-radius: var(--radius-xl) var(--radius-xl) 0 0;
        padding: var(--spacing-sm) var(--spacing-lg) calc(var(--spacing-lg) + var(--safe-bottom));
        box-shadow: var(--shadow-lg);
        transform: translateY(100%);
        transition: transform var(--transition-md);

        &.open { transform: translateY(0); }
      }

      .more-handle {
        width: 40px; height: 4px; border-radius: var(--radius-full);
        background: var(--color-border);
        margin: var(--spacing-sm) auto var(--spacing-md);
      }

      .more-user {
        display: flex; align-items: center; gap: var(--spacing-sm);
        padding-bottom: var(--spacing-md);
        margin-bottom: var(--spacing-sm);
        border-bottom: 1px solid var(--color-border);
      }

      .more-link {
        display: flex; align-items: center; gap: var(--spacing-md);
        padding: 14px var(--spacing-sm);
        font-size: var(--font-size-md);
        font-weight: 500;
        color: var(--color-text-primary);
        text-decoration: none;
        border: none;
        background: transparent;
        width: 100%;
        text-align: left;
        border-radius: var(--radius-md);
        cursor: pointer;

        .nav-icon { font-size: 18px; width: 24px; text-align: center; }

        &:active { background: var(--color-bg-hover); }
        &.active { color: var(--color-primary); font-weight: 600; }
      }
      .more-logout { color: var(--color-error); }
    }

    @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
  `]
})
export class SidebarComponent {
  user: AuthUser | null;
  userInitial: string;
  moreOpen = false;

  constructor(private authService: AuthService) {
    this.user = this.authService.getCurrentUser();
    this.userInitial = (this.user?.fullName || 'A').charAt(0).toUpperCase();
  }

  toggleMore(): void {
    this.moreOpen = !this.moreOpen;
  }

  closeMore(): void {
    this.moreOpen = false;
  }

  logout(): void {
    this.authService.logout();
  }
}
