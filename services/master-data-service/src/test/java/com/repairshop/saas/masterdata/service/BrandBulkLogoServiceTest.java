package com.repairshop.saas.masterdata.service;

import com.repairshop.saas.common.media.MediaProperties;
import com.repairshop.saas.common.media.MediaStorageException;
import com.repairshop.saas.common.media.MediaUploadValidator;
import com.repairshop.saas.common.media.MediaValidationException;
import com.repairshop.saas.common.media.S3StorageService;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.PreviewItem;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.PreviewResponse;
import com.repairshop.saas.masterdata.dto.BrandBulkLogoDtos.UploadResult;
import com.repairshop.saas.masterdata.dto.TaxonomyImageResponse;
import com.repairshop.saas.masterdata.entity.MasterBrand;
import com.repairshop.saas.masterdata.repository.MasterBannerRepository;
import com.repairshop.saas.masterdata.repository.MasterBrandRepository;
import com.repairshop.saas.masterdata.repository.MasterCategoryMenuRepository;
import com.repairshop.saas.masterdata.repository.MasterDeviceCategoryRepository;
import com.repairshop.saas.masterdata.repository.ModelCompatibilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Bulk brand logo upload: matching, modes, validation, failure handling and audit. */
class BrandBulkLogoServiceTest {

    private static final String CDN = "https://cdn.neargfix.com/";

    private final MasterBrandRepository brands = mock(MasterBrandRepository.class);
    private final S3StorageService storage = mock(S3StorageService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final MasterAuditLog audit = mock(MasterAuditLog.class);
    private final MediaProperties props = new MediaProperties();
    private final MediaUploadValidator validator = new MediaUploadValidator(props);
    private final TaxonomyMediaService media = new TaxonomyMediaService(
            mock(MasterDeviceCategoryRepository.class), brands, mock(MasterBannerRepository.class),
            mock(ModelCompatibilityRepository.class), mock(MasterCategoryMenuRepository.class),
            validator, storage, props, tx);
    private final BrandBulkLogoService service = new BrandBulkLogoService(brands, media, validator, audit, 200);
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
        when(storage.put(anyString(), any(), anyString())).thenAnswer(i -> CDN + i.getArgument(0));
        when(brands.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private MasterBrand brand(String name, String imageUrl) {
        MasterBrand b = MasterBrand.builder().id(UUID.randomUUID()).name(name).imageUrl(imageUrl).build();
        when(brands.findById(b.getId())).thenReturn(Optional.of(b));
        when(brands.lockById(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    // ── Matching ────────────────────────────────────────────────────────────

    @Test
    void namesNormaliseCaseSpacesUnderscoresHyphensAndAccents() {
        assertEquals("samsung", BrandLogoNames.fileKey("SAMSUNG.PNG"));
        assertEquals("oneplus", BrandLogoNames.fileKey("one_plus.webp"));
        assertEquals("oneplus", BrandLogoNames.normalize("One Plus"));
        assertEquals("samsung", BrandLogoNames.fileKey("Sam-sung.jpg"));
        assertEquals("citroen", BrandLogoNames.fileKey("Citroën.jpeg"));
        assertEquals("apple", BrandLogoNames.fileKey("logos/sub/apple.png"), "ZIP folders are ignored");
        assertEquals("evil.png", BrandLogoNames.baseName("..\\..\\evil.png"), "no path traversal survives");
        assertEquals("", BrandLogoNames.fileKey("   "));
    }

    @Test
    void previewMatchesExactAndNormalisedNamesAndFlagsTheRest() {
        MasterBrand samsung = brand("Samsung", null);
        MasterBrand apple = brand("Apple", CDN + "master/brands/apple-1.png");
        when(brands.findAll()).thenReturn(List.of(samsung, apple,
                brandRow("OnePlus"), brandRow("One Plus")));

        PreviewResponse r = service.preview(List.of("SAMSUNG.png", "apple.webp", "one-plus.jpg", "nokia.png", "samsung_.jpeg"));
        Map<String, PreviewItem> byFile = new java.util.HashMap<>();
        r.items().forEach(i -> byFile.put(i.fileName(), i));

        assertEquals("MATCHED", byFile.get("SAMSUNG.png").status());
        assertEquals(samsung.getId(), byFile.get("SAMSUNG.png").matches().get(0).id());
        assertFalse(byFile.get("SAMSUNG.png").matches().get(0).hasLogo());
        assertEquals("MATCHED", byFile.get("apple.webp").status());
        assertTrue(byFile.get("apple.webp").matches().get(0).hasLogo(), "existing logo is reported");
        assertEquals("AMBIGUOUS", byFile.get("one-plus.jpg").status(), "two brands → never auto-selected");
        assertEquals(2, byFile.get("one-plus.jpg").matches().size());
        assertEquals("UNMATCHED", byFile.get("nokia.png").status());
        assertEquals("DUPLICATE", byFile.get("samsung_.jpeg").status(), "second file for the same name");
        assertEquals(List.of(2, 1, 1, 1), List.of(r.matched(), r.ambiguous(), r.unmatched(), r.duplicates()));
    }

    private MasterBrand brandRow(String name) {
        return MasterBrand.builder().id(UUID.randomUUID()).name(name).build();
    }

    @Test
    void previewRejectsEmptyAndOversizedBatches() {
        assertThrows(MediaValidationException.class, () -> service.preview(List.of()));
        BrandBulkLogoService small = new BrandBulkLogoService(brands, media, validator, audit, 100);
        List<String> files = IntStream.range(0, 101).mapToObj(i -> "b" + i + ".png").toList();
        assertThrows(MediaValidationException.class, () -> small.preview(files));
        when(brands.findAll()).thenReturn(Collections.emptyList());
        assertEquals(100, small.preview(files.subList(0, 100)).items().size(), "100 per batch is allowed");
    }

    // ── Upload modes ────────────────────────────────────────────────────────

    @Test
    void missingOnlyUploadsALogoForABrandWithoutOne() throws Exception {
        MasterBrand b = brand("Samsung", null);

        UploadResult r = service.upload(b.getId(), png(512, 512, false), null, UUID.randomUUID().toString(), "Samsung.webp", admin);

        assertEquals("UPLOADED", r.status());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(key.capture(), bytes.capture(), eq("image/png"));
        assertTrue(key.getValue().startsWith("master/brands/samsung-"), "unique (versioned) key: " + key.getValue());
        assertEquals(CDN + key.getValue(), b.getImageUrl());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(bytes.getValue()));
        assertEquals(512, stored.getWidth());
        assertEquals(512, stored.getHeight());
        verify(audit).record(eq(admin), eq("BRAND_LOGO_UPLOADED"), eq("BRAND"), eq(b.getId()), anyMap());
    }

    @Test
    void missingOnlySkipsABrandThatAlreadyHasALogo() throws Exception {
        String existing = CDN + "master/brands/apple-old.png";
        MasterBrand b = brand("Apple", existing);

        UploadResult r = service.upload(b.getId(), png(512, 512, false), "MISSING_ONLY", null, "apple.png", admin);

        assertEquals("SKIPPED", r.status());
        assertEquals(existing, b.getImageUrl());
        verify(storage, never()).put(anyString(), any(), anyString());
        verify(audit).record(eq(admin), eq("BRAND_LOGO_SKIPPED"), eq("BRAND"), eq(b.getId()), anyMap());
    }

    @Test
    void aLegacyInlineImageCountsAsALogo() throws Exception {
        MasterBrand b = brand("Nokia", null);
        b.setImageBase64("data:image/png;base64,AAAA");
        assertEquals("SKIPPED", service.upload(b.getId(), png(512, 512, false), null, null, "nokia.png", admin).status());
    }

    @Test
    void aLogoAddedConcurrentlyIsNeverOverwrittenInMissingOnlyMode() throws Exception {
        MasterBrand stale = MasterBrand.builder().id(UUID.randomUUID()).name("Vivo").build();
        MasterBrand locked = MasterBrand.builder().id(stale.getId()).name("Vivo").imageUrl(CDN + "master/brands/vivo-x.png").build();
        when(brands.findById(stale.getId())).thenReturn(Optional.of(stale));
        when(brands.lockById(stale.getId())).thenReturn(Optional.of(locked));

        UploadResult r = service.upload(stale.getId(), png(512, 512, false), "MISSING_ONLY", null, "vivo.png", admin);

        assertEquals("SKIPPED", r.status());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), any(), anyString());
        verify(storage).deleteQuietly(key.getValue());
        verify(brands, never()).save(any());
        assertEquals(CDN + "master/brands/vivo-x.png", locked.getImageUrl());
    }

    @Test
    void replaceModeOverwritesAndRemovesTheSupersededObject() throws Exception {
        String existing = CDN + "master/brands/apple-old.png";
        MasterBrand b = brand("Apple", existing);
        when(brands.countByImageUrl(existing)).thenReturn(0L);
        when(storage.deleteSupersededQuietly(eq(existing), anyString())).thenReturn(true);

        UploadResult r = service.upload(b.getId(), png(512, 512, false), "replace", null, "Apple.png", admin);

        assertEquals("UPLOADED", r.status());
        assertEquals(existing, r.previousImageUrl());
        assertTrue(r.previousImageRemoved());
        assertNotEquals(existing, b.getImageUrl());
    }

    @Test
    void unknownModeIsRejected() {
        MasterBrand b = brand("Apple", null);
        assertThrows(MediaValidationException.class,
                () -> service.upload(b.getId(), png(512, 512, false), "OVERWRITE_ALL", null, null, admin));
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    void wrongSizeNonPngAndGarbageAreRejectedBeforeAnythingIsStored() throws Exception {
        MasterBrand b = brand("Samsung", null);
        assertThrows(MediaValidationException.class,
                () -> service.upload(b.getId(), png(256, 256, false), null, null, "small.png", admin));
        assertThrows(MediaValidationException.class,
                () -> service.upload(b.getId(), jpeg(), null, null, "photo.jpg", admin));
        assertThrows(MediaValidationException.class,
                () -> service.upload(b.getId(), new MockMultipartFile("image", "fake.png", "image/png", "not an image".getBytes()), null, null, "fake.png", admin));
        assertThrows(MediaValidationException.class,
                () -> service.upload(b.getId(), new MockMultipartFile("image", "x.png", "image/png", new byte[0]), null, null, "x.png", admin));
        verify(storage, never()).put(anyString(), any(), anyString());
        verify(audit, times(4)).record(eq(admin), eq("BRAND_LOGO_FAILED"), eq("BRAND"), eq(b.getId()), anyMap());
        assertNull(b.getImageUrl());
    }

    @Test
    void aHugeCanvasIsRefusedFromItsHeaderBeforeDecoding() throws Exception {
        MasterBrand b = brand("Samsung", null);
        // 6000 × 6000 white compresses to a few KB but would decode to ~140 MB.
        MediaValidationException e = assertThrows(MediaValidationException.class,
                () -> service.upload(b.getId(), png(6000, 6000, false), null, null, "bomb.png", admin));
        assertTrue(e.getMessage().contains("6000 × 6000"));
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void transparencyIsFlattenedOntoWhite() throws Exception {
        MasterBrand b = brand("Samsung", null);
        service.upload(b.getId(), png(512, 512, true), null, null, "samsung.png", admin);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(anyString(), bytes.capture(), eq("image/png"));
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(bytes.getValue()));
        assertEquals(0xFFFFFF, stored.getRGB(0, 0) & 0xFFFFFF, "transparent corner is now white");
        assertEquals(0xFF0000, stored.getRGB(256, 256) & 0xFFFFFF, "logo colour preserved");
    }

    // ── Failures ────────────────────────────────────────────────────────────

    @Test
    void anS3FailureLeavesTheExistingLogoAndCanBeRetried() throws Exception {
        String existing = CDN + "master/brands/apple-old.png";
        MasterBrand b = brand("Apple", existing);
        when(storage.put(anyString(), any(), anyString()))
                .thenThrow(new MediaStorageException("S3 unavailable"))
                .thenAnswer(i -> CDN + i.getArgument(0));

        assertThrows(MediaStorageException.class,
                () -> service.upload(b.getId(), png(512, 512, false), "REPLACE", null, "apple.png", admin));
        assertEquals(existing, b.getImageUrl(), "untouched after the failure");
        verify(brands, never()).save(any());
        verify(audit).record(eq(admin), eq("BRAND_LOGO_FAILED"), eq("BRAND"), eq(b.getId()), anyMap());

        UploadResult retry = service.upload(b.getId(), png(512, 512, false), "REPLACE", null, "apple.png", admin);
        assertEquals("UPLOADED", retry.status());
    }

    @Test
    void aDatabaseFailureRemovesTheNewObjectSoNothingIsOrphaned() throws Exception {
        MasterBrand b = brand("Samsung", null);
        when(brands.save(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class,
                () -> service.upload(b.getId(), png(512, 512, false), null, null, "samsung.png", admin));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), any(), anyString());
        verify(storage).deleteQuietly(key.getValue());
        verify(audit).record(eq(admin), eq("BRAND_LOGO_FAILED"), eq("BRAND"), eq(b.getId()), anyMap());
    }

    @Test
    void anUnknownBrandIsRejected() {
        UUID missing = UUID.randomUUID();
        when(brands.findById(missing)).thenReturn(Optional.empty());
        assertThrows(MediaValidationException.class,
                () -> service.upload(missing, png(512, 512, false), null, null, "x.png", admin));
    }

    // ── The single upload is unchanged ──────────────────────────────────────

    @Test
    void theExistingSingleUploadStillReplacesAnyImage() throws Exception {
        String existing = CDN + "master/brands/apple-old.png";
        MasterBrand b = brand("Apple", existing);
        MockMultipartFile jpg = jpeg();

        TaxonomyImageResponse r = media.uploadBrandImage(b.getId(), jpg);

        assertEquals(existing, r.previousImageUrl());
        assertTrue(r.imageKey().endsWith(".jpg"), "single upload keeps its own format: " + r.imageKey());
        assertEquals(CDN + r.imageKey(), b.getImageUrl());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static MockMultipartFile png(int w, int h, boolean transparent) throws Exception {
        BufferedImage img = new BufferedImage(w, h, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        if (!transparent) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
        }
        g.setColor(Color.RED);
        g.fillRect(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("image", "logo.png", "image/png", out.toByteArray());
    }

    private static MockMultipartFile jpeg() throws Exception {
        BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return new MockMultipartFile("image", "photo.jpg", "image/jpeg", out.toByteArray());
    }
}
