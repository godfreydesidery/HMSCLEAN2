import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthControllerService } from '../../api/generated';
import { AuthStore } from '../../core/auth/auth.store';
import { extractProblem } from '../../core/error/problem-detail';

@Component({
  selector: 'app-login',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  template: `
    <div class="login-wrapper">
      <div class="card shadow-sm login-card">
        <div class="card-body p-4">
          <h1 class="h4 text-center mb-4">Zana HMIS — Sign In</h1>

          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
            <div class="mb-3">
              <label for="username" class="form-label">Username</label>
              <input
                id="username"
                type="text"
                class="form-control"
                formControlName="username"
                autocomplete="username"
                [class.is-invalid]="form.controls.username.invalid && form.controls.username.touched"
              />
              @if (form.controls.username.invalid && form.controls.username.touched) {
                <div class="invalid-feedback">Username is required.</div>
              }
            </div>

            <div class="mb-3">
              <label for="password" class="form-label">Password</label>
              <input
                id="password"
                type="password"
                class="form-control"
                formControlName="password"
                autocomplete="current-password"
                [class.is-invalid]="form.controls.password.invalid && form.controls.password.touched"
              />
              @if (form.controls.password.invalid && form.controls.password.touched) {
                <div class="invalid-feedback">Password is required.</div>
              }
            </div>

            @if (errorMsg()) {
              <div class="alert alert-danger py-2" role="alert">{{ errorMsg() }}</div>
            }

            <button
              type="submit"
              class="btn btn-primary w-100"
              [disabled]="submitting() || form.invalid"
            >
              @if (submitting()) {
                <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
                Signing in…
              } @else {
                Sign In
              }
            </button>
          </form>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .login-wrapper {
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      padding: 1rem;
    }
    .login-card { width: 100%; max-width: 420px; }
  `],
})
export class LoginComponent {
  private readonly fb          = inject(NonNullableFormBuilder);
  private readonly authService = inject(AuthControllerService);
  private readonly authStore   = inject(AuthStore);
  private readonly router      = inject(Router);

  readonly form = this.fb.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  readonly submitting = signal(false);
  readonly errorMsg   = signal<string | null>(null);

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.errorMsg.set(null);

    this.authService
      .token({ tokenRequest: this.form.getRawValue() })
      .subscribe({
        next: (resp) => {
          this.authStore.setTokens(resp);
          void this.router.navigate(['/home']);
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          const problem = extractProblem(err);
          if (problem.status === 401) {
            this.errorMsg.set('Invalid username or password.');
          } else if (problem.status === 403) {
            this.errorMsg.set('Your account is not permitted to sign in.');
          } else {
            this.errorMsg.set('Sign-in failed. Please try again.');
          }
        },
        complete: () => {
          this.submitting.set(false);
        },
      });
  }
}
