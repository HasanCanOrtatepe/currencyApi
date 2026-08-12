package com.ohbsy.currencyapi.core.utilities;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Dinamik API anahtarlarının üretimi ve özetlenmesi. {@link SecureXml} ile aynı gerekçeyle
 * durur: paylaşılan, satıcıdan/çağrı yerinden bağımsız bir güvenlik detayı, tek yerde.
 *
 * <h2>Neden SHA-256 (bcrypt/Argon2 DEĞİL)</h2>
 * Yavaş KDF'ler (bcrypt, Argon2, PBKDF2) <b>düşük entropili, insan seçimi</b> sırları
 * (parolalar) çevrimdışı kaba kuvvete karşı yavaşlatmak için vardır. Buradaki anahtar 256 bitlik
 * bir {@link SecureRandom} çıktısıdır — kaba kuvvetle bulunması zaten hesaplanamaz ölçüde
 * pahalıdır; yavaş bir KDF bu durumda saldırgana hiçbir ek koruma sağlamadan HER isteğe gereksiz
 * CPU maliyeti bindirirdi. Aynı gerekçeyle GitHub (PAT) ve Stripe da token'larını SHA-256 ile
 * özetler, bcrypt ile değil.
 */
public final class ApiKeyHasher {

    private static final String PREFIX = "cur_";
    private static final int RAW_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyHasher() {
    }

    /** Yeni bir ham anahtar üretir: {@code cur_} + 256 bit rastgele, URL-safe Base64. */
    public static String generateRawKey() {
        byte[] bytes = new byte[RAW_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex özeti — istek-anı eşleşme burada yapılır, ham anahtar hiç saklanmaz. */
    public static String sha256Hex(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 her JVM'de bulunması garanti bir algoritmadır (JLS/JCA zorunlu listesi).
            throw new IllegalStateException("SHA-256 kullanilamiyor", e);
        }
    }

    /**
     * Maskelenmiş gösterim (ör. {@code cur_ab12…wxyz}) — yalnız oluşturma anında, ham anahtar
     * elimizdeyken çağrılır; anahtarı yeniden üretmek için KULLANILAMAZ.
     */
    public static String preview(String rawKey) {
        String body = rawKey.startsWith(PREFIX) ? rawKey.substring(PREFIX.length()) : rawKey;
        if (body.length() <= 8) {
            return PREFIX + "…";
        }
        return PREFIX + body.substring(0, 4) + "…" + body.substring(body.length() - 4);
    }
}
