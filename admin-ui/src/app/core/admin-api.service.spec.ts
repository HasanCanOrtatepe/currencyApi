import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { AdminApiService } from './admin-api.service';
import { AdminSessionService } from './admin-session.service';
import { RuntimeConfigService } from './runtime-config.service';

const BASE = 'http://test-host:8097';

class StubRuntimeConfig {
  adminApiBaseUrl(): Promise<string> {
    return Promise.resolve(BASE);
  }
}

/**
 * Servis isteği kurmadan ÖNCE runtime yapılandırmasını bekler (await). Bu yüzden istek,
 * çağrıyı yapan satırda değil bir sonraki mikro-görev turunda oluşur; beklemeden
 * {@code expectOne} çağrılırsa "found none" ile düşer.
 */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('AdminApiService', () => {
  let api: AdminApiService;
  let session: AdminSessionService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: RuntimeConfigService, useClass: StubRuntimeConfig },
      ],
    });
    api = TestBed.inject(AdminApiService);
    session = TestBed.inject(AdminSessionService);
    http = TestBed.inject(HttpTestingController);
    session.setToken('gizli-token');
  });

  afterEach(() => {
    // finally şart: verify() bir doğrulama hatası fırlatırsa sıfırlama yapılmaz ve BİR
    // SONRAKİ test "test module has already been instantiated" ile düşerdi — asıl hatayı
    // gizleyen, yanıltıcı bir zincir.
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  /** Backend'in AdminAuthFilter'ı bu başlığı bekler; eksikse her istek 401 alır. */
  it('listKeys X-Admin-Token başlığını taşır', async () => {
    const promise = api.listKeys();
    await settle();

    const req = http.expectOne(`${BASE}/admin/keys`);
    expect(req.request.headers.get('X-Admin-Token')).toBe('gizli-token');
    req.flush({ keys: [] });

    await promise;
  });

  it('createKey POST ile gövdeyi gönderir', async () => {
    const promise = api.createKey({ consumerName: 'crm', rateLimitOverride: 30 });
    await settle();

    const req = http.expectOne(`${BASE}/admin/keys`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ consumerName: 'crm', rateLimitOverride: 30 });
    req.flush({
      id: '1',
      rawKey: 'cur_x',
      consumerName: 'crm',
      createdAt: '',
      rateLimitOverride: 30,
    });

    await promise;
  });

  it('updateRateLimit PATCH ile doğru yola gider', async () => {
    const promise = api.updateRateLimit('key-1', 42);
    await settle();

    const req = http.expectOne(`${BASE}/admin/keys/key-1/rate-limit`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ rateLimitOverride: 42 });
    req.flush(null);

    await promise;
  });

  it('updateRateLimit(null) global varsayılana döndürmek için null gönderir', async () => {
    const promise = api.updateRateLimit('key-1', null);
    await settle();

    const req = http.expectOne(`${BASE}/admin/keys/key-1/rate-limit`);
    expect(req.request.body).toEqual({ rateLimitOverride: null });
    req.flush(null);

    await promise;
  });

  it('revokeKey DELETE ile id yolunu kullanır', async () => {
    const promise = api.revokeKey('key-1');
    await settle();

    const req = http.expectOne(`${BASE}/admin/keys/key-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    await promise;
  });

  /** Token biçim denetimiyle değil, GERÇEK bir istekle doğrulanır. */
  it('validateToken 200 alırsa true döner', async () => {
    const ok = api.validateToken('dogru');
    await settle();

    http.expectOne(`${BASE}/admin/keys`).flush({ keys: [] });

    expect(await ok).toBe(true);
  });

  it('validateToken 401 alırsa false döner (istisna sızdırmaz)', async () => {
    const bad = api.validateToken('yanlis');
    await settle();

    http.expectOne(`${BASE}/admin/keys`).flush('', { status: 401, statusText: 'Unauthorized' });

    expect(await bad).toBe(false);
  });
});
