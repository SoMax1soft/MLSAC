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

package wtf.mlsac.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.logging.Logger;

public class Config {
    private final boolean debug;
    private final String language;
    private final String outputDirectory;
    private final boolean aiEnabled;
    private final String aiApiKey;
    private final double aiAlertThreshold;
    private final boolean aiConsoleAlerts;
    private final boolean alertSoundEnabled;
    private final String alertSoundType;
    private final float alertSoundVolume;
    private final float alertSoundPitch;
    private final double aiBufferFlag;
    private final double aiBufferResetOnFlag;
    private final double aiBufferMultiplier;
    private final double aiBufferDecrease;
    private final double aiBufferDecreaseThreshold;
    private final int aiSequence;
    private final int aiStep;
    private final Map<Integer, String> punishmentCommands;
    private final boolean animationEnabled;
    private final String animationType;

    private final boolean liteBansEnabled;
    private final String liteBansDbHost;
    private final int liteBansDbPort;
    private final String liteBansDbName;
    private final String liteBansDbUsername;
    private final String liteBansDbPassword;
    private final String liteBansTablePrefix;
    private final int liteBansLookbackDays;
    private final Set<String> liteBansCheatReasons;
    private final boolean autostartEnabled;
    private final String autostartLabel;
    private final String autostartComment;
    private final String serverAddress;
    private final String reserveServerAddress;
    private final boolean safeNameCheck;
    private final String serverIdentityName;
    private final String serverIdentityFamily;
    private final boolean interServerEnabled;
    private final boolean crossReportsEnabled;
    private final boolean apiEventReportingEnabled;
    private final double apiAlertEventThreshold;
    private final boolean updatesEnabled;
    private final boolean visionEnabled;
    private final boolean reportsEnabled;
    private final boolean menusEnabled;
    private final int reportCooldownSeconds;
    private final int visionRegionScanIntervalTicks;
    private final boolean vlDecayEnabled;
    private final int vlDecayIntervalSeconds;
    private final int vlDecayAmount;
    private final boolean worldGuardEnabled;
    private final List<String> worldGuardDisabledRegions;
    private final boolean foliaEnabled;
    private final int foliaThreadPoolSize;
    private final boolean foliaEntitySchedulerEnabled;
    private final boolean foliaRegionSchedulerEnabled;
    private final Map<String, String> modelNames;
    private final Map<String, Boolean> modelOnlyAlert;
    private final Map<String, Boolean> modelEnabled;
    private final boolean alertResponsesEnabled;
    private final double aiAlertBufferStepPercent;
    private final boolean damageReductionEnabled;
    private final List<DamageReductionStage> damageReductionStages;
    private final boolean trollEnabled;
    private final List<TrollActionConfig> trollActions;
    private final String remotePresetName;
    private final int remoteRefreshMinutes;
    private final boolean remotePresetApplied;
    private final boolean antiEspEnabled;
    private final double antiEspMaxDistance;
    private final double antiEspProximityDistance;
    private final int antiEspHideDelayTicks;
    private final int antiEspUpdateIntervalTicks;
    private final int antiEspBudgetMicros;
    private final int antiEspCacheTicks;
    private final double antiEspMoveThreshold;
    private final int antiEspRayCount;
    private final boolean antiEspFovEnabled;
    private final double antiEspFovDegrees;
    private final boolean antiEspRestoreMetadata;
    private final boolean antiEspHideSounds;
    private final boolean antiEspHideGlowing;
    private final boolean antiEspVerboseDebug;

    public static final boolean DEFAULT_ANTI_ESP_ENABLED = true;
    public static final double DEFAULT_ANTI_ESP_MAX_DISTANCE = 48.0;
    public static final double DEFAULT_ANTI_ESP_PROXIMITY_DISTANCE = 3.0;
    public static final int DEFAULT_ANTI_ESP_HIDE_DELAY_TICKS = 20;
    public static final int DEFAULT_ANTI_ESP_UPDATE_INTERVAL_TICKS = 2;
    public static final int DEFAULT_ANTI_ESP_BUDGET_MICROS = 2500;
    public static final int DEFAULT_ANTI_ESP_CACHE_TICKS = 10;
    public static final double DEFAULT_ANTI_ESP_MOVE_THRESHOLD = 0.30;
    public static final int DEFAULT_ANTI_ESP_RAY_COUNT = 5;
    public static final boolean DEFAULT_ANTI_ESP_FOV_ENABLED = false;
    public static final double DEFAULT_ANTI_ESP_FOV_DEGREES = 140.0;
    public static final boolean DEFAULT_ANTI_ESP_RESTORE_METADATA = true;
    public static final boolean DEFAULT_ANTI_ESP_HIDE_SOUNDS = true;
    public static final boolean DEFAULT_ANTI_ESP_HIDE_GLOWING = true;
    public static final boolean DEFAULT_ANTI_ESP_VERBOSE_DEBUG = false;

    public static final boolean DEFAULT_DEBUG = false;
    public static final String DEFAULT_OUTPUT_DIRECTORY = "plugins/MLSAC/data";
    public static final boolean DEFAULT_AI_ENABLED = false;
    public static final String DEFAULT_AI_API_KEY = "";
    public static final double DEFAULT_AI_ALERT_THRESHOLD = 0.5;
    public static final boolean DEFAULT_AI_CONSOLE_ALERTS = true;
    public static final boolean DEFAULT_ALERT_SOUND_ENABLED = true;
    public static final String DEFAULT_ALERT_SOUND_TYPE = "BLOCK_NOTE_BLOCK_PLING";
    public static final float DEFAULT_ALERT_SOUND_VOLUME = 1.0f;
    public static final float DEFAULT_ALERT_SOUND_PITCH = 1.0f;
    public static final double DEFAULT_AI_BUFFER_FLAG = 50.0;
    public static final double DEFAULT_AI_BUFFER_RESET_ON_FLAG = 25.0;
    public static final double DEFAULT_AI_BUFFER_MULTIPLIER = 100.0;
    public static final double DEFAULT_AI_BUFFER_DECREASE = 0.25;
    public static final double DEFAULT_AI_BUFFER_DECREASE_THRESHOLD = 0.10;
    public static final boolean DEFAULT_ANIMATION_ENABLED = true;
    public static final String DEFAULT_ANIMATION_TYPE = "classic_ban";
    public static final int DEFAULT_AI_SEQUENCE = 40;
    public static final int DEFAULT_AI_STEP = 10;

    public static final boolean DEFAULT_LITEBANS_ENABLED = false;
    public static final String DEFAULT_LITEBANS_DB_HOST = "localhost";
    public static final int DEFAULT_LITEBANS_DB_PORT = 3306;
    public static final String DEFAULT_LITEBANS_DB_NAME = "litebans";
    public static final String DEFAULT_LITEBANS_DB_USERNAME = "";
    public static final String DEFAULT_LITEBANS_DB_PASSWORD = "";
    public static final String DEFAULT_LITEBANS_TABLE_PREFIX = "litebans_";
    public static final int DEFAULT_LITEBANS_LOOKBACK_DAYS = 7;
    public static final boolean DEFAULT_AUTOSTART_ENABLED = false;
    public static final String DEFAULT_AUTOSTART_LABEL = "UNLABELED";
    public static final String DEFAULT_AUTOSTART_COMMENT = "";
    public static final String DEFAULT_SERVER_ADDRESS = "https://api.mlsac.net/api/v1";
    public static final String DEFAULT_RESERVE_SERVER_ADDRESS = "https://ruapi.mlsac.net";
    public static final boolean DEFAULT_SAFE_NAME_CHECK = true;
    public static final String DEFAULT_SERVER_IDENTITY_NAME = "default";
    public static final String DEFAULT_SERVER_IDENTITY_FAMILY = "default";
    public static final boolean DEFAULT_INTERSERVER_ENABLED = false;
    public static final boolean DEFAULT_CROSS_REPORTS_ENABLED = false;
    public static final boolean DEFAULT_API_EVENT_REPORTING_ENABLED = true;
    public static final double DEFAULT_API_ALERT_EVENT_THRESHOLD = 0.75;
    public static final boolean DEFAULT_UPDATES_ENABLED = true;
    /** Region roster sweep period. 0 disables it; the default is every 5 minutes. */
    public static final int DEFAULT_VISION_REGION_SCAN_MINUTES = 5;
    public static final boolean DEFAULT_REPORTS_ENABLED = true;
    public static final boolean DEFAULT_MENUS_ENABLED = true;
    public static final int DEFAULT_REPORT_COOLDOWN_SECONDS = 30;
    public static final boolean DEFAULT_VISION_ENABLED = false;
    public static final boolean DEFAULT_VL_DECAY_ENABLED = true;
    public static final int DEFAULT_VL_DECAY_INTERVAL_SECONDS = 60;
    public static final int DEFAULT_VL_DECAY_AMOUNT = 1;
    public static final boolean DEFAULT_WORLDGUARD_ENABLED = true;
    public static final List<String> DEFAULT_WORLDGUARD_DISABLED_REGIONS = new ArrayList<>();
    public static final boolean DEFAULT_FOLIA_ENABLED = true;
    public static final int DEFAULT_FOLIA_THREAD_POOL_SIZE = 0;
    public static final boolean DEFAULT_FOLIA_ENTITY_SCHEDULER_ENABLED = true;
    public static final boolean DEFAULT_FOLIA_REGION_SCHEDULER_ENABLED = true;
    public static final boolean DEFAULT_ALERT_RESPONSES_ENABLED = true;
    public static final double DEFAULT_AI_ALERT_BUFFER_STEP_PERCENT = 0.33;
    public static final String DEFAULT_REMOTE_PRESET = "";
    public static final int DEFAULT_REMOTE_REFRESH_MINUTES = 5;
    public static final int MIN_REMOTE_REFRESH_MINUTES = 1;
    public static final int MAX_REMOTE_REFRESH_MINUTES = 1440;
    /** Same character set the API enforces on preset names. */
    private static final java.util.regex.Pattern PRESET_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    public Config() {
        this.debug = DEFAULT_DEBUG;
        this.language = "ru";
        this.outputDirectory = DEFAULT_OUTPUT_DIRECTORY;
        this.aiEnabled = DEFAULT_AI_ENABLED;
        this.aiApiKey = DEFAULT_AI_API_KEY;
        this.aiAlertThreshold = DEFAULT_AI_ALERT_THRESHOLD;
        this.aiConsoleAlerts = DEFAULT_AI_CONSOLE_ALERTS;
        this.alertSoundEnabled = DEFAULT_ALERT_SOUND_ENABLED;
        this.alertSoundType = DEFAULT_ALERT_SOUND_TYPE;
        this.alertSoundVolume = DEFAULT_ALERT_SOUND_VOLUME;
        this.alertSoundPitch = DEFAULT_ALERT_SOUND_PITCH;
        this.aiBufferFlag = DEFAULT_AI_BUFFER_FLAG;
        this.aiBufferResetOnFlag = DEFAULT_AI_BUFFER_RESET_ON_FLAG;
        this.aiBufferMultiplier = DEFAULT_AI_BUFFER_MULTIPLIER;
        this.aiBufferDecrease = DEFAULT_AI_BUFFER_DECREASE;
        this.aiBufferDecreaseThreshold = DEFAULT_AI_BUFFER_DECREASE_THRESHOLD;
        this.aiSequence = DEFAULT_AI_SEQUENCE;
        this.aiStep = DEFAULT_AI_STEP;
        this.punishmentCommands = new HashMap<>();
        this.animationEnabled = DEFAULT_ANIMATION_ENABLED;
        this.animationType = DEFAULT_ANIMATION_TYPE;

        this.liteBansEnabled = DEFAULT_LITEBANS_ENABLED;
        this.liteBansDbHost = DEFAULT_LITEBANS_DB_HOST;
        this.liteBansDbPort = DEFAULT_LITEBANS_DB_PORT;
        this.liteBansDbName = DEFAULT_LITEBANS_DB_NAME;
        this.liteBansDbUsername = DEFAULT_LITEBANS_DB_USERNAME;
        this.liteBansDbPassword = DEFAULT_LITEBANS_DB_PASSWORD;
        this.liteBansTablePrefix = DEFAULT_LITEBANS_TABLE_PREFIX;
        this.liteBansLookbackDays = DEFAULT_LITEBANS_LOOKBACK_DAYS;
        this.liteBansCheatReasons = createDefaultCheatReasons();
        this.autostartEnabled = DEFAULT_AUTOSTART_ENABLED;
        this.autostartLabel = DEFAULT_AUTOSTART_LABEL;
        this.autostartComment = DEFAULT_AUTOSTART_COMMENT;
        this.serverAddress = DEFAULT_SERVER_ADDRESS;
        this.reserveServerAddress = DEFAULT_RESERVE_SERVER_ADDRESS;
        this.safeNameCheck = DEFAULT_SAFE_NAME_CHECK;
        this.serverIdentityName = DEFAULT_SERVER_IDENTITY_NAME;
        this.serverIdentityFamily = DEFAULT_SERVER_IDENTITY_FAMILY;
        this.interServerEnabled = DEFAULT_INTERSERVER_ENABLED;
        this.crossReportsEnabled = DEFAULT_CROSS_REPORTS_ENABLED;
        this.apiEventReportingEnabled = DEFAULT_API_EVENT_REPORTING_ENABLED;
        this.apiAlertEventThreshold = DEFAULT_API_ALERT_EVENT_THRESHOLD;
        this.updatesEnabled = DEFAULT_UPDATES_ENABLED;
        this.visionEnabled = DEFAULT_VISION_ENABLED;
        this.reportsEnabled = DEFAULT_REPORTS_ENABLED;
        this.menusEnabled = DEFAULT_MENUS_ENABLED;
        this.reportCooldownSeconds = DEFAULT_REPORT_COOLDOWN_SECONDS;
        this.visionRegionScanIntervalTicks = DEFAULT_VISION_REGION_SCAN_MINUTES * 60 * 20;
        this.vlDecayEnabled = DEFAULT_VL_DECAY_ENABLED;
        this.vlDecayIntervalSeconds = DEFAULT_VL_DECAY_INTERVAL_SECONDS;
        this.vlDecayAmount = DEFAULT_VL_DECAY_AMOUNT;
        this.worldGuardEnabled = DEFAULT_WORLDGUARD_ENABLED;
        this.worldGuardDisabledRegions = new ArrayList<>(DEFAULT_WORLDGUARD_DISABLED_REGIONS);
        this.foliaEnabled = DEFAULT_FOLIA_ENABLED;
        this.foliaThreadPoolSize = DEFAULT_FOLIA_THREAD_POOL_SIZE;
        this.foliaEntitySchedulerEnabled = DEFAULT_FOLIA_ENTITY_SCHEDULER_ENABLED;
        this.foliaRegionSchedulerEnabled = DEFAULT_FOLIA_REGION_SCHEDULER_ENABLED;
        this.modelNames = new HashMap<>();
        this.modelOnlyAlert = new HashMap<>();
        this.modelEnabled = new HashMap<>();
        this.alertResponsesEnabled = DEFAULT_ALERT_RESPONSES_ENABLED;
        this.aiAlertBufferStepPercent = DEFAULT_AI_ALERT_BUFFER_STEP_PERCENT;
        this.damageReductionEnabled = true;
        this.damageReductionStages = createDefaultDamageReductionStages();
        this.trollEnabled = true;
        this.trollActions = createDefaultTrollActions();
        this.remotePresetName = DEFAULT_REMOTE_PRESET;
        this.remoteRefreshMinutes = DEFAULT_REMOTE_REFRESH_MINUTES;
        this.remotePresetApplied = false;
        this.antiEspEnabled = DEFAULT_ANTI_ESP_ENABLED;
        this.antiEspMaxDistance = DEFAULT_ANTI_ESP_MAX_DISTANCE;
        this.antiEspProximityDistance = DEFAULT_ANTI_ESP_PROXIMITY_DISTANCE;
        this.antiEspHideDelayTicks = DEFAULT_ANTI_ESP_HIDE_DELAY_TICKS;
        this.antiEspUpdateIntervalTicks = DEFAULT_ANTI_ESP_UPDATE_INTERVAL_TICKS;
        this.antiEspBudgetMicros = DEFAULT_ANTI_ESP_BUDGET_MICROS;
        this.antiEspCacheTicks = DEFAULT_ANTI_ESP_CACHE_TICKS;
        this.antiEspMoveThreshold = DEFAULT_ANTI_ESP_MOVE_THRESHOLD;
        this.antiEspRayCount = DEFAULT_ANTI_ESP_RAY_COUNT;
        this.antiEspFovEnabled = DEFAULT_ANTI_ESP_FOV_ENABLED;
        this.antiEspFovDegrees = DEFAULT_ANTI_ESP_FOV_DEGREES;
        this.antiEspRestoreMetadata = DEFAULT_ANTI_ESP_RESTORE_METADATA;
        this.antiEspHideSounds = DEFAULT_ANTI_ESP_HIDE_SOUNDS;
        this.antiEspHideGlowing = DEFAULT_ANTI_ESP_HIDE_GLOWING;
        this.antiEspVerboseDebug = DEFAULT_ANTI_ESP_VERBOSE_DEBUG;
    }

    /**
     * Read from the operator's own file only: the preset name decides which remote config to load,
     * so a remote value here would let one preset redirect the server to another.
     */
    private static String readPresetName(FileConfiguration localConfig, Logger logger) {
        String value = localConfig.getString("remote-config.preset", DEFAULT_REMOTE_PRESET);
        if (value == null) {
            return DEFAULT_REMOTE_PRESET;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_REMOTE_PRESET;
        }
        if (!PRESET_NAME_PATTERN.matcher(trimmed).matches()) {
            if (logger != null) {
                logger.warning("[Config] remote-config.preset '" + trimmed + "' is not a valid preset name"
                        + " (1-32 chars of A-Z, a-z, 0-9, _ or -) - running on local configuration");
            }
            return DEFAULT_REMOTE_PRESET;
        }
        return trimmed;
    }

    private static int clampRefreshMinutes(int minutes) {
        return Math.max(MIN_REMOTE_REFRESH_MINUTES, Math.min(MAX_REMOTE_REFRESH_MINUTES, minutes));
    }

    private static Set<String> createDefaultCheatReasons() {
        Set<String> reasons = new HashSet<>();
        reasons.add("killaura");
        reasons.add("cheat");
        reasons.add("hack");
        return reasons;
    }

    public Config(JavaPlugin plugin) {
        this(plugin, null, null);
    }

    public Config(JavaPlugin plugin, Logger logger) {
        this(plugin, logger, null);
    }

    /**
     * @param remoteSnapshot preset from the MLSAC API, or {@code null} to run on the bundled
     *                       defaults plus local config.yml. See {@link ConfigLayers}.
     */
    public Config(JavaPlugin plugin, Logger logger, RemoteConfigClient.Snapshot remoteSnapshot) {
        plugin.saveDefaultConfig();
        // Creates modules.yml and modules/*.yml on first run, and moves a pre-split config.yml
        // over to them. Has to happen before the layers are stacked, since it writes the files
        // those layers read.
        ModuleLayout.install(plugin);
        // Not plugin.getConfig(): managed sections live in the jar's managed-defaults.yml and may
        // be overridden by the API preset.
        FileConfiguration config = ConfigLayers.build(plugin, remoteSnapshot);

        this.remotePresetName = readPresetName(plugin.getConfig(), logger);
        this.remoteRefreshMinutes = clampRefreshMinutes(
                plugin.getConfig().getInt("remote-config.refresh-minutes", DEFAULT_REMOTE_REFRESH_MINUTES));
        this.remotePresetApplied = remoteSnapshot != null && remoteSnapshot.hasConfig();

        this.debug = config.getBoolean("debug", DEFAULT_DEBUG);
        this.language = config.getString("language", "ru").trim().toLowerCase(Locale.ROOT);
        this.outputDirectory = config.getString("outputDirectory", DEFAULT_OUTPUT_DIRECTORY);
        this.aiEnabled = config.getBoolean("detection.enabled",
                config.getBoolean("ai.enabled", DEFAULT_AI_ENABLED));
        this.aiApiKey = config.getString("detection.api-key",
                config.getString("ai.api-key", DEFAULT_AI_API_KEY));
        double alertThreshold = config.getDouble("alerts.threshold",
                config.getDouble("ai.alert.threshold", DEFAULT_AI_ALERT_THRESHOLD));
        this.aiAlertThreshold = clampThreshold(alertThreshold, "alerts.threshold", logger);
        this.aiConsoleAlerts = config.getBoolean("alerts.console",
                config.getBoolean("ai.alert.console", DEFAULT_AI_CONSOLE_ALERTS));
        this.alertSoundEnabled = config.getBoolean("alerts.sound.enabled", DEFAULT_ALERT_SOUND_ENABLED);
        this.alertSoundType = config.getString("alerts.sound.type", DEFAULT_ALERT_SOUND_TYPE);
        this.alertSoundVolume = (float) config.getDouble("alerts.sound.volume", DEFAULT_ALERT_SOUND_VOLUME);
        this.alertSoundPitch = (float) config.getDouble("alerts.sound.pitch", DEFAULT_ALERT_SOUND_PITCH);
        this.aiBufferFlag = config.getDouble("violation.threshold",
                config.getDouble("ai.buffer.flag", DEFAULT_AI_BUFFER_FLAG));
        this.aiBufferResetOnFlag = config.getDouble("violation.reset-value",
                config.getDouble("ai.buffer.reset-on-flag", DEFAULT_AI_BUFFER_RESET_ON_FLAG));
        this.aiBufferMultiplier = config.getDouble("violation.multiplier",
                config.getDouble("ai.buffer.multiplier", DEFAULT_AI_BUFFER_MULTIPLIER));
        this.aiBufferDecreaseThreshold = config.getDouble("violation.decay.threshold", DEFAULT_AI_BUFFER_DECREASE_THRESHOLD);
        if (config.isConfigurationSection("violation.decay")) {
            // New layout: violation.decay.{threshold,amount}
            this.aiBufferDecrease = config.getDouble("violation.decay.amount", DEFAULT_AI_BUFFER_DECREASE);
        } else {
            // Legacy layout: violation.decay was a scalar (with an even older ai.buffer.decrease alias)
            this.aiBufferDecrease = config.getDouble("violation.decay",
                    config.getDouble("ai.buffer.decrease", DEFAULT_AI_BUFFER_DECREASE));
        }
        this.aiSequence = DEFAULT_AI_SEQUENCE; // Hardcoded: 40
        this.aiStep = DEFAULT_AI_STEP;         // Hardcoded: 10
        this.animationEnabled = config.getBoolean("penalties.animation.enabled", DEFAULT_ANIMATION_ENABLED);
        this.animationType = config.getString("penalties.animation.type", DEFAULT_ANIMATION_TYPE);
        this.punishmentCommands = new HashMap<>();
        ConfigurationSection cmdSection = config.getConfigurationSection("penalties.actions");
        if (cmdSection == null) {
            cmdSection = config.getConfigurationSection("ai.punishment.commands");
        }
        if (cmdSection != null) {
            for (String key : cmdSection.getKeys(false)) {
                try {
                    int vl = Integer.parseInt(key);
                    String cmd = cmdSection.getString(key);
                    if (cmd != null && !cmd.isEmpty()) {
                        // Migrate legacy {BAN} and {KICK} to {ANIMATION}
                        cmd = migrateLegacyPrefixes(cmd);
                        punishmentCommands.put(vl, cmd);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        this.liteBansEnabled = config.getBoolean("litebans.enabled", DEFAULT_LITEBANS_ENABLED);
        this.liteBansDbHost = config.getString("litebans.database.host", DEFAULT_LITEBANS_DB_HOST);
        this.liteBansDbPort = config.getInt("litebans.database.port", DEFAULT_LITEBANS_DB_PORT);
        this.liteBansDbName = config.getString("litebans.database.name", DEFAULT_LITEBANS_DB_NAME);
        this.liteBansDbUsername = config.getString("litebans.database.username", DEFAULT_LITEBANS_DB_USERNAME);
        this.liteBansDbPassword = config.getString("litebans.database.password", DEFAULT_LITEBANS_DB_PASSWORD);
        this.liteBansTablePrefix = config.getString("litebans.table-prefix", DEFAULT_LITEBANS_TABLE_PREFIX);
        this.liteBansLookbackDays = config.getInt("litebans.lookback-days", DEFAULT_LITEBANS_LOOKBACK_DAYS);
        this.liteBansCheatReasons = new HashSet<>();

        List<String> reasonsList = config.getStringList("litebans.cheat-reasons");
        if (reasonsList.isEmpty()) {
            this.liteBansCheatReasons.addAll(createDefaultCheatReasons());
        } else {
            this.liteBansCheatReasons.addAll(reasonsList);
        }

        this.autostartEnabled = config.getBoolean("autostart.enabled", DEFAULT_AUTOSTART_ENABLED);
        this.autostartLabel = config.getString("autostart.label", DEFAULT_AUTOSTART_LABEL);
        this.autostartComment = config.getString("autostart.comment", DEFAULT_AUTOSTART_COMMENT);
        this.serverAddress = config.getString("detection.endpoint",
                config.getString("ai.server", DEFAULT_SERVER_ADDRESS));
        this.reserveServerAddress = config.getString("detection.reserve-endpoint",
                DEFAULT_RESERVE_SERVER_ADDRESS);
        this.safeNameCheck = config.getBoolean("punishments.safe-name-check", DEFAULT_SAFE_NAME_CHECK);
        this.serverIdentityName = config.getString("server-identity.name", DEFAULT_SERVER_IDENTITY_NAME);
        this.serverIdentityFamily = config.getString("server-identity.family", DEFAULT_SERVER_IDENTITY_FAMILY);
        this.interServerEnabled = config.getBoolean("server-identity.interserver.enabled",
                DEFAULT_INTERSERVER_ENABLED);
        this.crossReportsEnabled = config.getBoolean("server-identity.interserver.cross-reports",
                DEFAULT_CROSS_REPORTS_ENABLED);
        this.apiEventReportingEnabled = config.getBoolean("server-identity.reporting.events-enabled",
                DEFAULT_API_EVENT_REPORTING_ENABLED);
        double apiAlertThreshold = config.getDouble("server-identity.reporting.alert-threshold",
                DEFAULT_API_ALERT_EVENT_THRESHOLD);
        this.apiAlertEventThreshold = clampThreshold(apiAlertThreshold,
                "server-identity.reporting.alert-threshold", logger);
        this.updatesEnabled = config.getBoolean("updates.enabled", DEFAULT_UPDATES_ENABLED);
        this.visionEnabled = config.getBoolean("vision.enabled", DEFAULT_VISION_ENABLED);
        this.reportsEnabled = config.getBoolean("reports.enabled", DEFAULT_REPORTS_ENABLED);
        this.menusEnabled = config.getBoolean("menus.enabled", DEFAULT_MENUS_ENABLED);
        this.reportCooldownSeconds = Math.max(0,
                config.getInt("reports.cooldown-seconds", DEFAULT_REPORT_COOLDOWN_SECONDS));
        this.visionRegionScanIntervalTicks = Math.max(0,
                config.getInt("vision.region-scan-minutes", DEFAULT_VISION_REGION_SCAN_MINUTES)) * 60 * 20;
        this.vlDecayEnabled = config.getBoolean("violation.vl-decay.enabled", DEFAULT_VL_DECAY_ENABLED);
        this.vlDecayIntervalSeconds = config.getInt("violation.vl-decay.interval", DEFAULT_VL_DECAY_INTERVAL_SECONDS);
        this.vlDecayAmount = config.getInt("violation.vl-decay.amount", DEFAULT_VL_DECAY_AMOUNT);
        this.worldGuardEnabled = config.getBoolean("detection.worldguard.enabled", DEFAULT_WORLDGUARD_ENABLED);
        this.worldGuardDisabledRegions = config.getStringList("detection.worldguard.disabled-regions");
        this.foliaEnabled = config.getBoolean("folia.enabled", DEFAULT_FOLIA_ENABLED);
        this.foliaThreadPoolSize = config.getInt("folia.thread-pool-size", DEFAULT_FOLIA_THREAD_POOL_SIZE);
        this.foliaEntitySchedulerEnabled = config.getBoolean("folia.entity-scheduler.enabled",
                DEFAULT_FOLIA_ENTITY_SCHEDULER_ENABLED);
        this.foliaRegionSchedulerEnabled = config.getBoolean("folia.region-scheduler.enabled",
                DEFAULT_FOLIA_REGION_SCHEDULER_ENABLED);

        this.antiEspEnabled = config.getBoolean("anti_esp.enabled", DEFAULT_ANTI_ESP_ENABLED);
        this.antiEspMaxDistance = config.getDouble("anti_esp.max_distance", DEFAULT_ANTI_ESP_MAX_DISTANCE);
        this.antiEspProximityDistance = config.getDouble("anti_esp.proximity_distance", DEFAULT_ANTI_ESP_PROXIMITY_DISTANCE);
        this.antiEspHideDelayTicks = config.getInt("anti_esp.hide_delay_ticks", DEFAULT_ANTI_ESP_HIDE_DELAY_TICKS);
        this.antiEspUpdateIntervalTicks = config.getInt("anti_esp.update_interval_ticks", DEFAULT_ANTI_ESP_UPDATE_INTERVAL_TICKS);
        this.antiEspBudgetMicros = Math.max(100,
                config.getInt("anti_esp.budget_micros_per_pass", DEFAULT_ANTI_ESP_BUDGET_MICROS));
        this.antiEspCacheTicks = Math.max(0, config.getInt("anti_esp.cache_ticks", DEFAULT_ANTI_ESP_CACHE_TICKS));
        this.antiEspMoveThreshold = Math.max(0.0,
                config.getDouble("anti_esp.move_threshold", DEFAULT_ANTI_ESP_MOVE_THRESHOLD));
        this.antiEspRayCount = Math.max(1, Math.min(9,
                config.getInt("anti_esp.ray_count", DEFAULT_ANTI_ESP_RAY_COUNT)));
        this.antiEspFovEnabled = config.getBoolean("anti_esp.hide_outside_fov", DEFAULT_ANTI_ESP_FOV_ENABLED);
        this.antiEspFovDegrees = config.getDouble("anti_esp.fov_degrees", DEFAULT_ANTI_ESP_FOV_DEGREES);
        this.antiEspRestoreMetadata = config.getBoolean("anti_esp.restore_metadata", DEFAULT_ANTI_ESP_RESTORE_METADATA);
        this.antiEspHideSounds = config.getBoolean("anti_esp.hide_sounds", DEFAULT_ANTI_ESP_HIDE_SOUNDS);
        this.antiEspHideGlowing = config.getBoolean("anti_esp.hide_glowing", DEFAULT_ANTI_ESP_HIDE_GLOWING);
        this.antiEspVerboseDebug = config.getBoolean("anti_esp.verbose_debug", DEFAULT_ANTI_ESP_VERBOSE_DEBUG);

        this.modelNames = new HashMap<>();
        this.modelOnlyAlert = new HashMap<>();
        this.modelEnabled = new HashMap<>();
        ConfigurationSection modelsSection = config.getConfigurationSection("detection.models");
        if (modelsSection != null) {
            for (String modelKey : modelsSection.getKeys(false)) {
                ConfigurationSection modelSection = modelsSection.getConfigurationSection(modelKey);
                if (modelSection != null) {
                    String displayName = modelSection.getString("name", modelKey);
                    boolean onlyAlertForModel = modelSection.getBoolean("only-alert", false);
                    // Absent means enabled, so configs predating the switch keep every model
                    // running.
                    modelEnabled.put(modelKey, modelSection.getBoolean("enabled", true));

                    // Alert-only on the stable models would disable every punishment, so it is
                    // forced off whichever layer it came from.
                    if (onlyAlertForModel && ("pro".equalsIgnoreCase(modelKey) || "fast".equalsIgnoreCase(modelKey))) {
                        onlyAlertForModel = false;
                        if (logger != null) {
                            logger.info("[Config] Ignoring 'only-alert' for model " + modelKey
                                    + " - punishments stay enabled for the stable models");
                        }
                    }

                    modelNames.put(modelKey, displayName);
                    modelOnlyAlert.put(modelKey, onlyAlertForModel);
                } else {
                    String displayName = modelsSection.getString(modelKey);
                    if (displayName != null && !displayName.isEmpty()) {
                        modelNames.put(modelKey, displayName);
                        modelOnlyAlert.put(modelKey, false);
                        modelEnabled.put(modelKey, true);
                    }
                }
            }
        }


        this.alertResponsesEnabled = config.getBoolean("alert-responses.enabled", DEFAULT_ALERT_RESPONSES_ENABLED);
        double stepPercent = config.getDouble("alert-responses.alerts.buffer-step-percent",
                DEFAULT_AI_ALERT_BUFFER_STEP_PERCENT);
        if (stepPercent <= 0.0 || stepPercent > 1.0) {
            if (logger != null) {
                logger.warning("[Config] alert-responses.alerts.buffer-step-percent value "
                        + stepPercent + " is outside (0, 1], clamped to "
                        + DEFAULT_AI_ALERT_BUFFER_STEP_PERCENT);
            }
            stepPercent = DEFAULT_AI_ALERT_BUFFER_STEP_PERCENT;
        }
        this.aiAlertBufferStepPercent = stepPercent;
        this.damageReductionEnabled = config.getBoolean("alert-responses.damage-reduction.enabled", true);
        this.damageReductionStages = loadDamageReductionStages(config, logger);
        this.trollEnabled = config.getBoolean("alert-responses.troll.enabled", true);
        this.trollActions = loadTrollActions(config, logger);
    }

    private static List<DamageReductionStage> createDefaultDamageReductionStages() {
        List<DamageReductionStage> stages = new ArrayList<>();
        stages.add(new DamageReductionStage(15.0, 15.0, 8));
        stages.add(new DamageReductionStage(30.0, 35.0, 12));
        stages.add(new DamageReductionStage(45.0, 55.0, 16));
        return Collections.unmodifiableList(stages);
    }

    private static List<TrollActionConfig> createDefaultTrollActions() {
        List<TrollActionConfig> actions = new ArrayList<>();
        actions.add(new TrollActionConfig("shuffle_inventory", 20.0, 20, true, 1.4, 0.45,
                "&cMLSAC shuffled {PLAYER}'s inventory at buffer {BUFFER}."));
        actions.add(new TrollActionConfig("drop_weapon", 35.0, 20, true, 1.9, 0.55,
                "&cMLSAC launched {PLAYER}'s weapon at buffer {BUFFER}."));
        return Collections.unmodifiableList(actions);
    }

    private List<DamageReductionStage> loadDamageReductionStages(FileConfiguration config, Logger logger) {
        List<Map<?, ?>> rawStages = config.getMapList("alert-responses.damage-reduction.stages");
        List<DamageReductionStage> stages = new ArrayList<>();
        for (Map<?, ?> rawStage : rawStages) {
            double bufferThreshold = getDouble(rawStage.get("buffer"), 0.0);
            double reductionPercent = getDouble(rawStage.get("reduction-percent"), 0.0);
            int durationSeconds = getInt(rawStage.get("duration-seconds"), 0);
            if (bufferThreshold <= 0.0 || durationSeconds <= 0 || reductionPercent <= 0.0) {
                if (logger != null) {
                    logger.warning("[Config] Skipping invalid damage reduction stage: " + rawStage);
                }
                continue;
            }
            stages.add(new DamageReductionStage(bufferThreshold,
                    Math.max(0.0, Math.min(100.0, reductionPercent)),
                    durationSeconds));
        }
        if (stages.isEmpty()) {
            stages.addAll(createDefaultDamageReductionStages());
        }
        stages.sort(Comparator.comparingDouble(DamageReductionStage::getBufferThreshold));
        return Collections.unmodifiableList(stages);
    }

    private List<TrollActionConfig> loadTrollActions(FileConfiguration config, Logger logger) {
        List<Map<?, ?>> rawActions = config.getMapList("alert-responses.troll.actions");
        List<TrollActionConfig> actions = new ArrayList<>();
        for (Map<?, ?> rawAction : rawActions) {
            Object typeValue = rawAction.containsKey("type") ? rawAction.get("type") : "";
            String type = String.valueOf(typeValue).trim().toLowerCase(Locale.ROOT);
            double bufferThreshold = getDouble(rawAction.get("buffer"), 0.0);
            int cooldownSeconds = getInt(rawAction.get("cooldown-seconds"), 0);
            boolean onlySword = getBoolean(rawAction.get("only-sword"), true);
            double horizontalVelocity = getDouble(rawAction.get("horizontal-velocity"), 1.4);
            double verticalVelocity = getDouble(rawAction.get("vertical-velocity"), 0.45);
            String message = rawAction.containsKey("message") ? String.valueOf(rawAction.get("message")) : "";

            if (type.isEmpty() || bufferThreshold <= 0.0) {
                if (logger != null) {
                    logger.warning("[Config] Skipping invalid troll action: " + rawAction);
                }
                continue;
            }

            actions.add(new TrollActionConfig(type, bufferThreshold, Math.max(0, cooldownSeconds), onlySword,
                    horizontalVelocity, verticalVelocity, message));
        }
        if (actions.isEmpty()) {
            actions.addAll(createDefaultTrollActions());
        }
        actions.sort(Comparator.comparingDouble(TrollActionConfig::getBufferThreshold));
        return Collections.unmodifiableList(actions);
    }

    private int getInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double getDouble(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean getBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double clampThreshold(double value, String configPath, Logger logger) {
        if (value < 0.0 || value > 1.0) {
            double clamped = Math.max(0.0, Math.min(1.0, value));
            if (logger != null) {
                logger.warning("[Config] " + configPath + " value " + value +
                        " is outside valid range [0.0, 1.0], clamped to " + clamped);
            }
            return clamped;
        }
        return value;
    }
    
    /**
     * Миграция legacy префиксов {BAN} и {KICK} в {ANIMATION}
     */
    private String migrateLegacyPrefixes(String command) {
        if (command == null) {
            return null;
        }
        
        String trimmed = command.trim();
        
        // Заменяем {BAN} на {ANIMATION}
        if (trimmed.startsWith("{BAN}")) {
            return "{ANIMATION}" + trimmed.substring(5);
        }
        
        // Заменяем {KICK} на {ANIMATION}
        if (trimmed.startsWith("{KICK}")) {
            return "{ANIMATION}" + trimmed.substring(6);
        }
        
        return command;
    }

    public boolean isDebug() {
        return debug;
    }

    public String getLanguage() {
        return language;
    }

    public String getOutputDirectory() {
        return outputDirectory;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public String getAiApiKey() {
        return aiApiKey;
    }

    public double getAiAlertThreshold() {
        return aiAlertThreshold;
    }

    public boolean isAiConsoleAlerts() {
        return aiConsoleAlerts;
    }

    public boolean isAlertSoundEnabled() {
        return alertSoundEnabled;
    }

    public String getAlertSoundType() {
        return alertSoundType;
    }

    public float getAlertSoundVolume() {
        return alertSoundVolume;
    }

    public float getAlertSoundPitch() {
        return alertSoundPitch;
    }

    public double getAiBufferFlag() {
        return aiBufferFlag;
    }

    public double getAiBufferResetOnFlag() {
        return aiBufferResetOnFlag;
    }

    public double getAiBufferMultiplier() {
        return aiBufferMultiplier;
    }

    public double getAiBufferDecrease() {
        return aiBufferDecrease;
    }

    public double getAiBufferDecreaseThreshold() {
        return aiBufferDecreaseThreshold;
    }

    public int getAiSequence() {
        return aiSequence;
    }

    public int getAiStep() {
        return aiStep;
    }

    public boolean isAnimationEnabled() {
        return animationEnabled;
    }

    public String getAnimationType() {
        return animationType;
    }

    public Map<Integer, String> getPunishmentCommands() {
        return punishmentCommands;
    }

    public boolean isLiteBansEnabled() {
        return liteBansEnabled;
    }

    public String getLiteBansDbHost() {
        return liteBansDbHost;
    }

    public int getLiteBansDbPort() {
        return liteBansDbPort;
    }

    public String getLiteBansDbName() {
        return liteBansDbName;
    }

    public String getLiteBansDbUsername() {
        return liteBansDbUsername;
    }

    public String getLiteBansDbPassword() {
        return liteBansDbPassword;
    }

    public String getLiteBansTablePrefix() {
        return liteBansTablePrefix;
    }

    public int getLiteBansLookbackDays() {
        return liteBansLookbackDays;
    }

    public Set<String> getLiteBansCheatReasons() {
        return liteBansCheatReasons;
    }

    public boolean isAutostartEnabled() {
        return autostartEnabled;
    }

    public String getAutostartLabel() {
        return autostartLabel;
    }

    public String getAutostartComment() {
        return autostartComment;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    /**
     * Fallback endpoint for when {@link #getServerAddress()} cannot be reached at all, or empty
     * when failover is switched off.
     */
    public String getReserveServerAddress() {
        return reserveServerAddress == null ? "" : reserveServerAddress.trim();
    }

    /**
     * Whether a player whose name cannot be safely substituted into a console command is kicked
     * directly instead of having the punishment command run for them.
     */
    public boolean isSafeNameCheckEnabled() {
        return safeNameCheck;
    }

    public String getServerIdentityName() {
        return serverIdentityName;
    }

    public String getServerIdentityFamily() {
        return serverIdentityFamily;
    }

    public boolean isInterServerEnabled() {
        return interServerEnabled;
    }

    /** Whether player reports are shared across the account's servers (same family). */
    public boolean isCrossReportsEnabled() {
        return crossReportsEnabled;
    }

    public boolean isApiEventReportingEnabled() {
        return apiEventReportingEnabled;
    }

    public double getApiAlertEventThreshold() {
        return apiAlertEventThreshold;
    }

    public boolean isUpdatesEnabled() {
        return updatesEnabled;
    }

    /** Local, coarse switch: does this server collect MLS VISION data at all. Off by default. */
    public boolean isVisionEnabled() {
        return visionEnabled;
    }

    public boolean isVlDecayEnabled() {
        return vlDecayEnabled;
    }

    public int getVlDecayIntervalSeconds() {
        return vlDecayIntervalSeconds;
    }

    public int getVlDecayAmount() {
        return vlDecayAmount;
    }

    public boolean isWorldGuardEnabled() {
        return worldGuardEnabled;
    }

    public List<String> getWorldGuardDisabledRegions() {
        return worldGuardDisabledRegions;
    }

    public boolean isFoliaEnabled() {
        return foliaEnabled;
    }

    public int getFoliaThreadPoolSize() {
        return foliaThreadPoolSize;
    }

    public boolean isFoliaEntitySchedulerEnabled() {
        return foliaEntitySchedulerEnabled;
    }

    public boolean isFoliaRegionSchedulerEnabled() {
        return foliaRegionSchedulerEnabled;
    }

    /**
     * Whether this model may act at all. A disabled model's predictions are dropped on arrival:
     * no alert, no violation buffer, no punishment. Unknown keys default to enabled, so a model
     * added server-side works without a config change.
     */
    public boolean isModelEnabled(String modelKey) {
        if (modelKey == null) {
            return true;
        }
        return modelEnabled.getOrDefault(modelKey, true);
    }

    public boolean isOnlyAlertForModel(String modelKey) {
        if (modelKey == null) {
            return false;
        }
        return modelOnlyAlert.getOrDefault(modelKey, false);
    }

    public String getModelDisplayName(String modelKey) {
        if (modelKey == null) {
            return "Unknown";
        }
        return modelNames.getOrDefault(modelKey, modelKey);
    }

    public boolean isAlertResponsesEnabled() {
        return alertResponsesEnabled;
    }

    public boolean isDamageReductionEnabled() {
        return damageReductionEnabled;
    }

    public boolean isTrollEnabled() {
        return trollEnabled;
    }

    public double getAiAlertBufferStepPercent() {
        return aiAlertBufferStepPercent;
    }

    public List<DamageReductionStage> getDamageReductionStages() {
        return damageReductionStages;
    }

    public List<TrollActionConfig> getTrollActions() {
        return trollActions;
    }

    /** Name from {@code remote-config.preset}; empty when running on local config only. */
    public String getRemotePresetName() {
        return remotePresetName;
    }

    public boolean isRemoteConfigEnabled() {
        return !remotePresetName.isEmpty();
    }

    public int getRemoteRefreshMinutes() {
        return remoteRefreshMinutes;
    }

    /** True when the values above came from an API preset rather than the local layers. */
    public boolean isRemotePresetApplied() {
        return remotePresetApplied;
    }

    /** modules.yml -> reports. With this off there is no /report, no queue and no notifications. */
    public boolean isReportsEnabled() {
        return reportsEnabled;
    }

    /** modules.yml -> menus. Gates /mlsac suspects, /mlsac vision and the GUIs opened from them. */
    public boolean isMenusEnabled() {
        return menusEnabled;
    }

    /** Seconds a player waits between reports; 0 removes the cooldown. Staff are never held to it. */
    public int getReportCooldownSeconds() {
        return reportCooldownSeconds;
    }

    /** Ticks between WorldGuard roster sweeps, or 0 when the sweep is switched off. */
    public int getVisionRegionScanIntervalTicks() {
        return visionRegionScanIntervalTicks;
    }

    public boolean isAntiEspEnabled() {
        return antiEspEnabled;
    }

    public double getAntiEspMaxDistance() {
        return antiEspMaxDistance;
    }

    public double getAntiEspProximityDistance() {
        return antiEspProximityDistance;
    }

    public int getAntiEspHideDelayTicks() {
        return antiEspHideDelayTicks;
    }

    public int getAntiEspUpdateIntervalTicks() {
        return antiEspUpdateIntervalTicks;
    }

    /** Wall-clock budget for one occlusion pass; leftover viewers resume on the next pass. */
    public int getAntiEspBudgetMicros() {
        return antiEspBudgetMicros;
    }

    /** How long a raytrace result stays valid while neither player moves. */
    public int getAntiEspCacheTicks() {
        return antiEspCacheTicks;
    }

    /** Movement (blocks) by either player that forces a fresh raytrace. */
    public double getAntiEspMoveThreshold() {
        return antiEspMoveThreshold;
    }

    public int getAntiEspRayCount() {
        return antiEspRayCount;
    }

    public boolean isAntiEspFovEnabled() {
        return antiEspFovEnabled;
    }

    public double getAntiEspFovDegrees() {
        return antiEspFovDegrees;
    }

    public boolean isAntiEspRestoreMetadata() {
        return antiEspRestoreMetadata;
    }

    public boolean isAntiEspHideSounds() {
        return antiEspHideSounds;
    }

    public boolean isAntiEspHideGlowing() {
        return antiEspHideGlowing;
    }

    public boolean isAntiEspVerboseDebug() {
        return antiEspVerboseDebug;
    }

    public static final class DamageReductionStage {
        private final double bufferThreshold;
        private final double reductionPercent;
        private final int durationSeconds;

        public DamageReductionStage(double bufferThreshold, double reductionPercent, int durationSeconds) {
            this.bufferThreshold = bufferThreshold;
            this.reductionPercent = reductionPercent;
            this.durationSeconds = durationSeconds;
        }

        public double getBufferThreshold() {
            return bufferThreshold;
        }

        public double getReductionPercent() {
            return reductionPercent;
        }

        public int getDurationSeconds() {
            return durationSeconds;
        }
    }

    public static final class TrollActionConfig {
        private final String type;
        private final double bufferThreshold;
        private final int cooldownSeconds;
        private final boolean onlySword;
        private final double horizontalVelocity;
        private final double verticalVelocity;
        private final String message;

        public TrollActionConfig(String type, double bufferThreshold, int cooldownSeconds, boolean onlySword,
                double horizontalVelocity, double verticalVelocity, String message) {
            this.type = type;
            this.bufferThreshold = bufferThreshold;
            this.cooldownSeconds = cooldownSeconds;
            this.onlySword = onlySword;
            this.horizontalVelocity = horizontalVelocity;
            this.verticalVelocity = verticalVelocity;
            this.message = message;
        }

        public String getType() {
            return type;
        }

        public double getBufferThreshold() {
            return bufferThreshold;
        }

        public int getCooldownSeconds() {
            return cooldownSeconds;
        }

        public boolean isOnlySword() {
            return onlySword;
        }

        public double getHorizontalVelocity() {
            return horizontalVelocity;
        }

        public double getVerticalVelocity() {
            return verticalVelocity;
        }

        public String getMessage() {
            return message;
        }
    }
}
