package com.ohbsy.currencyapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * T34 Faz 7 — <b>sahte kur satıcısı.</b> TCMB ve ECB'nin günlük kur uçlarını, <b>gerçek
 * satıcıların yollarını ve XML şekillerini birebir kullanarak</b> taklit eder.
 *
 * <h2>Neden yolları ve şekli birebir taklit ediyor</h2>
 * CRM tarafında {@code crm.currency.provider=fake} ile {@code real} arasındaki farkın
 * <b>yalnız base URL</b> olması gerekiyordu (T34 K3/F8). Sahte servis kendi kolay bir sözleşme
 * uydursaydı ("/rates" + JSON), CRM'de ikinci bir adaptör yazmak gerekirdi ve o adaptör
 * gerçek satıcıya geçişte <b>hiçbir şey kanıtlamazdı</b>. Taklit edilen şey satıcının
 * rahatlığı değil, <b>bizim adaptörümüzün doğruluğudur</b>.
 *
 * <h2>Ne DEĞİLDİR</h2>
 * Bir CRM servisi değildir: veritabanı yok, Eureka'ya kaydolmaz, Config Server'dan okumaz,
 * outbox/Kafka taşımaz. Maven reaktörüne de girmez (bkz. {@code pom.xml}). Durumu (kaos modu)
 * bellektedir — T20'nin "durum Redis'te" kuralı buna işlemez, çünkü bu bir test ikilisidir ve
 * tek instance çalışır.
 */
@SpringBootApplication
public class CurrencyApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CurrencyApiApplication.class, args);
    }
}
