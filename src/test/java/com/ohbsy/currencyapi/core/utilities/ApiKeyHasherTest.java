package com.ohbsy.currencyapi.core.utilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiKeyHasher — üretim, özet, maskeleme")
class ApiKeyHasherTest {

    @Test
    @DisplayName("Ham anahtar tanınabilir bir önekle başlar ve her seferinde farklıdır")
    void generatesUniquePrefixedKeys() {
        String a = ApiKeyHasher.generateRawKey();
        String b = ApiKeyHasher.generateRawKey();

        assertThat(a).startsWith("cur_");
        assertThat(b).startsWith("cur_");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Aynı ham anahtar her zaman aynı hash'i üretir")
    void sameInputSameHash() {
        String raw = ApiKeyHasher.generateRawKey();

        assertThat(ApiKeyHasher.sha256Hex(raw)).isEqualTo(ApiKeyHasher.sha256Hex(raw));
    }

    @Test
    @DisplayName("Farklı ham anahtarlar farklı hash üretir")
    void differentInputDifferentHash() {
        assertThat(ApiKeyHasher.sha256Hex(ApiKeyHasher.generateRawKey()))
                .isNotEqualTo(ApiKeyHasher.sha256Hex(ApiKeyHasher.generateRawKey()));
    }

    @Test
    @DisplayName("Önizleme ham anahtarın ortasını gizler, başını/sonunu korur")
    void previewMasksMiddle() {
        String raw = "cur_ab12cdefghijklmnop_wxyz";

        String preview = ApiKeyHasher.preview(raw);

        assertThat(preview).startsWith("cur_ab12").endsWith("wxyz").contains("…");
        assertThat(preview).doesNotContain(raw.substring(8, raw.length() - 4));
    }
}
