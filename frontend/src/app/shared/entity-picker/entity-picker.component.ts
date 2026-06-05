import {
  ChangeDetectionStrategy,
  Component,
  forwardRef,
  inject,
  Input,
  signal,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import {
  NgbTypeahead,
  NgbTypeaheadModule,
  NgbTypeaheadSelectItemEvent,
} from '@ng-bootstrap/ng-bootstrap';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  Observable,
  of,
  OperatorFunction,
  switchMap,
  tap,
} from 'rxjs';
import { EntityOption, EntitySearchFn } from './entity-option';

/**
 * Reusable searchable entity picker (ng-bootstrap typeahead) — the project's standard way to
 * select an entity (patient, ward bed, prescription, route, …) WITHOUT the user ever typing a
 * uid/id. The user types human-readable text; matching results come from the supplied
 * {@link searchFn}; on selection the component captures the chosen {@link EntityOption.uid}
 * into the form model. The displayed text is the label; the model value is always the uid.
 *
 * <p>Implements {@link ControlValueAccessor} so it drops into reactive/template forms:
 * {@code <app-entity-picker formControlName="patientUid" [searchFn]="searchPatients" />}.
 * The form control's value is the selected uid (string) or '' when nothing is selected.
 *
 * <p>Clearing or editing the text after a selection invalidates it: the model is reset to ''
 * until a new result is picked, so a half-typed name can never be mistaken for a valid uid.
 */
@Component({
  selector: 'app-entity-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgbTypeaheadModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => EntityPickerComponent),
      multi: true,
    },
  ],
  template: `
    <div class="position-relative">
      <input
        #instance="ngbTypeahead"
        type="text"
        class="form-control"
        role="combobox"
        autocomplete="off"
        [class.is-invalid]="invalid"
        [placeholder]="placeholder"
        [disabled]="disabled()"
        [ngbTypeahead]="search"
        [resultTemplate]="resultTpl"
        [inputFormatter]="formatter"
        [editable]="false"
        (selectItem)="onSelect($event)"
        (blur)="onTouched()"
        (input)="onInput($any($event.target).value)"
        [attr.aria-label]="ariaLabel || placeholder"
      />

      @if (searching()) {
        <span class="picker-spinner spinner-border spinner-border-sm text-secondary"
              role="status" aria-hidden="true"></span>
      }
    </div>

    <ng-template #resultTpl let-option="result" let-term="term">
      <div class="picker-option">
        <span class="picker-option__label">
          <ngb-highlight [result]="option.label" [term]="term"></ngb-highlight>
        </span>
        @if (option.sublabel) {
          <span class="picker-option__sub text-muted small">{{ option.sublabel }}</span>
        }
      </div>
    </ng-template>
  `,
  styles: [`
    .picker-spinner {
      position: absolute;
      top: 50%;
      right: 0.6rem;
      transform: translateY(-50%);
      pointer-events: none;
    }
    .picker-option { display: flex; flex-direction: column; line-height: 1.2; }
    .picker-option__sub { margin-top: 0.1rem; }
  `],
})
export class EntityPickerComponent implements ControlValueAccessor {
  /** The search function that turns a typed term into matching options. REQUIRED. */
  @Input({ required: true }) searchFn!: EntitySearchFn;

  /** Placeholder shown in the empty input. */
  @Input() placeholder = 'Search…';

  /** Accessible label (falls back to placeholder). */
  @Input() ariaLabel = '';

  /** External validity flag (e.g. from the form control) to render the Bootstrap is-invalid state. */
  @Input() invalid = false;

  /** Minimum characters before a search fires. */
  @Input() minChars = 2;

  /** Debounce (ms) between keystrokes and the search call. */
  @Input() debounceMs = 250;

  protected readonly searching = signal(false);
  protected readonly disabled  = signal(false);

  /** The currently-selected option, kept so the input can re-display its label after writeValue. */
  private selected: EntityOption | null = null;

  // ControlValueAccessor callbacks
  private onChange: (uid: string) => void = () => { /* set by registerOnChange */ };
  protected onTouched: () => void = () => { /* set by registerOnTouched */ };

  /** ngb-typeahead source: debounce -> search -> map errors to empty list, with a loading flag. */
  protected readonly search: OperatorFunction<string, readonly EntityOption[]> =
    (text$: Observable<string>) =>
      text$.pipe(
        debounceTime(this.debounceMs),
        distinctUntilChanged(),
        tap(() => this.searching.set(true)),
        switchMap((term) => {
          if (!term || term.length < this.minChars) {
            this.searching.set(false);
            return of([] as EntityOption[]);
          }
          return this.searchFn(term).pipe(
            catchError(() => of([] as EntityOption[])),
            tap(() => this.searching.set(false)),
          );
        }),
      );

  /** Render the selected option's label in the input (ngb calls this for the chosen item). */
  protected readonly formatter = (option: EntityOption | string): string =>
    typeof option === 'string' ? option : (option?.label ?? '');

  protected onSelect(event: NgbTypeaheadSelectItemEvent<EntityOption>): void {
    this.selected = event.item;
    this.onChange(event.item.uid);
  }

  /**
   * Any free typing after a selection invalidates the captured uid until a new result is picked.
   * This guarantees the model only ever holds a uid that came from a real selection.
   */
  protected onInput(value: string): void {
    if (this.selected && value !== this.selected.label) {
      this.selected = null;
      this.onChange('');
    } else if (!value) {
      this.selected = null;
      this.onChange('');
    }
  }

  // ── ControlValueAccessor ────────────────────────────────────────────────────
  writeValue(_uid: string | null): void {
    // The form model is a uid; we cannot reverse a uid back into a label without a lookup.
    // Programmatic writes (e.g. form.reset()) clear the visible selection. Pre-selecting an
    // entity by uid is a future enhancement (would need a resolveFn input).
    this.selected = null;
  }

  registerOnChange(fn: (uid: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled.set(isDisabled);
  }
}
