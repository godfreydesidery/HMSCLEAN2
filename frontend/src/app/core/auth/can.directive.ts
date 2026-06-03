import {
  Directive,
  effect,
  inject,
  Input,
  signal,
  TemplateRef,
  ViewContainerRef,
} from '@angular/core';
import { AuthStore } from './auth.store';

/**
 * Structural directive that conditionally renders its host element based on
 * a privilege code.  Uses privilege codes exclusively — never role names or
 * arbitrary string matching.
 *
 * Usage:
 *   <button *appCan="'ADMIN-ACCESS'">Admin only</button>
 */
@Directive({
  selector: '[appCan]',
  standalone: true,
})
export class CanDirective {
  private readonly tpl       = inject<TemplateRef<unknown>>(TemplateRef);
  private readonly vcr       = inject(ViewContainerRef);
  private readonly authStore = inject(AuthStore);

  private readonly code = signal<string>('');
  private rendered      = false;

  @Input()
  set appCan(code: string) {
    this.code.set(code);
  }

  constructor() {
    effect(() => {
      const currentCode = this.code();
      const allowed     = currentCode
        ? this.authStore.hasPrivilege(currentCode)()
        : false;

      if (allowed && !this.rendered) {
        this.vcr.createEmbeddedView(this.tpl);
        this.rendered = true;
      } else if (!allowed && this.rendered) {
        this.vcr.clear();
        this.rendered = false;
      }
    });
  }
}
