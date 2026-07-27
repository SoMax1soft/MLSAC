/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This file is based on GPLv3 licensed work and includes modifications.
 * Derived from:
 *   - Shard (© 2025 KaelusAI, https://github.com/KaelusAI/Shard)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 *   - Client-side project (GPLv3: https://github.com/MLSAC/client-side)
 *
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.config.Config;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class UpdateChecker {
    private static final long CHECK_INTERVAL_TICKS = 5L * 60L * 20L;
    private static final int CONNECT_TIMEOUT_MILLIS = 8000;
    private static final int READ_TIMEOUT_MILLIS = 45000;

    private final JavaPlugin plugin;
    private final Config config;
    private final String currentVersion;
    private final String apiBaseUrl;
    private final int javaBuild;
    private final AtomicBoolean checking = new AtomicBoolean(false);

    private ScheduledTask task;
    private volatile String latestVersion;
    private volatile boolean updateAvailable = false;

    public UpdateChecker(JavaPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
        this.currentVersion = plugin.getDescription().getVersion();
        this.apiBaseUrl = normalizeApiBaseUrl(config.getServerAddress());
        this.javaBuild = Runtime.version().feature() >= 21 ? 21 : 17;
    }

    public void start() {
        stop();
        if (!config.isUpdatesEnabled()) {
            plugin.getLogger().info("[Updater] Update notifications are disabled in config.yml");
            return;
        }

        if (!isHttps(apiBaseUrl)) {
            plugin.getLogger().warning("[Updater] Update checks require an https:// endpoint; current endpoint is "
                    + apiBaseUrl + ". Checks are disabled.");
            return;
        }

        checkForUpdates();
        task = SchedulerManager.getAdapter().runAsyncRepeating(this::checkForUpdates, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    private static boolean isHttps(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public CompletableFuture<Boolean> checkForUpdates() {
        if (!config.isUpdatesEnabled() || currentVersion.toLowerCase(Locale.ROOT).contains("dev")) {
            return CompletableFuture.completedFuture(false);
        }

        if (!checking.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(updateAvailable);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                UpdateInfo info = requestUpdateInfo();
                if (info == null) {
                    updateAvailable = false;
                    return false;
                }

                latestVersion = info.version;
                updateAvailable = info.updateAvailable;

                if (info.updateAvailable) {
                    plugin.getLogger().warning("[Updater] A new MLSAC update (" + info.version + ") is available!");
                }

                return updateAvailable;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[Updater] Failed to check for update: " + e.getMessage());
                return false;
            } finally {
                checking.set(false);
            }
        });
    }

    private UpdateInfo requestUpdateInfo() throws Exception {
        if (!isHttps(apiBaseUrl)) {
            return null;
        }
        String url = apiBaseUrl + "/plugin/update?java=" + javaBuild
                + "&version=" + URLEncoder.encode(currentVersion, StandardCharsets.UTF_8.name());
        HttpURLConnection connection = openConnection(url);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");

        if (connection.getResponseCode() != 200) {
            return null;
        }

        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                return null;
            }

            JsonObject data = root.getAsJsonObject("data");
            if (data == null) {
                return null;
            }

            UpdateInfo info = new UpdateInfo();
            info.version = getString(data, "version");
            info.updateAvailable = data.has("updateAvailable") && data.get("updateAvailable").getAsBoolean();
            return info;
        }
    }

    private HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "MLSAC-Plugin-Updater/" + currentVersion);
        return connection;
    }

    private static String normalizeApiBaseUrl(String value) {
        String result = value == null || value.trim().isEmpty()
                ? Config.DEFAULT_SERVER_ADDRESS
                : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (!result.endsWith("/api/v1")) {
            result = result + "/api/v1";
        }
        return result;
    }

    private static String getString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    private static final class UpdateInfo {
        private String version;
        private boolean updateAvailable;
    }
}
