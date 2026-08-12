import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authGuard } from './auth.guard';
import { AdminSessionService } from './admin-session.service';

describe('authGuard', () => {
  let session: AdminSessionService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    session = TestBed.inject(AdminSessionService);
    router = TestBed.inject(Router);
  });

  afterEach(() => TestBed.resetTestingModule());

  const run = () =>
    TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));

  it('oturum yoksa /login sayfasına yönlendirir', () => {
    const result = run();

    expect(result).toEqual(router.parseUrl('/login'));
  });

  it('oturum varsa geçişe izin verir', () => {
    session.setToken('gizli-token');

    expect(run()).toBe(true);
  });
});
