import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'keys' },
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login').then((m) => m.Login),
  },
  {
    path: 'keys',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/keys/keys').then((m) => m.Keys),
  },
  { path: '**', redirectTo: 'keys' },
];
