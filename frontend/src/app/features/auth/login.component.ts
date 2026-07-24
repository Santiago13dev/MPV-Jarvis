import { Component } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  template: `
    <div class="login-page">
      <div class="login-card card">

        <!-- Header -->
        <div class="login-header">
          <div class="login-logo">💬</div>
          <h1>WhatsApp MVP</h1>
          <p class="text-muted">Panel de Administración</p>
        </div>

        <!-- Form -->
        <form [formGroup]="form" (ngSubmit)="login()">
          <div class="form-group">
            <label>Correo electrónico</label>
            <input
              class="form-control"
              type="email"
              formControlName="email"
              placeholder="admin@empresa.com"
              autocomplete="email">
          </div>

          <div class="form-group">
            <label>Contraseña</label>
            <div class="password-wrapper">
              <input
                class="form-control"
                [type]="showPassword ? 'text' : 'password'"
                formControlName="password"
                placeholder="••••••••"
                autocomplete="current-password">
              <button type="button" class="password-toggle" (click)="showPassword = !showPassword">
                {{ showPassword ? '🙈' : '👁️' }}
              </button>
            </div>
          </div>

          <!-- Error -->
          <div class="alert alert-error" *ngIf="error">
            {{ error }}
          </div>

          <!-- Submit -->
          <button
            class="btn btn-primary login-btn"
            type="submit"
            [disabled]="loading || form.invalid">
            <span *ngIf="loading" class="spinner"></span>
            {{ loading ? 'Ingresando...' : 'Ingresar al Dashboard' }}
          </button>
        </form>

        <!-- Footer -->
        <p class="login-footer text-xs text-muted">
          WhatsApp MVP Dashboard v1.0
        </p>

      </div>
    </div>
  `,
  styles: [`
    .login-page {
      min-height: 100vh;
      min-height: 100dvh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--color-bg);
      padding: var(--spacing-lg);
      padding-top: calc(var(--spacing-lg) + var(--safe-top));
      padding-bottom: calc(var(--spacing-lg) + var(--safe-bottom));
      background-image: radial-gradient(
        ellipse at 50% 0%,
        rgba(37, 211, 102, 0.05) 0%,
        transparent 70%
      );
    }

    @media (max-width: 420px) {
      .login-page { padding: var(--spacing-md); }
    }

    .login-card {
      width: 100%;
      max-width: 420px;
      display: flex;
      flex-direction: column;
      gap: var(--spacing-lg);
    }

    @media (max-width: 420px) {
      .login-card { padding: var(--spacing-lg) var(--spacing-md); gap: var(--spacing-md); }
    }

    .login-header {
      text-align: center;
      .login-logo { font-size: 56px; margin-bottom: var(--spacing-sm); }
      h1 { font-size: var(--font-size-2xl); font-weight: 700; }
      p  { margin-top: 4px; }
    }

    @media (max-width: 420px) {
      .login-header .login-logo { font-size: 44px; }
    }

    form { display: flex; flex-direction: column; gap: var(--spacing-md); }

    .password-wrapper { position: relative; }
    .password-toggle {
      position: absolute; right: 12px; top: 50%; transform: translateY(-50%);
      background: none; border: none; cursor: pointer; font-size: 16px;
      color: var(--color-text-muted); padding: 4px;
    }

    .login-btn {
      width: 100%;
      justify-content: center;
      padding: 12px;
      font-size: var(--font-size-md);
      margin-top: var(--spacing-sm);
    }

    .login-footer { text-align: center; }
  `]
})
export class LoginComponent {
  form: FormGroup;
  loading = false;
  error = '';
  showPassword = false;

  constructor(
    private fb: FormBuilder,
    private authService: AuthService,
    private router: Router
  ) {
    // Si ya está logueado, redirigir
    if (this.authService.isLoggedIn()) {
      this.router.navigate(['/dashboard']);
    }

    this.form = this.fb.group({
      email:    ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(4)]]
    });
  }

  login(): void {
    if (this.form.invalid) return;
    this.loading = true;
    this.error = '';

    this.authService.login(this.form.value).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      error: (err) => {
        this.error = err.status === 401
          ? 'Credenciales inválidas. Verifica tu correo y contraseña.'
          : 'Error de conexión. Intenta de nuevo.';
        this.loading = false;
      }
    });
  }
}
