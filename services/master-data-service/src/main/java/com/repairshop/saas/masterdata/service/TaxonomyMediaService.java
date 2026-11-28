package com.repairshop.saas.masterdata.service;

import com.repairshop.saas.common.media.MediaKeys;
import com.repairshop.saas.common.media.MediaProperties;
import com.repairshop.saas.common.media.MediaUploadValidator;
import com.repairshop.saas.common.media.MediaValidationException;
import com.repairshop.saas.common.media.S3StorageService;
import com.repairshop.saas.masterdata.dto.TaxonomyImageResponse;
import com.repairshop.saas.masterdata.entity.MasterBanner;
import com.repairshop.saas.masterdata.entity.MasterBrand;
import com.repairshop.saas.masterdata.entity.MasterCategoryMenu;
import com.repairshop.saas.masterdata.entity.MasterDeviceCategory;
import com.repairshop.saas.masterdata.entity.ModelCompatibility;
import com.repairshop.saas.masterdata.repository.MasterBannerRepository;
import com.repairshop.saas.masterdata.repository.MasterBrandRepository;
import com.repairshop.saas.masterdata.repository.MasterCategoryMenuRepository;
import com.repairshop.saas.masterdata.repository.MasterDeviceCategoryRepository;
import com.repairshop.saas.masterdata.repository.ModelCompatibilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Category and brand artwork on media.ggfix.in.
 *
 * <h2>Why this stores a URL and adds no columns</h2>
 * The first version of this added image_key / image_original_name / image_content_type
 * / image_size_bytes to both tables. That required a migration, the migration was not
 * applied to the production database, and the resulting drift 500ed the whole
 * catalogue — twice.
 *
 * None of that metadata was load-bearing. What the product needs is the bytes in S3
 * instead of inlined in Postgres, and image_url already holds a URL that every client
 * — admin, customer app, shop app — reads today. So the upload writes the public
 * media.ggfix.in URL into that existing column and adds no schema at all. Same
 * outcome for storage, deployable against the live database as it stands, and the
 * same approach already used for owner KYC and shop artwork in auth-service.
 *
 * image_base64 is cleared on upload: it holds the legacy inline data URI, and leaving
 * it would keep a multi-megabyte copy in the row after the point of this change was
 * to get it out.
 */
@Service
public class TaxonomyMediaService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyMediaService.class);

    private final MasterDeviceCategoryRepository categoryRepo;
    private final MasterBrandRepository brandRepo;
    private final MasterBannerRepository bannerRepo;
    private final ModelCompatibilityRepository compatibilityRepo;
    private final MasterCategoryMenuRepository categoryMenuRepo;
    private final MediaUploadValidator validator;
    private final S3StorageService storage;
    /** Only for {@link MediaProperties#keyForPublicUrl} — resolving a stored URL back to its key. */
    private final MediaProperties props;
    private final TransactionTemplate tx;

    public TaxonomyMediaService(MasterDeviceCategoryRepository categoryRepo,
                                MasterBrandRepository brandRepo,
                                MasterBannerRepository bannerRepo,
                                ModelCompatibilityRepository compatibilityRepo,
                                MasterCategoryMenuRepository categoryMenuRepo,
                                MediaUploadValidator validator,
                                S3StorageService storage,
                                MediaProperties props,
                                TransactionTemplate tx) {
        this.categoryRepo = categoryRepo;
        this.brandRepo = brandRepo;
        this.bannerRepo = bannerRepo;
        this.compatibilityRepo = compatibilityRepo;
        this.categoryMenuRepo = categoryMenuRepo;
        this.validator = validator;
        this.storage = storage;
        this.props = props;
        this.tx = tx;
    }

    /** Upload or replace a category tile. */
    public TaxonomyImageResponse uploadCategoryImage(UUID categoryId, MultipartFile file) {
        MasterDeviceCategory category = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new MediaValidationException("No category with id " + categoryId + "."));

        MediaUploadValidator.ValidatedUpload upload = validator.validateImage(file);
        String key = MediaKeys.masterCategoryImageKey(category.getName(), upload.extension());

        // Read before the update, because the row is about to stop pointing at it.
        String previousUrl = category.getImageUrl();

        // Upload before the update: an unreferenced object is sweepable, whereas a row
        // pointing at a missing key is a broken tile on the customer Home screen.
        String url = storage.put(key, upload.bytes(), upload.contentType());

        MasterDeviceCategory saved;
        try {
            saved = tx.execute(status -> {
                MasterDeviceCategory row = categoryRepo.findById(categoryId).orElseThrow();
                row.setImageUrl(url);
                row.setImageBase64(null);
                MasterDeviceCategory updated = categoryRepo.save(row);
                log.info("Category {} image -> {}", categoryId, key);
                return updated;
            });
        } catch (RuntimeException e) {
            log.error("Category {} update failed after upload; removing orphaned object {}", categoryId, key, e);
            storage.deleteQuietly(key);
            throw e;
        }

        boolean previousRemoved = removeSuperseded(previousUrl, key, MediaKeys.MASTER_CATEGORIES_ROOT,
                () -> categoryRepo.countByImageUrl(previousUrl));
        return new TaxonomyImageResponse(saved.getId(), saved.getName(), key, saved.getImageUrl(),
                upload.originalName(), upload.contentType(), upload.size(), previousUrl, previousRemoved);
    }

    /** Upload or replace a brand logo. */
    public TaxonomyImageResponse uploadBrandImage(UUID brandId, MultipartFile file) {
        MasterBrand brand = brandRepo.findById(brandId)
                .orElseThrow(() -> new MediaValidationException("No brand with id " + brandId + "."));
        return storeBrandImage(brand, validator.validateImage(file), false);
    }

    /**
     * Store an already-validated logo and point the brand's image_url at it — the
     * single upload above and the bulk upload (BrandBulkLogoService) share this.
     *
     * The object goes to a fresh unique key first; the row is updated only after
     * that succeeds, and the object is removed again if the update fails, so a
     * failed replacement leaves the existing logo untouched. The superseded object
     * is deleted only once the row has moved on (removeSuperseded).
     *
     * @param onlyIfMissing refuse if the brand already has a logo — checked before
     *                      the upload and again under the row lock, so a concurrent
     *                      upload is never overwritten. Throws
     *                      {@link LogoAlreadyPresentException}; nothing is changed.
     */
    public TaxonomyImageResponse storeBrandImage(MasterBrand brand, MediaUploadValidator.ValidatedUpload upload,
                                                 boolean onlyIfMissing) {
        UUID brandId = brand.getId();
        if (onlyIfMissing && hasLogo(brand)) throw new LogoAlreadyPresentException(brand.getName());
        String key = MediaKeys.masterBrandImageKey(brand.getName(), upload.extension());
        String url = storage.put(key, upload.bytes(), upload.contentType());

        MasterBrand saved;
        String[] previous = new String[1];
        try {
            saved = tx.execute(status -> {
                MasterBrand row = brandRepo.lockById(brandId)
                        .orElseThrow(() -> new MediaValidationException("No brand with id " + brandId + "."));
                if (onlyIfMissing && hasLogo(row)) throw new LogoAlreadyPresentException(row.getName());
                previous[0] = row.getImageUrl();
                row.setImageUrl(url);
                row.setImageBase64(null);
                MasterBrand updated = brandRepo.save(row);
                log.info("Brand {} image -> {}", brandId, key);
                return updated;
            });
        } catch (RuntimeException e) {
            if (!(e instanceof LogoAlreadyPresentException)) {
                log.error("Brand {} update failed after upload; removing orphaned object {}", brandId, key, e);
            }
            storage.deleteQuietly(key);
            throw e;
        }

        String previousUrl = previous[0];
        boolean previousRemoved = removeSuperseded(previousUrl, key, MediaKeys.MASTER_BRANDS_ROOT,
                () -> brandRepo.countByImageUrl(previousUrl));
        return new TaxonomyImageResponse(saved.getId(), saved.getName(), key, saved.getImageUrl(),
                upload.originalName(), upload.contentType(), upload.size(), previousUrl, previousRemoved);
    }

    /**
     * Upload or replace a home-screen banner image.
     *
     * Keyed on the banner's title — {@code banner/slider-1-8ab31f04.jpg} — so the
     * bucket reads the way the admin list does. A banner with no title cannot be
     * keyed, and is rejected rather than filed under something invented.
     */
    public TaxonomyImageResponse uploadBannerImage(UUID bannerId, MultipartFile file) {
        MasterBanner banner = bannerRepo.findById(bannerId)
                .orElseThrow(() -> new MediaValidationException("No banner with id " + bannerId + "."));
        if (banner.getTitle() == null || banner.getTitle().isBlank()) {
            throw new MediaValidationException(
                    "This banner has no title, so its image cannot be named. Set a title first.");
        }

        MediaUploadValidator.ValidatedUpload upload = validator.validateImage(file);
        String key = MediaKeys.bannerImageKey(banner.getTitle(), upload.extension());
        String previousUrl = banner.getImageUrl();
        String url = storage.put(key, upload.bytes(), upload.contentType());

        MasterBanner saved;
        try {
            saved = tx.execute(status -> {
                MasterBanner row = bannerRepo.findById(bannerId).orElseThrow();
                row.setImageUrl(url);
                row.setImageBase64(null);
                MasterBanner updated = bannerRepo.save(row);
                log.info("Banner {} image -> {}", bannerId, key);
                return updated;
            });
        } catch (RuntimeException e) {
            log.error("Banner {} update failed after upload; removing orphaned object {}", bannerId, key, e);
            storage.deleteQuietly(key);
            throw e;
        }

        boolean previousRemoved = removeSuperseded(previousUrl, key, MediaKeys.BANNER_ROOT,
                () -> bannerRepo.countByImageUrl(previousUrl));
        return new TaxonomyImageResponse(saved.getId(), saved.getTitle(), key, saved.getImageUrl(),
                upload.originalName(), upload.contentType(), upload.size(), previousUrl, previousRemoved);
    }

    /**
     * Upload or replace the reference photo on a Model Compatibility box.
     *
     * Keyed on the box NUMBER — {@code master/model-compatibility/a-12-9d3f7b10.jpg} —
     * which migration 79 keeps unique, so the bucket browses the way the shelf is
     * labelled. Id-scoped like the others: the box has to be saved before its photo
     * can be named, which is why the admin form uploads after the save rather than
     * before it.
     */
    public TaxonomyImageResponse uploadCompatibilityImage(UUID compatibilityId, MultipartFile file) {
        ModelCompatibility box = compatibilityRepo.findById(compatibilityId)
                .orElseThrow(() -> new MediaValidationException(
                        "No compatibility box with id " + compatibilityId + "."));
        if (box.getBoxNo() == null || box.getBoxNo().isBlank()) {
            throw new MediaValidationException(
                    "This box has no Box No, so its image cannot be named. Set a Box No first.");
        }

        MediaUploadValidator.ValidatedUpload upload = validator.validateImage(file);
        String key = MediaKeys.modelCompatibilityImageKey(box.getBoxNo(), upload.extension());
        String previousUrl = box.getReferenceImageUrl();
        String url = storage.put(key, upload.bytes(), upload.contentType());

        ModelCompatibility saved;
        try {
            saved = tx.execute(status -> {
                ModelCompatibility row = compatibilityRepo.findById(compatibilityId).orElseThrow();
                row.setReferenceImageUrl(url);
                ModelCompatibility updated = compatibilityRepo.save(row);
                log.info("Compatibility box {} image -> {}", compatibilityId, key);
                return updated;
            });
        } catch (RuntimeException e) {
            log.error("Compatibility box {} update failed after upload; removing orphaned object {}",
                    compatibilityId, key, e);
            storage.deleteQuietly(key);
            throw e;
        }

        boolean previousRemoved = removeSuperseded(previousUrl, key, MediaKeys.MASTER_COMPATIBILITY_ROOT,
                () -> compatibilityRepo.countByReferenceImageUrl(previousUrl));
        return new TaxonomyImageResponse(saved.getId(), saved.getBoxNo(), key, saved.getReferenceImageUrl(),
                upload.originalName(), upload.contentType(), upload.size(), previousUrl, previousRemoved);
    }

    /**
     * Upload or replace a Repair/Sell/Buy category-menu tile image.
     *
     * Keyed on BOTH the tile's category type and its name —
     * {@code master/category-menu/repair/mobile-8ab31f04.jpg} — because the same
     * menu name (e.g. "Mobile") can exist under more than one menu, and keying on
     * the name alone (as banners do on title) would let one overwrite the
     * other's folder. A tile with no name cannot be keyed, and is rejected
     * rather than filed under something invented.
     */
    public TaxonomyImageResponse uploadCategoryMenuImage(UUID id, MultipartFile file) {
        MasterCategoryMenu menu = categoryMenuRepo.findById(id)
                .orElseThrow(() -> new MediaValidationException("No category menu item with id " + id + "."));
        if (menu.getMenuName() == null || menu.getMenuName().isBlank()) {
            throw new MediaValidationException(
                    "This category menu item has no name, so its image cannot be named. Set a name first.");
        }

        MediaUploadValidator.ValidatedUpload upload = validator.validateImage(file);
        String key = MediaKeys.categoryMenuImageKey(menu.getCategoryType().name(), menu.getMenuName(), upload.extension());
        String previousUrl = menu.getImageUrl();
        String url = storage.put(key, upload.bytes(), upload.contentType());

        MasterCategoryMenu saved;
        try {
            saved = tx.execute(status -> {
                MasterCategoryMenu row = categoryMenuRepo.findById(id).orElseThrow();
                row.setImageUrl(url);
                MasterCategoryMenu updated = categoryMenuRepo.save(row);
                log.info("Category menu {} image -> {}", id, key);
                return updated;
            });
        } catch (RuntimeException e) {
            log.error("Category menu {} update failed after upload; removing orphaned object {}", id, key, e);
            storage.deleteQuietly(key);
            throw e;
        }

        boolean previousRemoved = removeSuperseded(previousUrl, key, MediaKeys.MASTER_CATEGORY_MENU_ROOT,
                () -> categoryMenuRepo.countByImageUrl(previousUrl));
        return new TaxonomyImageResponse(saved.getId(), saved.getMenuName(), key, saved.getImageUrl(),
                upload.originalName(), upload.contentType(), upload.size(), previousUrl, previousRemoved);
    }

    // ---------------------------------------------------------------- internals --

    /**
     * Delete the object this upload superseded, strictly after the commit.
     *
     * These used to be left in the bucket, on the grounds that no image_key column
     * exists so the old key could not be proven. It can be: the stored URL is composed
     * from the key by {@link com.repairshop.saas.common.media.MediaProperties#publicUrl},
     * and {@code keyForPublicUrl} inverts that exactly — a data URI or a leftover
     * Cloudinary link resolves to nothing and is left alone. Two more conditions hold
     * before anything goes, because deleting a live object breaks a tile:
     *
     * <ul>
     *   <li>the key must sit under this record type's own root, so a URL pasted in by
     *       hand cannot make a brand upload delete a category's artwork;</li>
     *   <li>no other row of the same kind may still reference it — every upload mints
     *       a unique key, but the URL column is hand-editable, so two rows can end up
     *       sharing one image.</li>
     * </ul>
     *
     * Failures are swallowed: the upload has already succeeded, and failing the whole
     * request over a leaked object would be the worse trade. The outcome is returned
     * so the admin can report which happened.
     *
     * @param stillReferenced counts rows of this kind still pointing at previousUrl.
     *                        Evaluated only once the cheaper checks pass, so the common
     *                        "nothing of ours to delete" case costs no query.
     */
    /** True when the brand shows a logo today: an image URL or a legacy inline image. */
    public static boolean hasLogo(MasterBrand b) {
        return (b.getImageUrl() != null && !b.getImageUrl().isBlank())
                || (b.getImageBase64() != null && !b.getImageBase64().isBlank());
    }

    /** A "missing logos only" upload found the brand already has a logo; nothing was changed. */
    public static class LogoAlreadyPresentException extends RuntimeException {
        public LogoAlreadyPresentException(String brandName) {
            super("\"" + brandName + "\" already has a logo. Choose \"Replace existing logos\" to overwrite it.");
        }
    }

    private boolean removeSuperseded(String previousUrl, String newKey, String root,
                                     LongSupplier stillReferenced) {
        String previousKey = props.keyForPublicUrl(previousUrl);
        if (previousKey == null || previousKey.equals(newKey)) {
            return false;
        }
        if (!MediaKeys.isUnder(previousKey, root)) {
            log.info("Keeping {} — outside {}, so it may belong to another record", previousKey, root);
            return false;
        }
        if (stillReferenced.getAsLong() > 0) {
            log.info("Keeping {} — still referenced by another row", previousKey);
            return false;
        }
        return storage.deleteSupersededQuietly(previousUrl, newKey);
    }
}
