import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'app-forbidden',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatButtonModule],
  template: `
    <div class="forbidden-wrapper">
      <h1>403 — You do not have permission to view this page.</h1>
      <p>Contact your system administrator if you believe this is an error.</p>
      <a mat-raised-button color="primary" routerLink="/home">Back to Home</a>
    </div>
  `,
  styles: [`
    .forbidden-wrapper {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: calc(100vh - 64px);
      padding: 2rem 1rem;
      text-align: center;
      gap: 1rem;
    }
    h1 { font-size: 1.5rem; }
  `],
})
export class ForbiddenComponent {}
