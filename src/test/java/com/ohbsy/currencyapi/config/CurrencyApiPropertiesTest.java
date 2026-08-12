package com.ohbsy.currencyapi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Yapılandırmanın ORTAMDAN gerçekten bağlandığını doğrular.
 *
 * <p>Bu sınıfın varlık sebebi ölçülmüş bir arızadır: anahtarlar önce
 * {@code keys: "[${CURRENCY_CRM_KEY}]": crm} biçiminde bir YAML map ANAHTARI olarak
 * yazılmıştı. YAML map anahtarında yer tutucu <b>çözülmez</b> (çözüm değerlere uygulanır),
 * bu yüzden servis ortam değişkeninin ADINI anahtar sanıyor ve <b>doğru anahtarla gelen
 * istek bile 401</b> alıyordu. Arıza sessizdi: yapılandırma doğru görünüyor, testler geçiyor,
 * yalnız canlı istek reddediliyordu. Birim testler filtreyi elle kurduğu için bunu yakalayamaz —
 * yakalanabilecek tek yer bağlama (binding) katmanıdır.
 */
@DisplayName("CurrencyApiProperties — ortamdan bağlama")
class CurrencyApiPropertiesTest {

    private CurrencyApiProperties bind(Map<String, Object> properties) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
        CurrencyApiProperties target = new CurrencyApiProperties();
        new Binder(source).bind("currency-api", org.springframework.boot.context.properties.bind.Bindable.ofInstance(target));
        return target;
    }

    @Test
    @DisplayName("key-spec ortam değerinden anahtar → tüketici haritası kurar")
    void keySpecBindsFromEnvironment() {
        CurrencyApiProperties properties = bind(Map.of(
                "currency-api.auth.enabled", "true",
                "currency-api.auth.key-spec", "abc123=crm,def456=reporting"));

        assertThat(properties.getAuth().isEnabled()).isTrue();
        assertThat(properties.getAuth().getKeys())
                .containsExactly(entry("abc123", "crm"), entry("def456", "reporting"));
    }

    /**
     * Compose {@code ${CURRENCY_CRM_KEY:-}=crm} gönderir; değişken doldurulmadıysa değer
     * {@code "=crm"} olur. Bu, anahtarı BOŞ bir tüketici demektir ve kabul edilseydi
     * anahtarsız istek geçerdi — yani güvenlik özelliği sessizce kapanırdı.
     */
    @Test
    @DisplayName("Boş anahtarlı girdi elenir (doldurulmamış ortam değişkeni geçerli anahtar üretmez)")
    void blankKeyIsDropped() {
        CurrencyApiProperties properties = bind(Map.of("currency-api.auth.key-spec", "=crm"));

        assertThat(properties.getAuth().getKeys()).isEmpty();
    }

    /** Şablon değeri ({@code __unset__}) geçerli bir anahtar sayılmamalıdır. */
    @Test
    @DisplayName("Yer tutucu anahtar (__unset__) geçerli sayılmaz")
    void placeholderKeyIsDropped() {
        CurrencyApiProperties properties = bind(Map.of("currency-api.auth.key-spec", "__unset__=crm"));

        assertThat(properties.getAuth().getKeys()).isEmpty();
    }

    /** Tüketici adı verilmezse anahtar yine de çalışmalı — ad yalnız log/metrik içindir. */
    @Test
    @DisplayName("Adsız anahtar kabul edilir, 'unnamed' olarak etiketlenir")
    void keyWithoutClientNameIsAccepted() {
        CurrencyApiProperties properties = bind(Map.of("currency-api.auth.key-spec", "abc123"));

        assertThat(properties.getAuth().getKeys()).containsExactly(entry("abc123", "unnamed"));
    }

    /**
     * Boş {@code TCMB_BASE_URL} varsayılanı EZMEMELİDİR: compose'un {@code ${VAR:-}} biçimi
     * değişken verilmediğinde boş dizgi gönderir, ortam değişkeni de Java varsayılanından
     * önceliklidir — eleme yapılmasaydı servis host'suz bir URL'ye bağlanmaya çalışırdı.
     */
    @Test
    @DisplayName("Boş base-url varsayılanı ezmez")
    void blankBaseUrlKeepsDefault() {
        CurrencyApiProperties properties = bind(Map.of("currency-api.tcmb.base-url", ""));

        assertThat(properties.getTcmb().getBaseUrl()).isEqualTo("https://www.tcmb.gov.tr");
    }
}
