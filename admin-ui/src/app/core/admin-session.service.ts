import { Injectable, signal } from '@angular/core';

/**
 * Admin token'ı yalnız BELLEKTE tutar. localStorage/sessionStorage KULLANILMAZ: bir XSS ya da
 * paylaşılan bir makinede kalıcı bir admin sırrının sızması riski, sayfa yenilendiğinde
 * token'ı tekrar girme bedelinden daha ağırdır — bu bilinçli bir tercihtir.
 */
@Injectable({ providedIn: 'root' })
export class AdminSessionService {
  private readonly _token = signal<string | null>(null);

  readonly token = this._token.asReadonly();

  isAuthenticated(): boolean {
    return this._token() !== null;
  }

  setToken(token: string): void {
    this._token.set(token);
  }

  clear(): void {
    this._token.set(null);
  }
}
