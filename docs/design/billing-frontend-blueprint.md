# Billing & Cashiering — Frontend Screen Blueprint
**Bounded context:** Billing & Cashiering  
**Increment:** 04  
**Privilege gate:** `BILL-A` (all screens)  
**Author role:** UX/UI Designer  
**Date:** 2026-06-03  

---

## 0. Shared Concerns

### 0.1 Route Tree

```
/billing                           → redirect → /billing/cashier
/billing/cashier                   → BillingCashierComponent
/billing/receipt/:uid              → BillingReceiptComponent
/billing/reports/collections       → CollectionsReportComponent
```

All three routes are guarded with `privilegeGuard` and `data: { privilege: 'BILL-A' }`, loaded lazily via `billing.routes.ts` from `app.routes.ts` (the placeholder entry already exists in the commented block).

**`billing.routes.ts` structure (spec — no code):**
- Parent route `billing` → `BillingShellComponent` (thin shell, just `<router-outlet>` inside `<div class="billing-shell">`)
- Children: `cashier`, `receipt/:uid`, `reports/collections`
- Child routes inherit the `BILL-A` guard from the parent via `canActivateChild: [privilegeChildGuard]`

### 0.2 Toolbar Navigation Entry

In `app.component.html`, add a navigation link inside the toolbar, rendered only when the privilege is held:

```
<a *appCan="'BILL-A'" mat-button routerLink="/billing/cashier"
   routerLinkActive="active-nav-link"
   aria-label="Billing and cashiering workspace">
  Billing
</a>
```

Position: after the app title, before the spacer. Import `CanDirective` and `RouterLinkActive` in `AppComponent.imports`.

### 0.3 Money Formatting

Create `src/app/core/billing/format-money.ts` — a pure function (not a pipe, to keep OnPush-friendly use in signal expressions):

```
formatMoney(amount: number | undefined | null, currency: string | undefined | null): string
```

- Returns `"${currency} ${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}"` e.g. `TZS 5,000.00`
- Returns `"—"` when either argument is null/undefined
- Currency defaults to `"TZS"` when the MoneyDto.currency field is absent

Also create a `HmisMoneyPipe` (`@Pipe({ name: 'hmisMoney', pure: true, standalone: true })`) that wraps `formatMoney(value.amount, value.currency)` and accepts a `MoneyDto | null | undefined`. Used in templates wherever a `MoneyDto` is available; `formatMoney()` used inline in signal-derived computed strings.

### 0.4 Patient Context Pattern

No patient search exists in this increment. The cashier identifies a patient by entering the `patientUid` (a 26-character ULID) directly.

**PatientContextComponent** — small standalone inline widget (not a route):
- `MatFormField appearance="outline"` with `MatInput`, label "Patient UID", `aria-label="Patient UID (26-character identifier)"`
- Validator: `Validators.required`, `Validators.minLength(26)`, `Validators.maxLength(26)`, pattern `[0-9A-HJKMNP-TV-Z]{26}` (ULID alphabet)
- Error messages: required → "Patient UID is required."; length/pattern → "Enter the 26-character patient UID."
- Submit button (mat-raised-button, color="primary") labeled "Load Bills" with keyboard shortcut `Enter` on the form
- Emits `patientUidConfirmed: EventEmitter<string>` on valid submit
- Input property `loading: boolean` — disables the button and shows inline `mat-spinner diameter="20"` inside the button while bills are being fetched
- Used by BillingCashierComponent; the cashier tab-navigates to it on first load and presses Enter to proceed

### 0.5 Status Badge Component

`BillingStatusBadgeComponent` — inline standalone component accepting `status: string` input. Renders a `<span class="badge badge--{normalized}">` where normalized maps:

| Input value   | CSS class suffix | Display label | Color token               |
|---------------|-----------------|---------------|---------------------------|
| PENDING        | pending         | Pending       | `--mat-sys-secondary`     |
| APPROVED       | approved        | Approved      | `--mat-sys-primary`       |
| PAID           | paid            | Paid          | green (custom: #2e7d32)   |
| UNPAID         | unpaid          | Unpaid        | `--mat-sys-error`         |
| COVERED        | covered         | Covered       | `--mat-sys-tertiary`      |
| VERIFIED       | verified        | Verified      | `--mat-sys-primary`       |
| CANCELLED      | cancelled       | Cancelled     | neutral (#616161)         |

Badge shape: `border-radius: 12px; padding: 2px 10px; font-size: 0.75rem; font-weight: 500; line-height: 1.6`. All badge backgrounds must meet 4.5:1 contrast against badge text.

### 0.6 Error Message Mapping

All components use `extractProblem(err)` and switch on `type` first, then `status`. Never dispatch on human-readable message text.

| `type` (urn)                                | `status` fallback | Display message                                                            |
|---------------------------------------------|-------------------|----------------------------------------------------------------------------|
| `urn:hmis:error:payment-amount-mismatch`    | 422               | "Tendered amount must equal the total ({formatted sum of selected bills})." |
| `urn:hmis:error:bill-not-payable`           | 422               | "One or more selected bills can no longer be paid."                         |
| —                                           | 403               | "You do not have permission to perform this action."                        |
| —                                           | 404               | "The requested record was not found."                                       |
| —                                           | 409               | "A conflict occurred. Reload and try again."                                |
| —                                           | 503 / 0           | "Service unavailable. Please try again shortly."                            |
| —                                           | any other         | "An unexpected error occurred. Please try again."                           |

The `payment-amount-mismatch` message interpolates the locally computed selected-total so the cashier sees the exact expected figure without a round-trip.

### 0.7 Component Style Conventions

All components: `standalone: true`, `ChangeDetectionStrategy.OnPush`, Angular signals for all state (`signal()` / `computed()`), `NonNullableFormBuilder` + `ReactiveFormsModule` for forms. Inline `template` and `styles` in the component decorator. No third-party UI libraries beyond Angular Material. Error text: `color: #c62828; font-size: 0.875rem`. Cards used as primary content containers.

---

## Screen 1 — Cashier Billing Workspace

**Route:** `/billing/cashier`  
**Component:** `BillingCashierComponent`  
**File path:** `src/app/features/billing/cashier/billing-cashier.component.ts`  
**Role:** Cashier (privilege `BILL-A`)  
**Primary use:** Identify a patient by UID, view all collectable bills across their invoices, select bills to pay, initiate payment, and initiate charge cancellation.

### 1.1 Layout

```
+------------------------------------------------------------------+
| mat-toolbar (inherited from app shell)                            |
+------------------------------------------------------------------+
| <main class="app-content">                                        |
|  <div class="billing-cashier-wrapper">                            |
|                                                                   |
|   +------------------------------------------------------------+  |
|   | mat-card class="patient-context-card"                      |  |
|   |  mat-card-header: "Patient Bills"                          |  |
|   |  mat-card-content:                                         |  |
|   |    <app-patient-context>  [patientUid input + Load button] |  |
|   +------------------------------------------------------------+  |
|                                                                   |
|   [Loading spinner — when bills are loading]                      |
|   [Error banner — when load fails]                                |
|   [Empty state card — when bills array is empty]                  |
|                                                                   |
|   +------------------------------------------------------------+  |
|   | mat-card class="bills-card"  [shown when bills.length > 0] |  |
|   |  mat-card-header:                                          |  |
|   |   "Bills for patient: {patientUid truncated to 8+…}"       |  |
|   |   mat-card-subtitle: "{N} bill(s) loaded"                  |  |
|   |                                                            |  |
|   |  mat-card-content:                                         |  |
|   |   +--------------------------------------------------+     |  |
|   |   | mat-table [dataSource]="billRows()"              |     |  |
|   |   |  <col> checkbox (MatCheckbox)                    |     |  |
|   |   |  <col> Description                               |     |  |
|   |   |  <col> Qty                                       |     |  |
|   |   |  <col> Amount                                    |     |  |
|   |   |  <col> Status                                    |     |  |
|   |   |  <col> Coverage                                  |     |  |
|   |   |  <col> Actions                                   |     |  |
|   |   +--------------------------------------------------+     |  |
|   |                                                            |  |
|   |  mat-card-actions:                                         |  |
|   |   Selected: {N} bills  |  Total: TZS X,XXX.XX             |  |
|   |   [Record Payment]  (mat-raised-button color="primary")    |  |
|   |   [Clear Selection] (mat-stroked-button)                   |  |
|   +------------------------------------------------------------+  |
|                                                                   |
|  </div>                                                           |
| </main>                                                           |
+------------------------------------------------------------------+
```

### 1.2 Angular Material Components

- `MatCardModule` — patient-context card, bills card
- `MatTableModule` — bills table (`<mat-table>`)
- `MatCheckboxModule` — select-all header checkbox + per-row checkboxes
- `MatButtonModule` — "Load Bills", "Record Payment", "Clear Selection", "Cancel charge" row action
- `MatIconModule` — icons in action buttons (`payments`, `cancel`, `receipt_long`)
- `MatProgressSpinnerModule` — full-card loading state (`diameter="40"`)
- `MatFormFieldModule` + `MatInputModule` — patient UID input (via PatientContextComponent)
- `MatSnackBarModule` — transient success confirmations (e.g. "Payment recorded successfully")
- `MatTooltipModule` — tooltip on truncated patient UID and on disabled checkboxes
- `MatDividerModule` — divider above card actions row

### 1.3 Signal State Model

```
// Patient context
patientUid          = signal<string | null>(null)

// Load state
loading             = signal<boolean>(false)
loadError           = signal<string | null>(null)

// Data
invoices            = signal<PatientInvoiceDto[]>([])

// Derived flat bill rows (computed from invoices)
billRows            = computed<BillRow[]>(...)
  // BillRow = { billUid, invoiceUid, description, qty, amount, currency,
  //             billStatus, coverageStatus, selectable: boolean }
  // selectable = coverageStatus === 'UNPAID' and billStatus not 'CANCELLED'

// Selection
selectedBillUids    = signal<Set<string>>(new Set())

// Derived totals
selectedCount       = computed(() => selectedBillUids().size)
selectedTotal       = computed(() => sum of amount for selected rows)
selectedCurrency    = computed(() => currency of first selected row, or 'TZS')

// Payment flow (managed by dialog — see Screen 2)
paymentPending      = signal<boolean>(false)
```

The `billRows` computed signal flattens `invoices().flatMap(inv => inv.details ?? [])` and decorates each row with `invoiceUid` and `selectable` flag. Non-selectable rows (already paid, covered, verified, cancelled) show a greyed-out checkbox with `aria-disabled="true"` and a tooltip explaining why (e.g. "Already covered by insurance").

### 1.4 Table Column Specification

| Column key     | Header             | Cell content                                               | Width    | Align  |
|----------------|--------------------|------------------------------------------------------------|----------|--------|
| `select`       | (checkbox)         | `MatCheckbox` — disabled if `!row.selectable`              | 48px     | center |
| `description`  | Description        | `row.description` — truncate at 40 chars with `MatTooltip` | flex-1   | start  |
| `qty`          | Qty                | `row.qty`                                                  | 64px     | end    |
| `amount`       | Amount             | `formatMoney(row.amount, row.currency)`                    | 140px    | end    |
| `billStatus`   | Status             | `<app-billing-status-badge [status]="row.billStatus">`     | 110px    | center |
| `coverageStatus` | Coverage         | `<app-billing-status-badge [status]="row.coverageStatus">` | 110px    | center |
| `actions`      | (empty)            | "Cancel" icon-button (mat-icon-button)                     | 56px     | center |

**Select-all behavior:** Header checkbox is indeterminate when some (not all) selectable rows are checked. Clicking it toggles all selectable rows only. Non-selectable rows are never toggled by select-all.

**Sticky header:** `<mat-header-row>` is sticky (`position: sticky; top: 0; z-index: 2`) so the column headers remain visible when the table scrolls.

**Table `aria-label`:** `"Patient bills table. {N} rows."` — updated reactively via a computed signal bound to `[attr.aria-label]`.

### 1.5 Card Actions Row

```
+-----------------------------------------------------+
| Selected: 3 bills  |  Total: TZS 12,500.00          |
|                    [Clear Selection] [Record Payment] |
+-----------------------------------------------------+
```

- "Total" text is `font-weight: 600; font-size: 1rem`
- "Record Payment" is `mat-raised-button color="primary"`, disabled when `selectedCount() === 0` or `paymentPending()`
- "Clear Selection" is `mat-stroked-button`, disabled when `selectedCount() === 0`
- Both buttons have `aria-label` attributes
- The row uses `display: flex; justify-content: space-between; align-items: center; padding: 0.75rem 1rem`

### 1.6 States

**Loading state:**
```
<div class="state-container" role="status" aria-live="polite" aria-label="Loading patient bills">
  <mat-spinner diameter="40"></mat-spinner>
  <p>Loading bills…</p>
</div>
```
Shown when `loading() === true`. Spinner is centered in the bills card content area.

**Empty state** (patient found but no bills):
```
<div class="empty-state" role="status">
  <mat-icon aria-hidden="true">receipt_long</mat-icon>
  <p>No bills found for this patient.</p>
</div>
```

**Error state:**
```
<p class="error-msg" role="alert">{{ loadError() }}</p>
```
Error message string is set via the RFC7807 mapping table (section 0.6). Includes a "Retry" mat-stroked-button that re-invokes `loadBills()`.

**Data state:** bills card shown when `billRows().length > 0`.

### 1.7 API Interactions

**Load bills:** On `patientUidConfirmed` event from PatientContextComponent:
1. `loading.set(true); loadError.set(null); invoices.set([])`
2. Call `defaultService.listInvoices({ patientUid: uid })` 
3. `next`: `invoices.set(data); loading.set(false)`
4. `error`: `loading.set(false); loadError.set(mapError(err))` via section 0.6

**Cancel charge:** "Cancel" button on a row opens the cancellation dialog (Screen 4) passing `billUid`. On dialog close with a `CancellationResultDto`, reload bills for the current patient.

**Record payment:** "Record Payment" button opens the payment dialog (Screen 2) passing `selectedBillUids()` and `selectedTotal()`. On dialog close with a `PatientPaymentDto`, reload bills and navigate to `/billing/receipt/{payment.uid}` if `payment.uid` is present (the receipt UID is same as payment UID per the `getReceipt` contract).

### 1.8 Accessibility

- Patient UID input: `aria-required="true"`, `aria-describedby` pointing to the error element
- Table: `role="grid"`, `aria-rowcount` bound to `billRows().length + 1` (header), each row `role="row"`
- Checkboxes: `aria-label="Select bill: {row.description}"` for each row checkbox; header checkbox `aria-label="Select all payable bills"`
- Disabled checkboxes: `aria-disabled="true"` with `MatTooltip` explaining the reason
- Card actions: `aria-live="polite"` region wrapping selected count and total, so screen readers announce changes when selection changes
- Keyboard: Tab order — patient UID input → Load Bills button → select-all checkbox → row 1 checkbox → row 1 cancel button → row 2 ... → Clear Selection → Record Payment
- All interactive targets minimum 44x44 CSS px (mat-icon-button default is 40px — apply `class="large-icon-btn"` with custom `min-width: 44px; min-height: 44px` in component styles)
- Error alert uses `role="alert"` so screen readers announce it immediately on load failure

### 1.9 Responsive Breakpoints

- **Desktop (≥1024px):** Full table with all columns. Bills card max-width unrestricted.
- **Tablet (768px–1023px):** Hide `qty` column (`display: none` via class bound to breakpoint signal). Patient context card and bills card stack vertically full-width.
- **Below 768px:** Not a primary target (cashier workstations), but the table gains horizontal scroll: `overflow-x: auto` on the card content wrapper.

---

## Screen 2 — Record Payment Dialog

**Trigger:** "Record Payment" button on Screen 1  
**Component:** `RecordPaymentDialogComponent`  
**File path:** `src/app/features/billing/cashier/record-payment-dialog.component.ts`  
**Opened via:** `MatDialog.open(RecordPaymentDialogComponent, { data: { billUids, total, currency } })`  
**Returns on close:** `PatientPaymentDto | null` (null = user cancelled)

### 2.1 Layout

```
+-----------------------------------------------+
| mat-dialog-title: "Record Payment"             |
+-----------------------------------------------+
| mat-dialog-content:                            |
|                                                |
|  +-----------------------------------------+  |
|  | Bills being paid:                        |  |
|  |  • {description} — TZS {amount}         |  |
|  |  • ...                                   |  |
|  +-----------------------------------------+  |
|                                                |
|  Total: TZS 12,500.00   (large, bold)          |
|                                                |
|  [mat-form-field] Tendered Amount              |
|    matInput type="number" step="0.01"          |
|    default value = exact total                 |
|    hint: "Enter amount in TZS"                 |
|                                                |
|  [mat-form-field] Payment Mode                 |
|    mat-select: CASH (default) | INSURANCE      |
|                                                |
|  [error paragraph if API returns 422]          |
|                                                |
+-----------------------------------------------+
| mat-dialog-actions align="end":               |
|  [Cancel]  [Confirm Payment]                   |
+-----------------------------------------------+
```

### 2.2 Angular Material Components

- `MatDialogModule` — dialog container, title, content, actions
- `MatFormFieldModule` + `MatInputModule` — tendered amount field
- `MatSelectModule` — payment mode selector
- `MatButtonModule` — Cancel (mat-button), Confirm Payment (mat-raised-button color="primary")
- `MatProgressSpinnerModule` — inline spinner inside Confirm button while submitting
- `MatListModule` — `<mat-list>` for the selected bills summary

### 2.3 Signal State Model

```
// Injected via MAT_DIALOG_DATA
data: { billUids: string[], total: number, currency: string, billSummaries: { description: string, amount: number }[] }

// Form
form = NonNullableFormBuilder.group({
  tenderedAmount: [data.total, [Validators.required, Validators.min(0.01)]],
  paymentMode:    ['CASH' as const, Validators.required]
})

// Submission state
submitting = signal<boolean>(false)
apiError   = signal<string | null>(null)
```

### 2.4 Form Validation

- `tenderedAmount`: required; min 0.01; type number. Client-side validation does NOT enforce exact-tender match (the server enforces it); the client merely shows the default equal to total to guide the cashier. This is intentional: the exact-tender rule is a business rule validated server-side.
- `paymentMode`: required; one of `CASH | INSURANCE`

On submit:
1. Mark all fields touched, check `form.invalid` → return early if invalid
2. `submitting.set(true); apiError.set(null)`
3. Build `RecordPaymentRequest`:
   ```
   {
     billUids: data.billUids,
     tenderedTotal: { amount: form.value.tenderedAmount, currency: data.currency },
     paymentMode: form.value.paymentMode
   }
   ```
4. Call `defaultService.recordPayment({ uid: /* invoiceUid passed in data */, recordPaymentRequest })`  
   Note: `recordPayment` takes `{ uid, recordPaymentRequest }` — `uid` here is the invoice UID. The caller (Screen 1) must pass the relevant invoice UID. If bills span multiple invoices, the engineer must clarify with the backend team; for the current design, assume all selected bills belong to one invoice (the `listInvoices` response groups bills by invoice, and the cashier selects within one invoice at a time — flag this assumption for BA review).
5. `next`: `submitting.set(false); dialogRef.close(result)`
6. `error`: `submitting.set(false); apiError.set(mapError422(err, data.total, data.currency))`

### 2.5 Error Mapping for 422

```
mapError422(err, total, currency):
  problem = extractProblem(err)
  if problem.type === 'urn:hmis:error:payment-amount-mismatch':
    return `Tendered amount must equal the total (${formatMoney(total, currency)}).`
  if problem.type === 'urn:hmis:error:bill-not-payable':
    return 'One or more selected bills can no longer be paid.'
  // fallthrough to generic
  return generic message from section 0.6
```

Error rendered as `<p class="error-msg" role="alert">{{ apiError() }}</p>` inside `mat-dialog-content`, visible only when `apiError() !== null`.

### 2.6 Accessibility

- Dialog: `aria-labelledby` pointing to `mat-dialog-title` id; `aria-describedby` pointing to the bills summary paragraph
- `MatDialogTitle` sets the `id` automatically; reference it in `MatDialog.open` config: `ariaLabelledBy: 'record-payment-dialog-title'`
- Focus on open: auto-focused to the tendered-amount input (set `cdkFocusInitial` on that field)
- Focus on close: returns to the "Record Payment" button that triggered the dialog (MatDialog default behavior)
- Confirm button: `aria-busy="true"` when `submitting()` is true
- Cancel button: always enabled; keyboard Escape closes the dialog via MatDialog default

### 2.7 UX Notes

- The tendered-amount field pre-fills with the exact total. This guides the cashier to the exact amount, reducing the `payment-amount-mismatch` 422 rate in practice.
- The payment mode selector defaults to CASH. Insurance selection is available but clinical coverage workflow (authorizations) is out of scope for this increment — flag this as a deferred concern.
- Bills summary list is read-only; it is not interactive.

---

## Screen 3 — POS Receipt

**Route:** `/billing/receipt/:uid`  
**Component:** `BillingReceiptComponent`  
**File path:** `src/app/features/billing/receipt/billing-receipt.component.ts`  
**Role:** Cashier, any authenticated user with `BILL-A`  
**Primary use:** View and print a payment receipt identified by `uid` (the payment UID returned by `recordPayment`).

### 3.1 Layout

```
+-------------------------------------------------------+
| mat-toolbar [hidden on print]                          |
|  [← Back to Cashier]  mat-icon-button                 |
|  "Receipt"                                             |
|  [Print]  mat-raised-button                            |
+-------------------------------------------------------+
|                                                        |
| <main class="app-content">                             |
|   +--------------------------------------------------+ |
|   | mat-card class="receipt-card"  id="receipt-card" | |
|   |                                                  | |
|   |  +--------------------------------------------+  | |
|   |  | .receipt-header (print only: hospital name)|  | |
|   |  |  ZANA HMIS                                 |  | |
|   |  |  [hospital address — from company profile  |  | |
|   |  |   if loaded, else omit]                    |  | |
|   |  +--------------------------------------------+  | |
|   |                                                  | |
|   |  mat-card-header:                                | |
|   |   mat-card-title: "Official Receipt"             | |
|   |   mat-card-subtitle: "No. {receiptNo}"           | |
|   |                                                  | |
|   |  mat-card-content:                               | |
|   |   mat-divider                                    | |
|   |   [grid: 2 columns on desktop, 1 on print]       | |
|   |   Date:         {businessDate}                   | |
|   |   Cashier:      {cashier}                        | |
|   |   Patient UID:  {patientUid}                     | |
|   |   Payment Mode: {paymentMode}                    | |
|   |   Status:       <app-billing-status-badge>       | |
|   |   mat-divider                                    | |
|   |   Amount:       TZS {amount}  (large, bold)      | |
|   |   mat-divider                                    | |
|   |   Business Day: {businessDayUid}                 | |
|   |                                                  | |
|   |  mat-card-actions [hidden on print]:             | |
|   |   [Print Receipt]  mat-raised-button color="primary" |
|   +--------------------------------------------------+ |
|                                                        |
+-------------------------------------------------------+
```

### 3.2 Angular Material Components

- `MatCardModule` — receipt card container
- `MatButtonModule` — Back button, Print Receipt button
- `MatIconModule` — `print`, `arrow_back` icons
- `MatProgressSpinnerModule` — loading state
- `MatDividerModule` — section separators within receipt
- `MatSnackBarModule` — error snack bar for load failures (non-critical: receipt may still be printable after partial load)

### 3.3 Signal State Model

```
uid        = signal<string>('')       // from ActivatedRoute params, set in ngOnInit
loading    = signal<boolean>(true)
error      = signal<string | null>(null)
receipt    = signal<ReceiptDto | null>(null)
```

`ReceiptDto` fields: `receiptNo, patientUid, amount: MoneyDto, paymentMode, status, cashier, businessDayUid, businessDate`

On `ngOnInit`:
1. Read `:uid` from `ActivatedRoute.snapshot.paramMap`
2. `loading.set(true)`
3. Call `defaultService.getReceipt({ uid })`
4. `next`: `receipt.set(data); loading.set(false)`
5. `error`: `loading.set(false); error.set(mapError(err))`

### 3.4 Print Styles (`@media print`)

All print rules live inside the component's `styles` array:

```css
@media print {
  mat-toolbar, .print-hidden { display: none !important; }
  .app-content { padding: 0; margin: 0; }
  .receipt-card {
    box-shadow: none;
    border: 1px solid #ccc;
    max-width: 100%;
    margin: 0;
    padding: 1rem;
  }
  .receipt-header { display: block !important; }
  mat-card-actions { display: none !important; }
  body { background: white; }
}
```

The `.receipt-header` div is `display: none` in screen view and `display: block` in print view, showing the hospital name and address. Hospital name is hard-coded as "Zana HMIS" (or loaded from company profile if the `DefaultService.getCompanyProfile()` call has already resolved in app state — inject `CompanyProfile` via a lightweight service or use the already-resolved value from app state if available; if not available, use the static fallback "Zana HMIS Hospital").

**Print button:** `<button mat-raised-button color="primary" (click)="window.print()" class="print-hidden">`. The method in the component simply calls `window.print()`.

### 3.5 States

**Loading:** Centered `mat-spinner diameter="48"` with `aria-label="Loading receipt"`. Full card height.

**Error:** `<p class="error-msg" role="alert">{{ error() }}</p>` with a "Back to Cashier" link.

**Data:** Receipt card rendered as described.

### 3.6 Money and Date Formatting

- Amount: `formatMoney(receipt()?.amount?.amount, receipt()?.amount?.currency)`
- `businessDate`: format using `new Date(receipt()!.businessDate!).toLocaleDateString('en-GB', { day:'2-digit', month:'short', year:'numeric' })` — e.g. "03 Jun 2026"
- `businessDayUid`: display as-is (it is a UID, not a numeric id — per the "never display internal numeric ids" rule)

### 3.7 Accessibility

- `mat-card` for the receipt region has `role="region"` and `aria-label="Payment receipt {receiptNo}"`
- Print button: `aria-label="Print this receipt"`
- Back button: `aria-label="Return to cashier workspace"`
- All field labels are `<dt>` elements in a `<dl>` description list; values are `<dd>`. This provides semantic structure for screen readers and prints cleanly.

---

## Screen 4 — Cancellation and Credit Note Dialog

**Trigger:** "Cancel" icon-button on a bill row in Screen 1  
**Component:** `CancelChargeDialogComponent`  
**File path:** `src/app/features/billing/cashier/cancel-charge-dialog.component.ts`  
**Opened via:** `MatDialog.open(CancelChargeDialogComponent, { data: { billUid, description, amount, currency } })`  
**Returns on close:** `CancellationResultDto | null`

### 4.1 Layout

```
+--------------------------------------------------+
| mat-dialog-title: "Cancel Charge"                |
+--------------------------------------------------+
| mat-dialog-content:                              |
|                                                  |
|  [Warning banner]                                |
|  "You are cancelling: {description}"             |
|  "Amount: TZS {amount}"                          |
|  "If this bill has been paid, a credit note      |
|   will be issued automatically."                 |
|                                                  |
|  [mat-form-field] Cancellation Reason            |
|    mat-label: "Reason (required)"                |
|    textarea matInput                             |
|    maxlength="255"                               |
|    mat-hint: "{N}/255"                           |
|    cdkFocusInitial                               |
|                                                  |
|  [error paragraph]                               |
|                                                  |
|  [result panel — shown after success]            |
|   "Charge cancelled."                            |
|   If creditNote present:                         |
|    "Credit Note: PCN {no}"                       |
|    "Amount: TZS {creditNote.amount}"             |
|    "Status: PENDING"                             |
|   If no creditNote:                              |
|    "No refund due (bill was not previously paid)."|
|                                                  |
+--------------------------------------------------+
| mat-dialog-actions align="end":                  |
|  [Close]       [Confirm Cancellation]            |
|  (shown before result; after result: [Close])    |
+--------------------------------------------------+
```

### 4.2 Angular Material Components

- `MatDialogModule` — dialog container
- `MatFormFieldModule` + `MatInputModule` with `cdkTextareaAutosize` — reason textarea
- `MatButtonModule` — Close, Confirm Cancellation
- `MatProgressSpinnerModule` — inline spinner in Confirm button
- `MatIconModule` — `warning_amber` icon in warning banner
- `MatDividerModule` — separates bill info from reason field

### 4.3 Signal State Model

```
// Injected via MAT_DIALOG_DATA
data: { billUid: string, description: string, amount: number, currency: string }

form = NonNullableFormBuilder.group({
  reference: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(255)]]
})

submitting  = signal<boolean>(false)
apiError    = signal<string | null>(null)
result      = signal<CancellationResultDto | null>(null)
```

When `result()` is not null, the dialog transitions to a read-only result state: the form is hidden, the result panel is shown, and the actions row shows only "Close" (which closes the dialog returning `result()`).

### 4.4 Cancellation Flow

1. Validate `form.controls.reference` — required, 1–255 chars
2. `submitting.set(true); apiError.set(null)`
3. Call `defaultService.cancelCharge({ billUid: data.billUid, cancelChargeRequest: { reference: form.value.reference } })`
4. `next`: `submitting.set(false); result.set(data)` — dialog transitions to result state
5. `error`: `submitting.set(false); apiError.set(mapError(err))`

### 4.5 Result Display Logic

```
if result().creditNote exists:
  show credit-note panel with:
    no:     result().creditNote.no
    amount: formatMoney(result().creditNote.amount.amount, result().creditNote.amount.currency)
    status: "PENDING"  (always PENDING on creation)
else:
  show "No refund due (bill was not previously paid)."
```

The `billStatus` in `CancellationResultDto` is informational only; do not display it to the cashier (it is an internal system state).

### 4.6 Validation — Reason Field

- Required — error: "Cancellation reason is required."
- maxLength 255 — error: "Reason cannot exceed 255 characters."
- `mat-hint` shows live character count: `"{{ form.controls.reference.value.length }}/255"`. This is a signal-safe expression via `form.valueChanges` tracked as a signal using `toSignal`.

### 4.7 Accessibility

- Dialog `aria-labelledby` pointing to `mat-dialog-title`
- Warning banner: `role="alert"` — static, announces on dialog open
- Reason field: `aria-label="Cancellation reason"`, `aria-required="true"`, `aria-describedby` pointing to hint and error elements
- On result: `aria-live="polite"` region wrapping the result panel so screen readers announce the outcome
- Confirm button: `aria-busy="true"` when `submitting()`
- Keyboard: Escape cancels and closes (returns `null`). Enter in the textarea does not submit (it is a multiline textarea); submit only via the Confirm button.
- `cdkFocusInitial` on the reason textarea so focus lands immediately when dialog opens

---

## Screen 5 — EOD Collections Report

**Route:** `/billing/reports/collections`  
**Component:** `CollectionsReportComponent`  
**File path:** `src/app/features/billing/reports/collections-report.component.ts`  
**Role:** Cashier, supervisor (privilege `BILL-A`)  
**Primary use:** Run an end-of-day or date-range collections report aggregated by item and payment channel; print the cash-up sheet.

### 5.1 Layout

```
+------------------------------------------------------------------+
| mat-toolbar (app shell)                                           |
+------------------------------------------------------------------+
| <main class="app-content">                                        |
|  <div class="report-wrapper">                                     |
|                                                                   |
|   +------------------------------------------------------------+  |
|   | mat-card class="report-filter-card"                        |  |
|   |  mat-card-header: "Collections Report"                     |  |
|   |  mat-card-content:                                         |  |
|   |   <form class="filter-form">                               |  |
|   |    [From date]  mat-datepicker   required                  |  |
|   |    [To date]    mat-datepicker   required                  |  |
|   |    [Cashier]    mat-form-field matInput  optional          |  |
|   |    [Run Report] mat-raised-button color="primary"          |  |
|   |   </form>                                                  |  |
|   +------------------------------------------------------------+  |
|                                                                   |
|   [Loading spinner — centered]                                    |
|   [Error banner]                                                  |
|   [Empty state — no results]                                      |
|                                                                   |
|   +------------------------------------------------------------+  |
|   | mat-card class="report-results-card"                       |  |
|   |  mat-card-header:                                          |  |
|   |   "Results: {from} – {to}"                                 |  |
|   |   [Print] mat-stroked-button class="print-hidden"          |  |
|   |  mat-card-content:                                         |  |
|   |   +--------------------------------------------------+     |  |
|   |   | mat-table [dataSource]="reportRows()"            |     |  |
|   |   |  <col> Item Name                                 |     |  |
|   |   |  <col> Payment Channel                           |     |  |
|   |   |  <col> Amount (right-aligned)                    |     |  |
|   |   |  ------------------------------------------------ |    |  |
|   |   |  [Grand Total row — mat-footer-row]              |     |  |
|   |   |    "TOTAL"   |   —   |  TZS {grandTotal}         |     |  |
|   |   +--------------------------------------------------+     |  |
|   +------------------------------------------------------------+  |
|                                                                   |
|  </div>                                                           |
| </main>                                                           |
+------------------------------------------------------------------+
```

### 5.2 Angular Material Components

- `MatCardModule` — filter card, results card
- `MatFormFieldModule` + `MatInputModule` — cashier filter input
- `MatDatepickerModule` + `MatNativeDateModule` (or `MatMomentDateModule`) — from/to date pickers. Use `[max]="today"` on both to prevent future dates. Use `[max]` on the "to" field equal to today; use `[min]` on the "from" field unrestricted (historical data).
- `MatTableModule` — results table with `matFooterRow` for grand total
- `MatButtonModule` — Run Report (mat-raised-button), Print (mat-stroked-button)
- `MatProgressSpinnerModule` — loading state
- `MatIconModule` — `print`, `table_chart` icons
- `MatDividerModule` — between filter and results cards

### 5.3 Signal State Model

```
today       = signal<Date>(new Date())

form = NonNullableFormBuilder.group({
  from:    [null as Date | null, Validators.required],
  to:      [null as Date | null, Validators.required],
  cashier: ['']  // optional free-text
})

// Date comparison validator: 'to' must be >= 'from'
// Applied as a group-level cross-field validator

loading     = signal<boolean>(false)
runError    = signal<string | null>(null)
reportRows  = signal<CollectionReportRow[]>([])
hasRun      = signal<boolean>(false)   // controls empty vs not-yet-run display

grandTotal  = computed(() =>
  reportRows().reduce((sum, row) => sum + (row.amount ?? 0), 0)
)

lastFrom    = signal<string | null>(null)   // ISO date string of last run
lastTo      = signal<string | null>(null)
```

### 5.4 Filter Form Validation

- `from`: required — error: "Start date is required."
- `to`: required — error: "End date is required."
- Cross-field: `to < from` → group error `dateRangeInvalid` — show below the "to" field: "End date must be on or after the start date."
- `cashier`: optional, free text, max 100 chars (soft limit, not enforced server-side but prevents unusably long strings)

Date value conversion: Angular Material datepicker provides a `Date` object; convert to ISO string `yyyy-MM-dd` using `date.toISOString().split('T')[0]` before passing to the API.

### 5.5 API Interaction

On "Run Report":
1. Validate form → mark all touched, return if invalid
2. `loading.set(true); runError.set(null); reportRows.set([]); hasRun.set(false)`
3. Build params:
   ```
   {
     from: toIsoDate(form.value.from),
     to:   toIsoDate(form.value.to),
     cashier: form.value.cashier || undefined
   }
   ```
4. Call `defaultService.getCollectionsReport(params)`
5. `next`: `reportRows.set(data); hasRun.set(true); loading.set(false); lastFrom/lastTo.set(...)`
6. `error`: `loading.set(false); hasRun.set(false); runError.set(mapError(err))`

### 5.6 Table Specification

| Column key  | Header           | Cell content                          | Width   | Align |
|-------------|------------------|---------------------------------------|---------|-------|
| `itemName`  | Item             | `row.itemName ?? '—'`                 | flex-1  | start |
| `channel`   | Payment Channel  | `row.paymentChannel ?? '—'`           | 160px   | start |
| `amount`    | Amount           | `formatMoney(row.amount, 'TZS')`      | 160px   | end   |

**Footer row (grand total):**
- `matFooterRowDef`: all three columns
- `itemName` cell: `<strong>TOTAL</strong>`
- `channel` cell: `—`
- `amount` cell: `<strong>{{ formatMoney(grandTotal(), 'TZS') }}</strong>` — font-size 1rem, font-weight 700
- Footer row has `class="total-row"` with `background-color: var(--mat-sys-surface-variant)` and `border-top: 2px solid var(--mat-sys-outline)`

**Empty result state** (after running with no rows):
```
<div class="empty-state" role="status">
  <mat-icon aria-hidden="true">table_chart</mat-icon>
  <p>No collections found for this period.</p>
</div>
```
Shown when `hasRun() && reportRows().length === 0`.

**Not-yet-run state** (before first submit):
Results card is hidden entirely. The filter card shows an informational hint below the form: "Select a date range and click Run Report."

### 5.7 Print Styles

```css
@media print {
  .print-hidden, mat-toolbar, .report-filter-card { display: none !important; }
  .report-results-card {
    box-shadow: none;
    border: 1px solid #ddd;
    padding: 0.5rem;
    margin: 0;
  }
  .report-print-header { display: block !important; }
  body { background: white; font-size: 11pt; }
}
```

A `.report-print-header` div (hidden in screen view, visible in print) displays:
- Hospital name: "Zana HMIS Hospital"
- Report title: "Collections Report"
- Period: "{lastFrom()} to {lastTo()}"
- Printed on: current datetime

Print button calls `window.print()`. Print button has class `print-hidden` so it does not appear in the printout.

### 5.8 Accessibility

- Filter form: each `mat-form-field` has `mat-label`; date pickers have `aria-label` including "start date" / "end date"
- Cross-field validation error is associated with the "to" field via `aria-describedby`
- Results table: `aria-label="Collections report results"` — updated with period when `hasRun()` is true: `aria-label="Collections report: {from} to {to}"`
- Footer row: `role="row"` with `aria-label="Grand total: {formatted amount}"`
- Grand total cell: `aria-live="polite"` so screen readers announce when the value updates after a run
- "Run Report" button: `aria-busy="true"` when `loading()` is true
- All interactive targets ≥ 44x44 CSS px

---

## 6. Route File Specification

**`billing.routes.ts`** (`src/app/features/billing/billing.routes.ts`):

```
Route array: BILLING_ROUTES
- path: ''          → redirect to 'cashier'
- path: 'cashier'   → BillingCashierComponent
    canActivate: [privilegeGuard], data: { privilege: 'BILL-A' }
    title: 'Cashier — Billing'
- path: 'receipt/:uid' → BillingReceiptComponent
    canActivate: [privilegeGuard], data: { privilege: 'BILL-A' }
    title: 'Receipt — Billing'
- path: 'reports/collections' → CollectionsReportComponent
    canActivate: [privilegeGuard], data: { privilege: 'BILL-A' }
    title: 'Collections Report — Billing'
```

All components lazy-loaded via `loadComponent`. The parent entry in `app.routes.ts` is:

```
{
  path: 'billing',
  canActivate: [privilegeGuard],
  data: { privilege: 'BILL-A' },
  loadChildren: () => import('./features/billing/billing.routes').then(m => m.BILLING_ROUTES)
}
```

This entry is already present as a comment in the current `app.routes.ts` — uncomment it.

---

## 7. Shared Billing Module Files

| File                                                                    | Purpose                                                                 |
|-------------------------------------------------------------------------|-------------------------------------------------------------------------|
| `src/app/features/billing/billing.routes.ts`                            | Route declarations for the billing bounded context                      |
| `src/app/features/billing/cashier/billing-cashier.component.ts`         | Screen 1 — main cashier workspace                                       |
| `src/app/features/billing/cashier/record-payment-dialog.component.ts`   | Screen 2 — record payment dialog                                        |
| `src/app/features/billing/cashier/cancel-charge-dialog.component.ts`    | Screen 4 — cancellation + credit note dialog                            |
| `src/app/features/billing/receipt/billing-receipt.component.ts`         | Screen 3 — POS receipt view + print                                     |
| `src/app/features/billing/reports/collections-report.component.ts`      | Screen 5 — EOD collections report                                       |
| `src/app/core/billing/format-money.ts`                                  | Pure `formatMoney(amount, currency): string` function                   |
| `src/app/core/billing/hmis-money.pipe.ts`                               | `HmisMoneyPipe` wrapping `formatMoney`; standalone, pure                |
| `src/app/features/billing/shared/billing-status-badge.component.ts`     | Status badge for bill/invoice/coverage status values                    |
| `src/app/features/billing/shared/patient-context.component.ts`          | Patient UID input widget emitting `patientUidConfirmed`                 |

---

## 8. WCAG 2.1 AA Compliance Checklist (per screen)

### All Screens
- [ ] All interactive elements have visible focus indicators (Material default `outline` in high-contrast mode)
- [ ] Color alone is never the sole indicator of meaning (status badges also carry text labels)
- [ ] All form fields have associated `mat-label` or explicit `aria-label`
- [ ] Error messages use `role="alert"` or `aria-live="assertive"`
- [ ] Minimum touch target 44x44 CSS px for all buttons
- [ ] Text contrast ratio ≥ 4.5:1 (azure-blue Material prebuilt theme satisfies this; verify badge custom colors)

### Screen 1 (Cashier Workspace)
- [ ] Table has `aria-label` with row count
- [ ] Select-all / per-row checkbox aria-labels describe the action and bill
- [ ] Disabled checkboxes carry `aria-disabled` and tooltip
- [ ] Selected total `aria-live="polite"` region announces changes
- [ ] Loading spinner has `aria-label` and `role="status"`

### Screen 2 (Record Payment Dialog)
- [ ] Dialog `aria-labelledby` connected to title
- [ ] Focus moves to tendered-amount on open (`cdkFocusInitial`)
- [ ] Focus returns to trigger button on close
- [ ] 422 error `role="alert"` inside dialog

### Screen 3 (Receipt)
- [ ] Receipt region `role="region"` with `aria-label`
- [ ] `<dl>/<dt>/<dd>` structure for key-value fields
- [ ] Print button `aria-label` describes action
- [ ] `@media print` hides toolbar and navigation — only receipt content prints

### Screen 4 (Cancellation Dialog)
- [ ] Focus on textarea on open
- [ ] Character count live-region
- [ ] Result panel `aria-live="polite"`
- [ ] Warning banner `role="alert"`

### Screen 5 (Collections Report)
- [ ] Date pickers have descriptive `aria-label`
- [ ] Cross-field error associated with relevant field via `aria-describedby`
- [ ] Footer row labeled as grand total
- [ ] Grand total `aria-live="polite"`

---

## 9. Assumptions and Flags for BA/Backend Review

1. **Cross-invoice payment:** `recordPayment` takes a single invoice UID (`uid` param). If a cashier selects bills spanning multiple invoices, multiple `recordPayment` calls would be needed — or backend must support multi-invoice payment. **This design assumes single-invoice selection. Flag for BA confirmation.**

2. **Receipt UID = Payment UID:** The design routes to `/billing/receipt/{payment.uid}` after `recordPayment` returns `PatientPaymentDto`. This assumes `getReceipt({ uid: paymentDto.uid })` works — i.e., the receipt UID is the same as the payment UID. **Confirm with backend team.**

3. **Insurance payment:** The payment mode supports `INSURANCE` in the API, but authorization workflows are not yet designed. Selecting INSURANCE at the cashier point may be premature. **Flag for BA: should INSURANCE mode be disabled at cashier level until insurance module is built?**

4. **`listInvoices` status filter:** The API supports an optional `status` filter. The current design loads all invoices (no filter) so the cashier sees the full picture. If APPROVED invoices should not be displayed to the cashier, a filter should be added. **Confirm with BA.**

5. **Cashier filter in collections report:** The `cashier` param is a free-text string per the API contract. The design provides a text input. If the system should restrict this to known cashier usernames (from an IAM list), a `mat-autocomplete` would be more appropriate but requires an IAM endpoint not yet in scope. **Flag as deferred enhancement.**

6. **`amountPaid` on invoice:** `PatientInvoiceDto.amountPaid` is available but not displayed in the current bill table (which shows individual bill amounts). If a summary "amount already paid on this invoice" is clinically useful for the cashier, it can be added to the bills card header. **Flag for BA.**
