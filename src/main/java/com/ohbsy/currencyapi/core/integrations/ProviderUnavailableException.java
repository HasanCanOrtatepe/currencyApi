package com.ohbsy.currencyapi.core.integrations;

/**
 * Sağlayıcıdan kullanılabilir kur alınamadı.
 *
 * <p>{@link Reason} <b>tanılama içindir, akış kararı değildir</b>: servis katmanı hepsine aynı
 * yanıtı verir (son geçerli kuru sun). Ayrımın değeri log'dadır — "satıcı mı düştü, cevabı mı
 * bozuk, tatil mi" soruları farklı yerlerde aranır ve tek bir "hata" etiketiyle ayrılamaz.
 */
public class ProviderUnavailableException extends RuntimeException {

    public enum Reason {
        /** Ağ/HTTP hatası, bağlantı reddi, 5xx. */
        TRANSPORT,
        /** Zaman aşımı. */
        TIMEOUT,
        /**
         * Belge yok — TCMB'de <b>hafta sonu ve resmî tatillerde beklenen</b> durumdur (404).
         * Arıza değil takvimdir; bu yüzden ayrı bir sebep olarak işaretlenir ve alarm
         * kurallarında {@link #TRANSPORT} ile aynı ağırlıkta değerlendirilmemelidir.
         */
        NOT_PUBLISHED,
        /** Cevap geldi ama okunamadı/doğrulamadan geçmedi. */
        INVALID_PAYLOAD,
        /**
         * Satıcı bizi tanımadı (401/403): anahtar yok, yanlış ya da süresi dolmuş.
         *
         * <p>{@link #TRANSPORT}'tan ayrılması operasyonel bir gerekliliktir: diğer sebepler
         * <b>kendiliğinden düzelir</b> (satıcı geri gelir, tatil biter), bu düzelmez —
         * yapılandırma değişene kadar her denemede tekrarlar. "TCMB düştü" ile "anahtarımız
         * geçersiz" farklı işler gerektirir ve tek bir etiketle ayrılamaz.
         */
        UNAUTHORIZED
    }

    private final Reason reason;

    public ProviderUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ProviderUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
