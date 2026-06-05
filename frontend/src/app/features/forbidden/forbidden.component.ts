import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-forbidden',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <div class="forbidden-wrapper text-center">
      <div class="display-1 text-danger fw-bold">403</div>
      <h1 class="h4 mb-2">You do not have permission to view this page.</h1>
      <p class="text-muted">Contact your system administrator if you believe this is an error.</p>
      <a class="btn btn-primary" routerLink="/home">Back to Home</a>
    </div>
  `,
  styles: [`
    .forbidden-wrapper {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: calc(100vh - 56px);
      padding: 2rem 1rem;
      gap: 0.75rem;
    }
  `],
})
export class ForbiddenComponent {}
