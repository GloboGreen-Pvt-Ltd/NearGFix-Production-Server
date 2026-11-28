package com.repairshop.saas.masterdata.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Filename ↔ brand-name matching for the bulk logo upload.
 *
 * Both sides are reduced to lowercase ASCII letters and digits, so
 * "SAMSUNG.PNG", "samsung.webp" and "Sam-sung.jpg" all match the brand
 * "Samsung", and "One_Plus.png" matches "OnePlus" or "One Plus". Spaces, underscores, hyphens, dots, accents and case
 * are ignored; nothing else (no "-logo" stripping, no fuzzy distance), so a
 * match is always explainable to the admin.
 */
public final class BrandLogoNames {

    private BrandLogoNames() {}

    /** Matching key: accents folded, lowercase, only [a-z0-9]. Empty for null/blank. */
    public static String normalize(String s) {
        if (s == null) return "";
        String folded = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /** The last path segment — a ZIP entry like "logos/apple.png" or "..\\x.png" keeps only its name. */
    public static String baseName(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1).trim();
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    /** "apple.logo.png" → "apple.logo"; a name without an extension is returned as is. */
    public static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Key for an uploaded file name: path and extension removed, then {@link #normalize}. */
    public static String fileKey(String fileName) {
        return normalize(stripExtension(baseName(fileName)));
    }
}
