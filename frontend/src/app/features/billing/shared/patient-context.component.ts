import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  inject,
  Input,
  Output,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

/** ULID alphabet — 26 chars: digits + Crockford base32 excluding i, l, o, u */
const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/;

@Component({
  selector: 'app-patient-context',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="patient-context-form">
      <mat-form-field appearance="outline" class="uid-field">
        <mat-label>Patient UID</mat-label>
        <input
          matInput
          formControlName="patientUid"
          autocomplete="off"
          aria-required="true"
          aria-label="Patient UID (26-character identifier)"
          [attr.aria-describedby]="'patient-uid-error'"
          maxlength="26"
          placeholder="01ARYZ6S41TPTWGIKMJ4VWBZWK"
        />
        @if (form.controls.patientUid.invalid && form.controls.patientUid.touched) {
          @if (form.controls.patientUid.hasError('required')) {
            <mat-error id="patient-uid-error">Patient UID is required.</mat-error>
          } @else {
            <mat-error id="patient-uid-error">Enter the 26-character patient UID.</mat-error>
          }
        } @else {
          <mat-hint>26-character ULID (e.g. 01ARYZ6S41TPTWGIKMJ4VWBZWK)</mat-hint>
        }
      </mat-form-field>

      <button
        mat-raised-button
        color="primary"
        type="submit"
        class="load-btn"
        [disabled]="loading"
        aria-label="Load bills for patient"
      >
        @if (loading) {
          <mat-spinner diameter="20" class="btn-spinner"></mat-spinner>
          Loading…
        } @else {
          Load Bills
        }
      </button>
    </form>
  `,
  styles: [`
    .patient-context-form {
      display: flex;
      align-items: flex-start;
      gap: 1rem;
      flex-wrap: wrap;
    }
    .uid-field {
      flex: 1;
      min-width: 280px;
    }
    .load-btn {
      margin-top: 4px;
      min-height: 44px;
      min-width: 120px;
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
    }
    .btn-spinner {
      display: inline-block;
    }
  `],
})
export class PatientContextComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  @Input() loading = false;

  @Output() readonly patientUidConfirmed = new EventEmitter<string>();

  readonly form = this.fb.group({
    patientUid: [
      '',
      [
        Validators.required,
        Validators.minLength(26),
        Validators.maxLength(26),
        Validators.pattern(ULID_PATTERN),
      ],
    ],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.patientUidConfirmed.emit(this.form.controls.patientUid.value);
  }
}
