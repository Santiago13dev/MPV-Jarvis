import { Component, OnInit } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { WebSocketService } from '../../core/services/websocket.service';

@Component({
  selector: 'app-dashboard-layout',
  template: `
    <div class="layout">
      <app-sidebar></app-sidebar>
      <div class="main">
        <app-topbar></app-topbar>
        <div class="content">
          <router-outlet></router-outlet>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .layout {
      display: flex;
      height: 100vh;
      overflow: hidden;
      background: var(--color-bg);
    }
    .main {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      min-width: 0;
    }
    .content {
      flex: 1;
      overflow-y: auto;
      padding: var(--spacing-lg);
    }
  `]
})
export class DashboardLayoutComponent implements OnInit {
  constructor(
    private authService: AuthService,
    private wsService: WebSocketService
  ) {}

  ngOnInit(): void {
    const token = this.authService.getToken();
    if (token) {
      this.wsService.connect(token);
    }
  }
}
