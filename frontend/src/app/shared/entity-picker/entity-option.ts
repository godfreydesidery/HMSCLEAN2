/**
 * A single selectable option in an {@link EntityPickerComponent}.
 *
 * The picker shows {@link label} (and optional {@link sublabel}) to the user and stores
 * {@link uid} in the form model. Users NEVER see or type the uid — it is captured
 * programmatically when they select a result (project rule: no uid/id entry by hand).
 */
export interface EntityOption {
  /** The opaque identifier written to the form model (a ULID). Never displayed to the user. */
  readonly uid: string;
  /** Primary human-readable text shown in the input and dropdown (e.g. a patient's full name). */
  readonly label: string;
  /** Optional secondary text shown muted in the dropdown (e.g. registration no · phone). */
  readonly sublabel?: string;
}

/**
 * A search function supplied to the picker. Given the user's typed term, it returns the
 * matching options (already mapped to {@link EntityOption}). The picker handles debouncing,
 * the loading spinner, and empty-result display.
 */
export type EntitySearchFn = (term: string) => import('rxjs').Observable<EntityOption[]>;
