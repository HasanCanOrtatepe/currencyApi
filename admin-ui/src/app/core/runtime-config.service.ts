import { Injectable } from '@angular/core';

interface RuntimeConfig {
  adminApiPort: number;
}

/**
 * `config.json`'ı çalışma zamanında (build-time `environment.ts` DEĞİL) okur ve admin API'nin
 * adresini `window.location.hostname` üzerinden hesaplar.
 *
 * <h2>Neden sabit bir host değil</h2>
 * Bu arayüz LAN'daki başka bir cihazdan `http://<mac-ip>:8096` ile açılabilir. `config.json`'a
 * {@code "http://localhost:8097"} yazılsaydı, tarayıcı bunu KENDİ makinesinin localhost'u
 * sanırdı — Mac'in değil. Bu yüzden yalnız PORT sabittir, host tarayıcının o an bağlandığı
 * adresten (hangi cihaz olursa olsun doğru) türetilir.
 */
@Injectable({ providedIn: 'root' })
export class RuntimeConfigService {
  private cached: Promise<RuntimeConfig> | null = null;

  private load(): Promise<RuntimeConfig> {
    if (!this.cached) {
      this.cached = fetch('config.json').then((response) => response.json());
    }
    return this.cached;
  }

  async adminApiBaseUrl(): Promise<string> {
    const config = await this.load();
    return `${window.location.protocol}//${window.location.hostname}:${config.adminApiPort}`;
  }
}
