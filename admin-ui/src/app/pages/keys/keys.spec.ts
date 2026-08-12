import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Keys } from './keys';
import { AdminApiService } from '../../core/admin-api.service';
import { AdminSessionService } from '../../core/admin-session.service';
import {
  AdminApiKeyCreateRequest,
  AdminApiKeyCreatedResponse,
  AdminApiKeyRow,
  AdminApiKeysResponse,
} from '../../core/api-key.model';

function row(overrides: Partial<AdminApiKeyRow> = {}): AdminApiKeyRow {
  return {
    id: 'id-1',
    consumerName: 'crm',
    keyPreview: 'cur_ab…yz',
    createdAt: '2026-08-12T10:00:00Z',
    revokedAt: null,
    active: true,
    rateLimitOverride: null,
    lastUsedAt: null,
    usageLimit: 120,
    usageRemaining: 120,
    ...overrides,
  };
}

/** Gerçek HTTP yerine çağrıları kaydeden bir sahte — bileşenin KENDİ mantığı sınanır. */
class FakeAdminApi {
  listResponse: AdminApiKeysResponse = { keys: [row()] };
  created: AdminApiKeyCreatedResponse = {
    id: 'id-new',
    rawKey: 'cur_yeni_anahtar',
    consumerName: 'reporting',
    createdAt: '2026-08-12T11:00:00Z',
    rateLimitOverride: 30,
  };
  failNext: 'list' | 'create' | 'revoke' | 'limit' | null = null;

  createCalls: AdminApiKeyCreateRequest[] = [];
  revokeCalls: string[] = [];
  limitCalls: Array<{ id: string; limit: number | null }> = [];

  async listKeys(): Promise<AdminApiKeysResponse> {
    if (this.failNext === 'list') throw new Error('list patladi');
    return this.listResponse;
  }
  async createKey(request: AdminApiKeyCreateRequest): Promise<AdminApiKeyCreatedResponse> {
    if (this.failNext === 'create') throw new Error('create patladi');
    this.createCalls.push(request);
    return this.created;
  }
  async revokeKey(id: string): Promise<void> {
    if (this.failNext === 'revoke') throw new Error('revoke patladi');
    this.revokeCalls.push(id);
  }
  async updateRateLimit(id: string, limit: number | null): Promise<void> {
    if (this.failNext === 'limit') throw new Error('limit patladi');
    this.limitCalls.push({ id, limit });
  }
}

describe('Keys', () => {
  let component: Keys;
  let api: FakeAdminApi;
  let session: AdminSessionService;
  let router: Router;

  beforeEach(() => {
    api = new FakeAdminApi();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AdminApiService, useValue: api }],
    });
    session = TestBed.inject(AdminSessionService);
    router = TestBed.inject(Router);
    session.setToken('gizli-token');
    component = TestBed.runInInjectionContext(
      () => new Keys(api as unknown as AdminApiService, session, router),
    );
  });

  afterEach(() => {
    component.ngOnDestroy();
    TestBed.resetTestingModule();
  });

  it('refresh satırları yükler', async () => {
    await component.refresh();

    expect(component.rows().length).toBe(1);
    expect(component.rows()[0].consumerName).toBe('crm');
    expect(component.error()).toBeNull();
  });

  it('liste alınamazsa hata gösterir, çökmez', async () => {
    api.failNext = 'list';

    await component.refresh();

    expect(component.error()).toBeTruthy();
    expect(component.rows().length).toBe(0);
  });

  it('boş tüketici adıyla oluşturma isteği GÖNDERİLMEZ', async () => {
    component.newConsumerName = '   ';

    await component.submitCreate();

    expect(api.createCalls.length).toBe(0);
  });

  it('oluşturma sonrası ham anahtar TEK SEFERLİK gösterilir ve form temizlenir', async () => {
    component.newConsumerName = 'reporting';
    component.newRateLimitOverride = 30;

    await component.submitCreate();

    expect(api.createCalls[0]).toEqual({ consumerName: 'reporting', rateLimitOverride: 30 });
    expect(component.justCreated()?.rawKey).toBe('cur_yeni_anahtar');
    expect(component.newConsumerName).toBe('');
    expect(component.newRateLimitOverride).toBeNull();
    expect(component.showCreateForm).toBe(false);
  });

  /** Diyalog kapandıktan sonra ham anahtara ERİŞİM KALMAMALI. */
  it('gösterim kapatılınca ham anahtar bellekten düşer', async () => {
    component.newConsumerName = 'reporting';
    await component.submitCreate();

    component.closeReveal();

    expect(component.justCreated()).toBeNull();
  });

  it('iptal onaylanana kadar istek gönderilmez', async () => {
    const target = row({ id: 'id-9' });

    component.askRevoke(target);
    expect(api.revokeCalls.length).toBe(0);

    component.cancelRevoke();
    await component.confirmRevoke();
    expect(api.revokeCalls.length).toBe(0);

    component.askRevoke(target);
    await component.confirmRevoke();
    expect(api.revokeCalls).toEqual(['id-9']);
    expect(component.revokeCandidate).toBeNull();
  });

  it('limit düzenleme mevcut değeri yükler ve kaydeder', async () => {
    const target = row({ id: 'id-7', rateLimitOverride: 5 });

    component.startEditLimit(target);
    expect(component.editingLimitValue).toBe(5);

    component.editingLimitValue = 55;
    await component.saveLimit();

    expect(api.limitCalls).toEqual([{ id: 'id-7', limit: 55 }]);
    expect(component.editingLimitFor).toBeNull();
  });

  it('limit boş bırakılırsa null gönderilir (global varsayılana dön)', async () => {
    component.startEditLimit(row({ id: 'id-7', rateLimitOverride: 5 }));
    component.editingLimitValue = null;

    await component.saveLimit();

    expect(api.limitCalls).toEqual([{ id: 'id-7', limit: null }]);
  });

  it('limit güncellenemezse diyalog AÇIK kalır ve hata gösterilir', async () => {
    api.failNext = 'limit';
    component.startEditLimit(row({ id: 'id-7' }));

    await component.saveLimit();

    expect(component.editingLimitFor).not.toBeNull();
    expect(component.error()).toBeTruthy();
  });

  it('çıkış oturumu düşürür ve /login sayfasına gider', () => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    component.logout();

    expect(session.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/login');
  });

  /** Bileşen yok edildiğinde zamanlayıcı durmazsa arka planda istek atmaya devam ederdi. */
  it('ngOnDestroy poll zamanlayıcısını durdurur', () => {
    const clear = vi.spyOn(globalThis, 'clearInterval');

    component.ngOnInit();
    component.ngOnDestroy();

    expect(clear).toHaveBeenCalled();
  });
});
