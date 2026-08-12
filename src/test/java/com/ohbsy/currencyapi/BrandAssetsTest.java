package com.ohbsy.currencyapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mührün <b>üç ayrı yerde inline</b> durmasının bedelini ödeyen test.
 *
 * <h2>Neden inline, neden bu test</h2>
 * Logo, CSS'in ulaşamadığı yerlerde de doğru görünsün diye her yüzeyde SVG olarak gömülüdür
 * (tanıtım sayfası, panelin girişi, panelin masthead'i). Bunun bedeli <b>ayrışmadır</b>:
 * logo değiştiğinde bir kopyanın güncellenmesi unutulur ve iki yüzeyde iki farklı işaret
 * görünür — üstelik hiçbir şey hata vermez, kimse fark etmez.
 *
 * <p>Bu test kanonik kaynağı ({@code static/logo.svg}) tek doğru sayar ve gömülü kopyaların
 * ondan sapmadığını doğrular. Angular tarafındaki dosyalar da okunur: iki yüzeyi aynı anda
 * gören tek yer burasıdır (admin panelinin kendi testleri Java kaynaklarını görmez).
 */
@DisplayName("Marka varlıkları — gömülü mühürler kanonik logoyla aynı")
class BrandAssetsTest {

    private static final Path CANONICAL = Path.of("src/main/resources/static/logo.svg");

    /** Mührün gömülü olduğu her yüzey. */
    private static final List<Path> SURFACES = List.of(
            Path.of("src/main/resources/static/index.html"),
            Path.of("admin-ui/src/app/pages/login/login.html"),
            Path.of("admin-ui/src/app/pages/keys/keys.html"));

    /** İlk {@code <svg …>…</svg>} bloğu — boşluk/satır sonu farkları normalleştirilir. */
    private static final Pattern SVG = Pattern.compile("<svg\\b.*?</svg>", Pattern.DOTALL);

    private String firstSvgOf(Path file) throws IOException {
        Matcher matcher = SVG.matcher(Files.readString(file));
        assertThat(matcher.find()).as("%s içinde <svg> bulunamadı", file).isTrue();
        return normalise(matcher.group());
    }

    /**
     * Karşılaştırma biçimden değil <b>şekilden</b> olmalı: kanonik dosya satırlara bölünmüş,
     * gömülü kopyalar tek satır. Ayrıca kanonik dosyada bulunup gömülü kopyalarda gereksiz
     * olan alanlar (xmlns, role, aria-label, yorumlar) elenir.
     */
    private String normalise(String svg) {
        return svg
                // (?s) şart: Java'da `.` varsayılan olarak satır sonunu EŞLEMEZ ve kanonik
                // dosyadaki yorum çok satırlıdır — bayrak olmadan yorum silinmeden kalır.
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("\\s+(xmlns|role|aria-label)=\"[^\"]*\"", "")
                .replaceAll("\\s+", " ")
                .replace("> <", "><")
                .trim();
    }

    @Test
    @DisplayName("Her yüzeydeki gömülü mühür, kanonik logo.svg ile AYNI")
    void embeddedSealsMatchCanonicalLogo() throws IOException {
        String canonical = firstSvgOf(CANONICAL);

        for (Path surface : SURFACES) {
            assertThat(firstSvgOf(surface))
                    .as("%s içindeki mühür logo.svg'den AYRIŞMIŞ — logo değişince tüm "
                            + "kopyalar birlikte güncellenmelidir", surface)
                    .isEqualTo(canonical);
        }
    }

    /**
     * Sekme ikonu kanonik mühürden AYRI bir varyanttır (16 pikselde halka ve ince çizgiler
     * lekeye dönüştüğü için) — bu bilinçli farkın yanlışlıkla "düzeltilmemesi" için sabitlenir.
     */
    @Test
    @DisplayName("Favicon ayrı bir varyanttır: halka yok, çizgiler kalın")
    void faviconIsDeliberatelySimplified() throws IOException {
        String favicon = Files.readString(Path.of("src/main/resources/static/favicon.svg"));

        assertThat(favicon).doesNotContain("<circle");
        assertThat(favicon).contains("stroke-width=\"4\"");
    }

    /** Panel kendi kopyalarını sunar; kaynak dosyalar iki tarafta da bulunmalıdır. */
    @Test
    @DisplayName("Marka varlıkları her iki yüzeyde de mevcut")
    void assetsExistOnBothSurfaces() {
        for (String asset : new String[] {
                "favicon.svg", "favicon-32.png", "apple-touch-icon.png", "logo.svg"}) {
            assertThat(Path.of("src/main/resources/static", asset)).exists();
            assertThat(Path.of("admin-ui/public", asset)).exists();
        }
    }
}
