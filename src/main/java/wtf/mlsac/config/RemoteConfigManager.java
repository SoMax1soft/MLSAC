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
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.config;

import wtf.mlsac.Main;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Keeps the managed settings in sync with the preset named in {@code remote-config.preset}.
 *
 * <p>Fetches shortly after start and then on a fixed interval, always off the main thread. A
 * failed fetch changes nothing, so a backend outage cannot leave the server unconfigured. A fetch
 * whose hash matches the applied one is dropped without touching any manager, so the common
 * "nothing changed" poll costs one HTTP request.
 */
public final class RemoteConfigManager {
    private static final long TICKS_PER_MINUTE = 1200L;
    /** Lets the server finish starting before the first fetch. */
    private static final long INITIAL_DELAY_TICKS = 40L;

    private final Main plugin;
    private final Logger logger;
    private final RemoteConfigClient client;
    private final AtomicBoolean fetchInFlight = new AtomicBoolean(false);

    private volatile ScheduledTask task;
    private volatile RemoteConfigClient.Snapshot appliedSnapshot;
    private volatile String appliedHash = "";
    private volatile boolean stopped;

    public RemoteConfigManager(Main plugin, Config config) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.client = new RemoteConfigClient(plugin.getLogger(), config.isDebug());
    }

    /** Snapshot currently backing {@link Main#getPluginConfig()}, or {@code null}. */
    public RemoteConfigClient.Snapshot getAppliedSnapshot() {
        return appliedSnapshot;
    }

    public void start() {
        Config config = plugin.getPluginConfig();
        if (!config.isRemoteConfigEnabled()) {
            logger.info("[RemoteConfig] No preset selected (remote-config.preset is empty)"
                    + " - using local configuration");
            return;
        }
        if (!config.isAiEnabled()) {
            logger.info("[RemoteConfig] Detection is disabled - skipping preset download");
            return;
        }

        long periodTicks = config.getRemoteRefreshMinutes() * TICKS_PER_MINUTE;
        logger.info("[RemoteConfig] Preset '" + config.getRemotePresetName() + "' will be refreshed every "
                + config.getRemoteRefreshMinutes() + " min");
        this.task = SchedulerManager.getAdapter().runAsyncRepeating(this::refresh,
                INITIAL_DELAY_TICKS, periodTicks);
    }

    public void stop() {
        stopped = true;
        ScheduledTask current = task;
        if (current != null) {
            current.cancel();
            task = null;
        }
    }

    /** Forces a fetch now, e.g. from {@code /mlsac reload}. Safe from any thread. */
    public void refreshNow() {
        SchedulerManager.getAdapter().runAsync(this::refresh);
    }

    private void refresh() {
        if (stopped) {
            return;
        }
        // Prevents polls piling up on the HTTP executor when the backend is slow.
        if (!fetchInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            Config config = plugin.getPluginConfig();
            if (config == null || !config.isRemoteConfigEnabled()) {
                return;
            }

            String apiKey = config.getAiApiKey();
            if (apiKey == null || apiKey.trim().isEmpty() || "your-api-key".equalsIgnoreCase(apiKey.trim())) {
                return;
            }

            RemoteConfigClient.Snapshot snapshot =
                    client.fetch(config.getServerAddress(), apiKey, config.getRemotePresetName());
            if (snapshot == null) {
                // Network or auth failure; keep what is already applied.
                return;
            }
            applyIfChanged(snapshot, config.getRemotePresetName());
        } catch (Exception exception) {
            logger.warning("[RemoteConfig] Refresh failed: " + exception.getMessage());
        } finally {
            fetchInFlight.set(false);
        }
    }

    private void applyIfChanged(RemoteConfigClient.Snapshot snapshot, String presetName) {
        String hash = snapshot.hasConfig() ? snapshot.getHash() : "";
        if (hash.equals(appliedHash)) {
            return;
        }

        appliedHash = hash;
        appliedSnapshot = snapshot.hasConfig() ? snapshot : null;

        if (snapshot.hasConfig()) {
            logger.info("[RemoteConfig] Applying preset '" + presetName + "' (" + hash + ")");
        } else {
            logger.info("[RemoteConfig] Preset '" + presetName + "' is unavailable"
                    + " (test mode off or preset deleted) - falling back to local configuration");
        }
        plugin.applyRemoteConfig(appliedSnapshot);
    }
}
