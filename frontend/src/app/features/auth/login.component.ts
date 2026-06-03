import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { DefaultService } from '../../api/generated';
import { AuthStore } from '../../core/auth/auth.store';
import { extractProblem } from '../../core/error/problem-detail';

@Component({
  selector: 'app-login',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
  ],
  template: `
    <div class="login-wrapper">
      <mat-card class="login-card">
        <mat-card-header>
          <mat-card-title>Zana HMIS — Sign In</mat-card-title>
        </mat-card-header>

        <mat-card-content>
          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Username</mat-label>
              <input
                matInput
                formControlName="username"
                autocomplete="username"
                aria-required="true"
              />
              @if (form.controls.username.invalid && form.controls.username.touched) {
                <mat-error>Username is required.</mat-error>
              }
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Password</mat-label>
              <input
                matInput
                type="password"
                formControlName="password"
                autocomplete="current-password"
                aria-required="true"
              />
              @if (form.controls.password.invalid && form.controls.password.touched) {
                <mat-error>Password is required.</mat-error>
              }
            </mat-form-field>

            @if (errorMsg()) {
              <p class="error-msg" role="alert">{{ errorMsg() }}</p>
            }

            <button
              mat-raised-button
              color="primary"
              type="submit"
              class="full-width"
              [disabled]="submitting() || form.invalid"
            >
              @if (submitting()) {
                Signing in…
              } @else {
                Sign In
              }
            </button>

          </form>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .login-wrapper {
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: calc(100vh - 64px);
      padding: 1rem;
    }
    .login-card { width: 100%; max-width: 420px; }
    .full-width  { width: 100%; margin-bottom: 0.75rem; display: block; }
    .error-msg   { color: #c62828; margin: 0 0 0.75rem; font-size: 0.875rem; }
  `],
})
export class LoginComponent {
  private readonly fb             = inject(NonNullableFormBuilder);
  private readonly defaultService = inject(DefaultService);
  private readonly authStore      = inject(AuthStore);
  private readonly router         = inject(Router);

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

    this.defaultService
      .issueToken({ tokenRequest: this.form.getRawValue() })
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
