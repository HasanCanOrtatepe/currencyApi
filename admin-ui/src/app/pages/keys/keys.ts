import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AdminApiService } from '../../core/admin-api.service';
import { AdminSessionService } from '../../core/admin-session.service';
import { AdminApiKeyCreatedResponse, AdminApiKeyRow } from '../../core/api-key.model';

const POLL_INTERVAL_MS = 10_000;

@Component({
  selector: 'app-keys',
  imports: [FormsModule, DatePipe],
  templateUrl: './keys.html',
  styleUrl: './keys.css',
})
export class Keys implements OnInit, OnDestroy {
  readonly rows = signal<AdminApiKeyRow[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  showCreateForm = false;
  newConsumerName = '';
  newRateLimitOverride: number | null = null;
  readonly creating = signal(false);

  /** Oluşturma sonrası tek seferlik gösterim — kapatılınca bir daha asla erişilemez. */
  readonly justCreated = signal<AdminApiKeyCreatedResponse | null>(null);

  revokeCandidate: AdminApiKeyRow | null = null;

  /** Limiti düzenlenen satır — anahtarın kendisi değişmez, yalnız kotası. */
  editingLimitFor: AdminApiKeyRow | null = null;
  editingLimitValue: number | null = null;

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  constructor(
    private readonly api: AdminApiService,
    private readonly session: AdminSessionService,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    void this.refresh();
    this.pollHandle = setInterval(() => void this.refresh(), POLL_INTERVAL_MS);
  }

  ngOnDestroy(): void {
    if (this.pollHandle) {
      clearInterval(this.pollHandle);
    }
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const response = await this.api.listKeys();
      this.rows.set(response.keys);
      this.error.set(null);
    } catch {
      this.error.set('Anahtar listesi alınamadı.');
    } finally {
      this.loading.set(false);
    }
  }

  async submitCreate(): Promise<void> {
    if (!this.newConsumerName.trim()) {
      return;
    }
    this.creating.set(true);
    try {
      const created = await this.api.createKey({
        consumerName: this.newConsumerName.trim(),
        rateLimitOverride: this.newRateLimitOverride,
      });
      this.justCreated.set(created);
      this.showCreateForm = false;
      this.newConsumerName = '';
      this.newRateLimitOverride = null;
      await this.refresh();
    } catch {
      this.error.set('Anahtar oluşturulamadı.');
    } finally {
      this.creating.set(false);
    }
  }

  closeReveal(): void {
    this.justCreated.set(null);
  }

  async copyRawKey(): Promise<void> {
    const created = this.justCreated();
    if (created) {
      await navigator.clipboard.writeText(created.rawKey);
    }
  }

  startEditLimit(row: AdminApiKeyRow): void {
    this.editingLimitFor = row;
    this.editingLimitValue = row.rateLimitOverride;
  }

  cancelEditLimit(): void {
    this.editingLimitFor = null;
    this.editingLimitValue = null;
  }

  async saveLimit(): Promise<void> {
    if (!this.editingLimitFor) {
      return;
    }
    try {
      await this.api.updateRateLimit(this.editingLimitFor.id, this.editingLimitValue);
      this.cancelEditLimit();
      await this.refresh();
    } catch {
      this.error.set('Limit güncellenemedi.');
    }
  }

  askRevoke(row: AdminApiKeyRow): void {
    this.revokeCandidate = row;
  }

  cancelRevoke(): void {
    this.revokeCandidate = null;
  }

  async confirmRevoke(): Promise<void> {
    if (!this.revokeCandidate) {
      return;
    }
    try {
      await this.api.revokeKey(this.revokeCandidate.id);
      this.revokeCandidate = null;
      await this.refresh();
    } catch {
      this.error.set('Anahtar iptal edilemedi.');
    }
  }

  logout(): void {
    this.session.clear();
    void this.router.navigateByUrl('/login');
  }
}
