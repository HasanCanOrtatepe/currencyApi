package com.ohbsy.currencyapi.core.integrations.evds.dtos;

import java.util.Map;

/**
 * EVDS'in bir <b>gözlem satırı</b> — takvimdeki tek bir gün.
 *
 * <p><b>Sütun adları çalışma zamanında belirlenir:</b> istediğimiz seri {@code TP.DK.USD.S} ise
 * cevapta {@code TP_DK_USD_S} olarak döner (noktalar alt çizgiye çevrilir). Bu yüzden satır
 * sabit alanlı bir record değil, {@code sütun → ham değer} haritasıdır; hangi sütunun hangi
 * para birimi olduğunu bilmek {@code EvdsRateMapper}'ın işidir.
 *
 * <p><b>Yayın olmayan günler satırı SİLMEZ, değerleri boşaltır:</b> hafta sonu ve tatillerde
 * satır gelir ama değerler {@code null}'dır ({@link #hasNoValues()}). Bu, "o gün yok" ile
 * "o gün var ama kur yayınlanmadı" ayrımını okuyucuya bırakır.
 *
 * @param date   {@code dd-MM-yyyy} biçiminde ham tarih — burada AYRIŞTIRILMAZ
 * @param values {@code sütun adı → ham değer}; yalnız metin değerler taşınır
 */
public record EvdsObservation(String date, Map<String, String> values) {

    public String valueOf(String column) {
        return values.get(column);
    }

    /** Yayın yapılmamış gün (hafta sonu/tatil): satır var, içi boş. */
    public boolean hasNoValues() {
        return values.isEmpty();
    }
}
