import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  AdminApiKeyCreateRequest,
  AdminApiKeyCreatedResponse,
  AdminApiKeysResponse,
} from './api-key.model';
import { AdminSessionService } from './admin-session.service';
import { RuntimeConfigService } from './runtime-config.service';

/** Her admin isteğine {@code X-Admin-Token} taşır — backend'in AdminAuthFilter'ının beklediği başlık. */
@Injectable({ providedIn: 'root' })
export class AdminApiService {
  constructor(
    private readonly http: HttpClient,
    private readonly session: AdminSessionService,
    private readonly config: RuntimeConfigService,
  ) {}

  private async headers(token: string): Promise<HttpHeaders> {
    return new HttpHeaders({ 'X-Admin-Token': token });
  }

  private async baseUrl(): Promise<string> {
    return this.config.adminApiBaseUrl();
  }

  /** Girilen token'ı GERÇEKTEN dener — biçim doğrulaması değil, canlı bir GET ile. */
  async validateToken(token: string): Promise<boolean> {
    const base = await this.baseUrl();
    try {
      await firstValueFrom(
        this.http.get(`${base}/admin/keys`, { headers: await this.headers(token) }),
      );
      return true;
    } catch {
      return false;
    }
  }

  async listKeys(): Promise<AdminApiKeysResponse> {
    const base = await this.baseUrl();
    const token = this.session.token();
    if (!token) {
      throw new Error('oturum yok');
    }
    return firstValueFrom(
      this.http.get<AdminApiKeysResponse>(`${base}/admin/keys`, {
        headers: await this.headers(token),
      }),
    );
  }

  async createKey(request: AdminApiKeyCreateRequest): Promise<AdminApiKeyCreatedResponse> {
    const base = await this.baseUrl();
    const token = this.session.token();
    if (!token) {
      throw new Error('oturum yok');
    }
    return firstValueFrom(
      this.http.post<AdminApiKeyCreatedResponse>(`${base}/admin/keys`, request, {
        headers: await this.headers(token),
      }),
    );
  }

  /** Yalnız limiti değiştirir — anahtarın kendisi değişmez, tüketici etkilenmez. */
  async updateRateLimit(id: string, rateLimitOverride: number | null): Promise<void> {
    const base = await this.baseUrl();
    const token = this.session.token();
    if (!token) {
      throw new Error('oturum yok');
    }
    await firstValueFrom(
      this.http.patch<void>(
        `${base}/admin/keys/${id}/rate-limit`,
        { rateLimitOverride },
        { headers: await this.headers(token) },
      ),
    );
  }

  async revokeKey(id: string): Promise<void> {
    const base = await this.baseUrl();
    const token = this.session.token();
    if (!token) {
      throw new Error('oturum yok');
    }
    await firstValueFrom(
      this.http.delete<void>(`${base}/admin/keys/${id}`, {
        headers: await this.headers(token),
      }),
    );
  }
}
