import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AdminSessionService } from './admin-session.service';

export const authGuard: CanActivateFn = () => {
  const session = inject(AdminSessionService);
  const router = inject(Router);

  if (session.isAuthenticated()) {
    return true;
  }
  return router.parseUrl('/login');
};
