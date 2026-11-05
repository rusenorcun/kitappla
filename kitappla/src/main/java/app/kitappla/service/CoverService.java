package app.kitappla.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.awt.Graphics2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Kitap kapağı görselleri. Kapak ya üye tarafından yüklenir ya da alışveriş linkinden
 * gelen adresten indirilir; her iki durumda da dosya <b>bizde</b> durur ve
 * {@code /uploads/covers/...} altından sunulur.
 *
 * <p>Uzak adresi olduğu gibi saklamak yerine indirmenin sebebi: birçok satış sitesi
 * görseli dışarıdan çağırınca vermiyor (referer kontrolü) ya da http üzerinden
 * sunuyor — https sayfada tarayıcı bunu engelliyor. İkisinde de kapak görünmüyor.</p>
 */
@Service
public class CoverService {

    private static final Logger log = LoggerFactory.getLogger(CoverService.class);

    /** Kapak için üst sınır; bundan büyüğü ne yüklenir ne indirilir. */
    private static final long MAX_BYTES = 3L * 1024 * 1024;

    /**
     * Kapaklar en fazla bu genişlikte saklanır. Liste kartları 220 px, detay sayfası ~300 px
     * gösterir; 600 px yüksek yoğunluklu ekranlar için yeterli pay bırakır.
     */
    static final int EN_GENIS_PIKSEL = 600;
    private static final int TIMEOUT_MS = 8000;

    /**
     * Piksel sınırı. Birkaç KB'lık bir PNG 50000×50000 boyut bildirebilir; çözülünce
     * gigabaytlarca bellek ister ve {@code ExitOnOutOfMemoryError} ile süreç kapanır.
     * Boyut, görsel çözülmeden başlıktan okunup bu sınırla denetlenir.
     */
    static final int EN_FAZLA_KENAR = 10_000;
    static final long EN_FAZLA_PIKSEL = 40_000_000L;
    private static final String UA = "Mozilla/5.0 (compatible; KitapplaBot/1.0)";

    private static final Map<String, String> UZANTI = Map.of(
            "image/jpeg", ".jpg",
            "image/jpg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif");

    private final Path dir;

    public CoverService(@Value("${kitappla.upload-dir}") String uploadDir) {
        this.dir = Path.of(uploadDir, "covers");
    }

    /**
     * Kapağı belirler: yüklenen dosya varsa o kazanır, yoksa linkten gelen adres indirilir.
     *
     * @return şablonun kullanacağı adres; hiçbiri yoksa null
     */
    public String resolve(MultipartFile uploaded, String remoteUrl) {
        String local = saveUpload(uploaded);
        if (local != null) return local;
        return saveFromUrl(remoteUrl);
    }

    /** Üyenin yüklediği görseli saklar. Görsel değilse ya da çok büyükse null döner. */
    public String saveUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;

        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!UZANTI.containsKey(type)) {
            throw new IllegalArgumentException("Kapak görseli JPG, PNG, WEBP ya da GIF olmalı.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Kapak görseli en fazla 3 MB olabilir.");
        }
        Path hedef = null;
        try {
            Files.createDirectories(dir);
            String name = UUID.randomUUID() + UZANTI.get(type);
            hedef = dir.resolve(name).toAbsolutePath();
            file.transferTo(hedef);
            if (!gercektenGorsel(hedef)) {
                Files.deleteIfExists(hedef);
                throw new IllegalArgumentException("Kapak görseli JPG, PNG, WEBP ya da GIF olmalı.");
            }
            if (!boyutUygun(hedef)) {
                Files.deleteIfExists(hedef);
                throw new IllegalArgumentException("Kapak görselinin çözünürlüğü çok yüksek.");
            }
            kucult(dir.resolve(name));
            return "/uploads/covers/" + name;
        } catch (IOException e) {
            if (hedef != null) {
                try { Files.deleteIfExists(hedef); } catch (IOException ignored) {}
            }
            throw new IllegalArgumentException("Kapak görseli kaydedilemedi.");
        }
    }

    /**
     * Dosyanın ilk baytlarına bakar. İstemcinin bildirdiği {@code Content-Type} kendi
     * seçtiği bir değerdir; ona güvenilirse {@code /uploads/covers/} altına herhangi bir
     * içerik ".png" adıyla konulup herkese açık sunulabiliyordu. Küçültme adımı bunu
     * yakalamıyor: okunamayan dosyayı sessizce olduğu gibi bırakıyor, WEBP/GIF'e ise
     * hiç dokunmuyor. Bu yüzden biçim baytlardan doğrulanır.
     */
    static boolean gercektenGorsel(Path dosya) throws IOException {
        byte[] b = new byte[16];
        int okunan;
        try (InputStream in = Files.newInputStream(dosya)) {
            okunan = in.readNBytes(b, 0, b.length);
        }
        if (okunan < 12) return false;
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (Arrays.equals(Arrays.copyOf(b, 8), png)) return true;
        // GIF: "GIF87a" / "GIF89a"
        String bas = new String(b, 0, 6, StandardCharsets.US_ASCII);
        if (bas.equals("GIF87a") || bas.equals("GIF89a")) return true;
        // WEBP: "RIFF"...."WEBP"
        return new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
    }

    /**
     * Uzak adresteki görseli indirip saklar. İndirme başarısız olursa <b>adresin kendisini</b>
     * döndürür; kapak hiç olmamasındansa tarayıcının doğrudan bağlanmayı denemesi daha iyi.
     * <p>
     * Tek istisna SSRF denetimi: iç ağ, loopback ya da çözülemeyen adresler için
     * {@code null} döner — böyle bir adres kapak olarak da saklanmaz.
     */
    private static final java.util.regex.Pattern YEREL_KAPAK =
            java.util.regex.Pattern.compile("^/uploads/covers/[A-Za-z0-9-]{1,64}\\.(jpg|png|webp|gif)$");

    public String saveFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("/uploads/covers/")) {                 // zaten bizde
            // Yalnızca yükleme ucunun ürettiği ad biçimi (UUID + uzantı); "../" ya da alt klasör içeren yol kabul edilmez.
            return YEREL_KAPAK.matcher(url).matches() ? url : null;
        }
        if (!url.matches("(?i)^https?://.+")) return null;

        try {
            URI currentUri = URI.create(url.trim());
            java.util.Set<String> visited = new java.util.HashSet<>();

            for (int redirect = 0; redirect <= 3; redirect++) {
                if (!SsrfValidator.isSafeUri(currentUri)) {
                    return null;
                }

                HttpURLConnection c = (HttpURLConnection) currentUri.toURL().openConnection();
                c.setRequestProperty("User-Agent", UA);
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setInstanceFollowRedirects(false);

                int code;
                try {
                    code = c.getResponseCode();
                } catch (Exception e) {
                    c.disconnect();
                    return url;
                }

                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = c.getHeaderField("Location");
                    c.disconnect();
                    if (location == null || location.isBlank()) {
                        return url;
                    }
                    URI nextUri = currentUri.resolve(location.trim());
                    if (!visited.add(nextUri.toString())) {
                        return url;
                    }
                    currentUri = nextUri;
                    continue;
                }

                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
                int noktaliVirgul = type.indexOf(';');
                if (noktaliVirgul > 0) type = type.substring(0, noktaliVirgul).trim();
                if (code != 200 || !UZANTI.containsKey(type)) {
                    c.disconnect();
                    return url;
                }

                Files.createDirectories(dir);
                String name = UUID.randomUUID() + UZANTI.get(type);
                Path hedef = dir.resolve(name);
                long yazilan;
                try (InputStream in = c.getInputStream()) {
                    yazilan = Files.copy(sinirli(in), hedef);
                } catch (Exception copyEx) {
                    Files.deleteIfExists(hedef);
                    throw copyEx;
                } finally {
                    c.disconnect();
                }
                // Uzak sunucunun bildirdiği tür de kendi seçtiği bir değerdir; baytlara bakılır.
                if (yazilan <= 0 || yazilan > MAX_BYTES || !gercektenGorsel(hedef) || !boyutUygun(hedef)) {
                    Files.deleteIfExists(hedef);
                    return url;
                }
                kucult(hedef);
                return "/uploads/covers/" + name;
            }
            return url;
        } catch (Exception e) {
            log.debug("Kapak indirilemedi: {}", url, e);
            return url;
        }
    }

    /**
     * Görselin boyutlarını çözmeden (yalnızca başlıktan) okur ve sınırı aşanları reddeder.
     * Okuyucusu olmayan biçimler (WEBP) sunucuda hiç çözülmediği için geçer.
     */
    static boolean boyutUygun(Path dosya) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(dosya.toFile())) {
            if (iis == null) return true;
            Iterator<ImageReader> okuyucular = ImageIO.getImageReaders(iis);
            if (!okuyucular.hasNext()) return true;
            ImageReader okuyucu = okuyucular.next();
            try {
                okuyucu.setInput(iis, true, true);
                long w = okuyucu.getWidth(0);
                long h = okuyucu.getHeight(0);
                return w > 0 && h > 0 && w <= EN_FAZLA_KENAR && h <= EN_FAZLA_KENAR && w * h <= EN_FAZLA_PIKSEL;
            } finally {
                okuyucu.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;   // başlığı okunamayan dosya güvenle değerlendirilemez
        }
    }

    /**
     * {@link #EN_GENIS_PIKSEL}'den geniş JPEG ve PNG kapakları yerinde küçültür (en-boy oranı korunur).
     * Telefon fotoğrafı gibi 3 MB'lık bir kapak ~60 KB'a iner; ev internetinin yükleme hızı sınırlı
     * olduğu için liste sayfalarında belirleyici. Okunamayan dosyalar ile WEBP/GIF olduğu gibi bırakılır.
     */
    static void kucult(Path dosya) {
        String ad = dosya.getFileName().toString();
        String bicim = ad.endsWith(".jpg") ? "jpg" : ad.endsWith(".png") ? "png" : null;
        if (bicim == null) return;
        try {
            BufferedImage kaynak = ImageIO.read(dosya.toFile());
            if (kaynak == null || kaynak.getWidth() <= EN_GENIS_PIKSEL) return;

            int yukseklik = Math.max(1, Math.round(kaynak.getHeight() * (float) EN_GENIS_PIKSEL / kaynak.getWidth()));
            int tur = bicim.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage kucuk = new BufferedImage(EN_GENIS_PIKSEL, yukseklik, tur);
            Graphics2D g = kucuk.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(kaynak, 0, 0, EN_GENIS_PIKSEL, yukseklik, null);
            g.dispose();
            ImageIO.write(kucuk, bicim, dosya.toFile());
        } catch (IOException | RuntimeException e) {
            log.debug("Kapak küçültülemedi, olduğu gibi bırakıldı: {}", dosya, e);
        }
    }

    /** Boyut sınırını aşan gövdeyi okumayı bırakır; kötü niyetli/dev dosyaya karşı. */
    private static InputStream sinirli(InputStream in) {
        final long limit = MAX_BYTES + 1;
        return new InputStream() {
            private long okunan;

            @Override
            public int read() throws IOException {
                if (okunan >= limit) return -1;
                int b = in.read();
                if (b >= 0) okunan++;
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (okunan >= limit) return -1;
                int kalan = (int) Math.min(len, limit - okunan);
                int n = in.read(buf, off, kalan);
                if (n > 0) okunan += n;
                return n;
            }
        };
    }
}
