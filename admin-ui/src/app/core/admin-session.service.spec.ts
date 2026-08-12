import { TestBed } from '@angular/core/testing';
import { AdminSessionService } from './admin-session.service';

describe('AdminSessionService', () => {
  let service: AdminSessionService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AdminSessionService);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('başlangıçta oturum yoktur', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.token()).toBeNull();
  });

  it('token kaydedilince oturum açılır', () => {
    service.setToken('gizli-token');

    expect(service.isAuthenticated()).toBe(true);
    expect(service.token()).toBe('gizli-token');
  });

  it('clear() oturumu düşürür', () => {
    service.setToken('gizli-token');

    service.clear();

    expect(service.isAuthenticated()).toBe(false);
    expect(service.token()).toBeNull();
  });

  /**
   * Token YALNIZ bellekte yaşamalı: bir XSS ya da paylaşılan makinede kalıcı bir admin
   * sırrının sızması, sayfa yenilendiğinde token'ı tekrar girme bedelinden ağırdır.
   *
   * <p>Sayfa yenilemesi, servisin YENİDEN kurulmasıyla temsil edilir. Token herhangi bir
   * kalıcı yere (localStorage/sessionStorage/cookie) yazılmış olsaydı yeni örnek onu geri
   * okurdu; okumuyorsa hiçbir yere yazılmamış demektir. Bu kurgu, test ortamında
   * {@code localStorage} bulunup bulunmamasından BAĞIMSIZDIR.
   */
  it('token kalıcı DEĞİLDİR — yeniden kurulan servis onu geri getirmez', () => {
    service.setToken('cok-gizli-token');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const afterReload = TestBed.inject(AdminSessionService);

    expect(afterReload.token()).toBeNull();
    expect(afterReload.isAuthenticated()).toBe(false);
  });
});
