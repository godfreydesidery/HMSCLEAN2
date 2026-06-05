import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  inject,
  Input,
  Output,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { EntityPickerComponent } from '../../../shared/entity-picker/entity-picker.component';
import { PatientSearch } from '../../../shared/entity-picker/patient-search';

@Component({
  selector: 'app-patient-context',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, EntityPickerComponent],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="patient-context-form">
      <div class="picker-field">
        <label class="form-label" for="patient-picker">Patient</label>
        <app-entity-picker
          id="patient-picker"
          formControlName="patientUid"
          placeholder="Search by name, registration no, or phone…"
          ariaLabel="Search for a patient"
          [searchFn]="patientSearch.searchFn"
          [invalid]="form.controls.patientUid.invalid && form.controls.patientUid.touched"
        ></app-entity-picker>
        @if (form.controls.patientUid.invalid && form.controls.patientUid.touched) {
          <div class="invalid-feedback d-block">Select a patient from the list.</div>
        } @else {
          <div class="form-text">Type to search; pick a patient to load their bills.</div>
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
    .picker-field {
      flex: 1;
      min-width: 320px;
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
  protected readonly patientSearch = inject(PatientSearch);

  @Input() loading = false;

  /** Emits the SELECTED patient's uid (captured by the picker — never typed by the user). */
  @Output() readonly patientUidConfirmed = new EventEmitter<string>();

  readonly form = this.fb.group({
    // The value is the uid captured by the entity-picker on selection; required = a patient was picked.
    patientUid: ['', [Validators.required]],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.patientUidConfirmed.emit(this.form.controls.patientUid.value);
  }
}
