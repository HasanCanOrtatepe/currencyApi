import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AdminApiService } from '../../core/admin-api.service';
import { AdminSessionService } from '../../core/admin-session.service';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login {
  token = '';
  readonly checking = signal(false);
  readonly error = signal<string | null>(null);

  constructor(
    private readonly api: AdminApiService,
    private readonly session: AdminSessionService,
    private readonly router: Router,
  ) {}

  async submit(): Promise<void> {
    if (!this.token.trim()) {
      return;
    }
    this.checking.set(true);
    this.error.set(null);
    try {
      const ok = await this.api.validateToken(this.token.trim());
      if (!ok) {
        this.error.set('Token reddedildi — admin API adresini ve token’ı kontrol edin.');
        return;
      }
      this.session.setToken(this.token.trim());
      await this.router.navigateByUrl('/keys');
    } catch {
      this.error.set('Admin API’ye ulaşılamadı — config.json’daki adres doğru mu?');
    } finally {
      this.checking.set(false);
    }
  }
}
