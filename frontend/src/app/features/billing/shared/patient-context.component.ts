import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  inject,
  Input,
  Output,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

/** ULID alphabet — 26 chars: digits + Crockford base32 excluding i, l, o, u */
const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/;

@Component({
  selector: 'app-patient-context',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="patient-context-form">
      <div class="uid-field">
        <label class="form-label" for="patient-uid-input">Patient UID</label>
        <input
          id="patient-uid-input"
          class="form-control"
          formControlName="patientUid"
          autocomplete="off"
          aria-required="true"
          aria-label="Patient UID (26-character identifier)"
          [attr.aria-describedby]="'patient-uid-error'"
          maxlength="26"
          placeholder="01ARYZ6S41TPTWGIKMJ4VWBZWK"
          [class.is-invalid]="form.controls.patientUid.invalid && form.controls.patientUid.touched"
        />
        @if (form.controls.patientUid.invalid && form.controls.patientUid.touched) {
          @if (form.controls.patientUid.hasError('required')) {
            <div id="patient-uid-error" class="invalid-feedback">Patient UID is required.</div>
          } @else {
            <div id="patient-uid-error" class="invalid-feedback">Enter the 26-character patient UID.</div>
          }
        } @else {
          <div class="form-text">26-character ULID (e.g. 01ARYZ6S41TPTWGIKMJ4VWBZWK)</div>
        }
      </div>

      <button
        type="submit"
        class="btn btn-primary load-btn"
        [disabled]="loading"
        aria-label="Load bills for patient"
      >
        @if (loading) {
          <span class="spinner-border spinner-border-sm btn-spinner" role="status" aria-hidden="true"></span>
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
      margin-top: 1.9rem;
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
