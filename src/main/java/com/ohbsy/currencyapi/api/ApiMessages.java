package com.ohbsy.currencyapi.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Hata metinlerini isteğin diline çözer ({@code Accept-Language}).
 *
 * <p>Tüketici CRM'in kendi sözleşmesi {@code tr}/{@code en} taşımaktır ve kullanıcıya gösterilen
 * metnin dile çözülmüş olarak <b>backend'den</b> gelmesini bekler. Sabit İngilizce dizgi
 * döndürseydik, CRM her hata kodunu kendi çeviri dosyasında ikinci kez tanımlamak zorunda
 * kalırdı — aynı metnin iki yerde yaşaması, birinin güncellenip diğerinin unutulması demektir.
 *
 * <p>Desteklenmeyen bir dil geldiğinde varsayılan ({@code messages.properties}, İngilizce)
 * kullanılır: bilinmeyen dil bir hata değildir, yalnız çevirisi yoktur.
 */
@Component
public class ApiMessages {

    /** Yalnız bunlar desteklenir — CRM'in {@code SupportedLanguages} listesiyle eştir. */
    private static final Locale TR = Locale.forLanguageTag("tr");
    private static final Locale EN = Locale.ENGLISH;

    private final MessageSource messageSource;

    public ApiMessages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String get(HttpServletRequest request, String code) {
        return messageSource.getMessage(code, null, localeOf(request));
    }

    private Locale localeOf(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header != null && !header.isBlank()
                && header.trim().toLowerCase(Locale.ROOT).startsWith("tr")) {
            return TR;
        }
        return EN;
    }
}
