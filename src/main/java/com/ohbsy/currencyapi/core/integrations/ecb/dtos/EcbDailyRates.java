package com.ohbsy.currencyapi.core.integrations.ecb.dtos;

import java.util.Map;

/**
 * ECB'nin bir <b>yayın günü</b> — belgedeki {@code <Cube time="...">} bloğunun ham hâli.
 *
 * <p><b>Kurlar EUR tabanlıdır ve TERS YÖNDEDİR:</b> {@code currency="USD" rate="1.1665"}
 * satırı "1 EUR = 1,1665 USD" demektir. Bizim domainimiz TRY tabanlıdır; çeviri
 * {@code EcbRateMapper}'ın işidir ve burada YAPILMAZ — bu record satıcının söylediğini
 * söylediği gibi taşır.
 *
 * <p><b>EUR satır olarak GELMEZ.</b> Baz para biriminin kendisi belgede yoktur (1 EUR = 1 EUR
 * yazmak anlamsız olurdu); mapper onu ayrıca üretir. Bu, "eksik para birimi" ile "baz para
 * birimi" ayrımını okuyucuya değil çeviriciye bırakır.
 *
 * @param date  {@code yyyy-MM-dd} biçiminde ham tarih — burada AYRIŞTIRILMAZ
 * @param rates {@code para birimi kodu → ham kur}; yalnız metin değerler taşınır
 */
public record EcbDailyRates(String date, Map<String, String> rates) {

    public String valueOf(String currency) {
        return rates.get(currency);
    }

    /** İçi boş gün: {@code <Cube time="...">} var ama altında kur satırı yok. */
    public boolean hasNoValues() {
        return rates.isEmpty();
    }
}
