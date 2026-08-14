package com.ohbsy.currencyapi.dataAccess;

import java.time.ZoneId;

/**
 * <b>Birikmeli anahtar kullanımı</b> — "bu anahtar ne kadar kullanıldı" sorusunun cevabı.
 *
 * <h2>Neden {@link RateLimiter} bu soruyu cevaplayamıyor</h2>
 * Hız sınırlayıcının sayacı <b>1 dakikalık pencereye</b> aittir ve pencere dolunca sıfırlanır.
 * Admin panelindeki "Kalan" sütunu bu yüzden pratikte <b>hep dolu</b> görünüyordu: 15 dakikada
 * bir istek atan bir tüketici için o sayı 120'den 119'a inip saniyeler içinde 120'ye dönüyordu.
 * Sayı doğruydu, <b>soru yanlıştı</b> — "şu an kaç hakkın kaldı" ile "bugüne kadar kaç istek
 * attın" farklı sorulardır ve ikincisi bir sayaçla değil <b>biriktirmeyle</b> cevaplanır.
 *
 * <p>Bu yüzden ayrı bir soyutlama: hız sınırı bir <i>kontrol</i>, bu bir <i>ölçüm</i>dür.
 * Aynı sayaca ikisini birden yaptırmak, birinin penceresini diğerinin ihtiyacına göre
 * değiştirme baskısı yaratırdı.
 *
 * <h2>Anahtar başına, tüketici adına DEĞİL</h2>
 * Hız sınırı kimliği tüketici adıdır (aynı ada bağlı iki anahtar aynı kovayı paylaşır); ama
 * panelde bir <b>satır bir anahtardır</b>. Sayım anahtar kimliğine yapılır ki "hangi anahtar
 * kullanılıyor" sorusu — ör. eskisini iptal etmeden yenisini dağıtırken — cevaplanabilsin.
 *
 * <h2>Fail-open, istisnasız</h2>
 * Bu bir gösterge alanıdır. {@link #record} asla istisna fırlatmaz: bir sayım yazısının
 * kaybı isteği düşürmemelidir. {@link #of} da okunamazsa sıfır döner — panelde eksik bir sayı,
 * çalışmayan bir panelden iyidir.
 */
public interface ApiKeyUsageCounter {

    /**
     * Gün sınırı <b>Türkiye takvimiyle</b> çizilir: paneli işleten kişi buradadır ve "bugün"
     * onun günüdür. UTC seçilseydi gece 03:00'te sayaç sıfırlanır, sabah bakan kişi dünkü
     * trafiğin bir kısmını "bugün" sütununda görürdü.
     */
    ZoneId DAY_ZONE = ZoneId.of("Europe/Istanbul");

    /** Bir başarılı kullanımı sayar. Asla istisna fırlatmaz. */
    void record(String keyId);

    /** Anahtarın bugünkü ve toplam kullanımı; okunamazsa {@link Usage#none()}. */
    Usage of(String keyId);

    /** {@code memory} | {@code redis} — sağlık/tanı çıktısında hangi uygulamanın seçildiği. */
    String kind();

    /**
     * @param today bugün (Türkiye takvimi) yapılan istek sayısı
     * @param total anahtar oluşturulduğundan beri yapılan toplam istek sayısı
     */
    record Usage(long today, long total) {

        public static Usage none() {
            return new Usage(0, 0);
        }
    }
}
