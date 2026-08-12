export interface AdminApiKeyRow {
  id: string;
  consumerName: string;
  keyPreview: string;
  createdAt: string;
  revokedAt: string | null;
  active: boolean;
  rateLimitOverride: number | null;
  lastUsedAt: string | null;
  usageLimit: number;
  usageRemaining: number;
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
