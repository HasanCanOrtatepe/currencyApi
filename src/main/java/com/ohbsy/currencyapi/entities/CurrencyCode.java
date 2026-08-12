package com.ohbsy.currencyapi.entities;

import java.util.Locale;
import java.util.Optional;

/**
 * ISO 4217 para birimi kodu. <b>Çıplak {@code String} yerine enum</b>: kod bir kimliktir, serbest
 * metin değil — {@code "usd"}, {@code "USD "} ve {@code "Usd"} aynı şeydir ve bu normalizasyon
 * tek yerde durmalıdır.
 *
 * <p>Desteklenen küme bilinçle dardır: TCMB 20+ kod yayınlar, biz sunduklarımızı burada
 * ilan ederiz. Yeni kod eklemek = bir satır; eklenmeyen kod sessizce atlanır.
 */
public enum CurrencyCode {

    /** Baz — TCMB tablosu TRY tabanlıdır. */
    TRY,
    USD,
    EUR,
    GBP,
    CHF,
    JPY;

    /**
     * Tolerant çözüm: tanınmayan kod <b>istisna değil boş {@link Optional}</b>'dır. Satıcı
     * belgesinde bizim tanımadığımız kodların bulunması normaldir ve bir arıza değildir —
     * istisna fırlatmak, satıcı yeni bir para birimi eklediğinde servisi düşürürdü.
     */
    public static Optional<CurrencyCode> fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            // Locale.ROOT: Türkçe locale'de "i".toUpperCase() → "İ" olur ve eşleşme kaybolur.
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
