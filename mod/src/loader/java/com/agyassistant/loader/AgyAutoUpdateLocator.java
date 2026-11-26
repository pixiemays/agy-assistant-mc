package com.agyassistant.loader;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Early NeoForge FML service-layer locator that downloads the latest agyassistant mod jar
 * from GitHub Releases BEFORE mods and assets load, avoiding a second launch on heavy modpacks.
 */
public final class AgyAutoUpdateLocator implements IModFileCandidateLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("agyassistant.loader");
    private static final String REPO = "pixiemays/agy-assistant-mc";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ASSET_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ASSET_SIZE_PATTERN = Pattern.compile("\"size\"\\s*:\\s*(\\d+)");
    private static final Pattern ASSET_URL_PATTERN = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern AUTO_UPDATE_FALSE = Pattern.compile("\"autoUpdate\"\\s*:\\s*false");

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        System.setProperty("agyassistant.loader.active", "true");

        try {
            Path modsDir = FMLPaths.MODSDIR.get();
            if (hasStandaloneModInModsDir(modsDir)) {
                LOGGER.warn("Standalone agyassistant-*.jar found in mods/; skipping loader to avoid duplicate mod ID.");
                return;
            }

            Path configDir = FMLPaths.CONFIGDIR.get().resolve("agyassistant");
            Path cacheDir = configDir.resolve("updates");
            Files.createDirectories(cacheDir);

            // Clean up any partial downloads from interrupted runs
            cleanupPartFiles(cacheDir);

            // Ensure bundled fallback jar is extracted if cache is empty or older than bundled
            extractBundledIfNewer(cacheDir);

            Path bestLocal = findHighestVersionJar(cacheDir);
            String currentVer = bestLocal != null ? extractVersionFromFilename(bestLocal.getFileName().toString()) : "0.0.0";

            if (isAutoUpdateEnabled(configDir)) {
                Path updated = tryDownloadLatest(cacheDir, configDir, modsDir, currentVer);
                if (updated != null) {
                    String newVer = extractVersionFromFilename(updated.getFileName().toString());
                    System.setProperty("agyassistant.loader.updated", newVer);
                    bestLocal = updated;
                    pruneOldJars(cacheDir, updated);
                }
            } else {
                LOGGER.info("AGY Assistant auto-update is disabled in config.");
            }

            if (bestLocal != null && Files.isRegularFile(bestLocal)) {
                LOGGER.info("Loading AGY Assistant mod from {}", bestLocal.getFileName());
                pipeline.addPath(
                        bestLocal,
                        ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                        IncompatibleFileReporting.WARN_ALWAYS
                );
            } else {
                LOGGER.error("No AGY Assistant mod jar available in {} and download failed.", cacheDir);
            }
        } catch (Exception e) {
            LOGGER.error("AGY Assistant loader encountered an error during mod discovery", e);
        }
    }

    private boolean hasStandaloneModInModsDir(Path modsDir) {
        if (!Files.isDirectory(modsDir)) return false;
        try (Stream<Path> s = Files.list(modsDir)) {
            return s.anyMatch(p -> {
                if (!Files.isRegularFile(p)) return false;
                String name = p.getFileName().toString().toLowerCase();
                return name.startsWith("agyassistant-")
                        && name.endsWith(".jar")
                        && !name.startsWith("agyassistant-loader");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isAutoUpdateEnabled(Path configDir) {
        Path cfg = configDir.resolve("config.json");
        if (!Files.isRegularFile(cfg)) return true;
        try {
            String raw = Files.readString(cfg, StandardCharsets.UTF_8);
            return !AUTO_UPDATE_FALSE.matcher(raw).find();
        } catch (IOException e) {
            return true;
        }
    }

    private void cleanupPartFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".part"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    private void extractBundledIfNewer(Path cacheDir) {
        try {
            URI selfUri = AgyAutoUpdateLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path selfPath = Path.of(selfUri);
            if (!Files.isRegularFile(selfPath)) return;

            try (JarFile jf = new JarFile(selfPath.toFile())) {
                JarEntry bundled = jf.stream()
                        .filter(e -> !e.isDirectory()
                                && e.getName().startsWith("META-INF/agy-bundled/agyassistant-")
                                && e.getName().endsWith(".jar"))
                        .findFirst()
                        .orElse(null);
                if (bundled == null) return;

                String bundledName = Path.of(bundled.getName()).getFileName().toString();
                String bundledVer = extractVersionFromFilename(bundledName);

                Path bestLocal = findHighestVersionJar(cacheDir);
                String localVer = bestLocal != null ? extractVersionFromFilename(bestLocal.getFileName().toString()) : "0.0.0";

                if (bestLocal == null || compareVersions(bundledVer, localVer) > 0) {
                    Path target = cacheDir.resolve(bundledName);
                    Path part = cacheDir.resolve(bundledName + ".part");
                    try (InputStream in = jf.getInputStream(bundled)) {
                        Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Extracted bundled AGY Assistant {} to cache", bundledName);
                    pruneOldJars(cacheDir, target);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not extract bundled fallback jar: {}", e.getMessage());
        }
    }

    private Path tryDownloadLatest(Path cacheDir, Path configDir, Path modsDir, String currentVer) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(LATEST_API))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "agy-assistant-loader/" + currentVer)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOGGER.warn("GitHub release check returned HTTP {}", resp.statusCode());
                return null;
            }

            String body = resp.body();
            Matcher tagMatcher = TAG_PATTERN.matcher(body);
            if (!tagMatcher.find()) return null;

            String tag = tagMatcher.group(1).trim();
            String remoteVer = stripVersionPrefix(tag);
            if (compareVersions(remoteVer, currentVer) <= 0) {
                LOGGER.info("AGY Assistant is up to date (current={}, latest={})", currentVer, tag);
                return null;
            }

            List<AssetInfo> assets = parseAssets(body);
            AssetInfo modAsset = assets.stream()
                    .filter(a -> a.name.startsWith("agyassistant-")
                            && a.name.endsWith(".jar")
                            && !a.name.startsWith("agyassistant-loader")
                            && !a.name.endsWith("-sources.jar"))
                    .findFirst()
                    .orElse(null);

            if (modAsset == null) {
                LOGGER.warn("No mod jar asset found in GitHub release {}", tag);
                return null;
            }

            LOGGER.info("Downloading AGY Assistant {} from GitHub Releases...", tag);
            Path partFile = cacheDir.resolve(modAsset.name + ".part");
            Path targetFile = cacheDir.resolve(modAsset.name);
            downloadFile(http, modAsset.url, partFile, modAsset.size, currentVer);
            Files.move(partFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Downloaded AGY Assistant {} ({} bytes)", modAsset.name, Files.size(targetFile));

            // Also update agy-bridge.exe if present on Windows (before bridge starts)
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                Path bridgeTarget = findInstalledBridge(configDir, modsDir);
                if (bridgeTarget != null) {
                    assets.stream()
                            .filter(a -> a.name.equalsIgnoreCase("agy-bridge.exe"))
                            .findFirst()
                            .ifPresent(bAsset -> {
                                Path bPart = bridgeTarget.getParent().resolve("agy-bridge.exe.part");
                                try {
                                    downloadFile(http, bAsset.url, bPart, bAsset.size, currentVer);
                                    Files.move(bPart, bridgeTarget, StandardCopyOption.REPLACE_EXISTING);
                                    LOGGER.info("Updated agy-bridge.exe to {}", tag);
                                } catch (Exception ex) {
                                    try { Files.deleteIfExists(bPart); } catch (IOException ignored) {}
                                    LOGGER.warn("Failed to update agy-bridge.exe: {}", ex.getMessage());
                                }
                            });
                }
            }

            return targetFile;
        } catch (Exception e) {
            LOGGER.warn("Skipping GitHub update check (offline or error): {}", e.getMessage());
            return null;
        }
    }

    private Path findInstalledBridge(Path configDir, Path modsDir) {
        Path cfgExe = configDir.resolve("agy-bridge.exe");
        if (Files.isRegularFile(cfgExe)) return cfgExe;
        Path modsExe = modsDir.resolve("agy-bridge.exe");
        if (Files.isRegularFile(modsExe)) return modsExe;
        return null;
    }

    private void downloadFile(HttpClient http, String url, Path dest, long expectedSize, String userAgentVer)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "agy-assistant-loader/" + userAgentVer)
                .GET()
                .build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            Files.deleteIfExists(dest);
            throw new IOException("Download failed with HTTP " + resp.statusCode());
        }
        long actual = Files.size(dest);
        if (actual <= 0 || (expectedSize > 0 && actual != expectedSize)) {
            Files.deleteIfExists(dest);
            throw new IOException("Downloaded size mismatch: " + actual + " vs " + expectedSize);
        }
    }

    private record AssetInfo(String name, long size, String url) {}

    private List<AssetInfo> parseAssets(String json) {
        List<AssetInfo> result = new ArrayList<>();
        int assetsIdx = json.indexOf("\"assets\"");
        if (assetsIdx < 0) return result;
        String tail = json.substring(assetsIdx);

        Matcher urlMatcher = ASSET_URL_PATTERN.matcher(tail);
        int searchStart = 0;
        while (urlMatcher.find(searchStart)) {
            int urlPos = urlMatcher.start();
            String url = urlMatcher.group(1);
            // Look backwards from urlPos (up to 2500 chars) for the closest "name" and "size"
            int blockStart = Math.max(0, urlPos - 2500);
            String chunk = tail.substring(blockStart, urlPos);

            String name = findLastMatch(ASSET_NAME_PATTERN, chunk);
            String sizeStr = findLastMatch(ASSET_SIZE_PATTERN, chunk);
            long size = -1L;
            if (sizeStr != null) {
                try { size = Long.parseLong(sizeStr); } catch (NumberFormatException ignored) {}
            }
            if (name != null) {
                result.add(new AssetInfo(name, size, url));
            }
            searchStart = urlMatcher.end();
        }
        return result;
    }

    private String findLastMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private Path findHighestVersionJar(Path cacheDir) {
        try (Stream<Path> s = Files.list(cacheDir)) {
            return s.filter(p -> {
                        if (!Files.isRegularFile(p)) return false;
                        String n = p.getFileName().toString();
                        return n.startsWith("agyassistant-") && n.endsWith(".jar");
                    })
                    .max(Comparator.comparing(
                            p -> extractVersionFromFilename(p.getFileName().toString()),
                            AgyAutoUpdateLocator::compareVersions
                    ))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void pruneOldJars(Path cacheDir, Path keep) {
        try (Stream<Path> s = Files.list(cacheDir)) {
            s.filter(p -> !p.equals(keep)
                            && p.getFileName().toString().startsWith("agyassistant-")
                            && p.getFileName().toString().endsWith(".jar"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    private static String extractVersionFromFilename(String filename) {
        String base = filename;
        if (base.startsWith("agyassistant-")) {
            base = base.substring("agyassistant-".length());
        }
        if (base.endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        }
        return stripVersionPrefix(base);
    }

    private static String stripVersionPrefix(String v) {
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    static int compareVersions(String a, String b) {
        int[] pa = parseVersionParts(a);
        int[] pb = parseVersionParts(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int[] parseVersionParts(String v) {
        String clean = stripVersionPrefix(v);
        int dash = clean.indexOf('-');
        if (dash >= 0) clean = clean.substring(0, dash);
        int plus = clean.indexOf('+');
        if (plus >= 0) clean = clean.substring(0, plus);
        String[] segs = clean.split("\\.");
        int[] nums = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            StringBuilder digits = new StringBuilder();
            for (int j = 0; j < segs[i].length(); j++) {
                char c = segs[i].charAt(j);
                if (Character.isDigit(c)) digits.append(c);
                else break;
            }
            nums[i] = digits.length() > 0 ? Integer.parseInt(digits.toString()) : 0;
        }
        return nums;
    }
}
