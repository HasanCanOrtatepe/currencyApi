package com.ohbsy.currencyapi.api;

import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Testlerin GERÇEK {@code messages*.properties} dosyalarını kullanan {@link ApiMessages}
 * örneği. Sahte bir mesaj kaynağı yerine gerçeği kullanılır: aksi hâlde bir çeviri anahtarının
 * dosyada eksik olması testte değil, ancak canlıda fark edilirdi.
 */
final class TestMessages {

    private TestMessages() {
    }

    static ApiMessages create() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return new ApiMessages(source);
    }
}
