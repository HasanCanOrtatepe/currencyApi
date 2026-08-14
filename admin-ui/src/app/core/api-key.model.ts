export interface AdminApiKeyRow {
  id: string;
  consumerName: string;
  keyPreview: string;
  createdAt: string;
  revokedAt: string | null;
  active: boolean;
  rateLimitOverride: number | null;
  lastUsedAt: string | null;
  /** Kota: dakikalık pencerede izin verilen istek sayısı. */
  usageLimit: number;
  /**
   * ŞU ANKİ dakikalık pencerede kalan hak — pencere dolunca `usageLimit`'e döner. Seyrek
   * çağıran bir tüketicide pratikte hep dolu görünür; "ne kadar kullanılıyor" sorusunun
   * cevabı bu DEĞİL, aşağıdaki birikmeli sayaçlardır.
   */
  usageRemaining: number;
  /** Bugün (Türkiye takvimi) yapılan istek sayısı — birikmeli, azalmaz. */
  usageToday: number;
  /** Anahtar oluşturulduğundan beri yapılan toplam istek sayısı. */
  usageTotal: number;
}

export interface AdminApiKeysResponse {
  keys: AdminApiKeyRow[];
}

export interface AdminApiKeyCreateRequest {
  consumerName: string;
  rateLimitOverride: number | null;
}

export interface AdminApiKeyCreatedResponse {
  id: string;
  rawKey: string;
  consumerName: string;
  createdAt: string;
  rateLimitOverride: number | null;
}
