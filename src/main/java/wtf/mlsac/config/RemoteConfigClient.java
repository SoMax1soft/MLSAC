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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Downloads a named config preset from the MLSAC API and turns it into a configuration overlay.
 *
 * <p>The backend validates every preset before storing it; this class validates the response again
 * from scratch. Nothing from the wire is trusted, so a hostile or misconfigured endpoint cannot
 * make the plugin run an arbitrary console command, read a file outside the animations folder, or
 * push a value that breaks the violation maths. Values that fail validation are dropped and fall
 * back to the local or bundled layer.
 */
public final class RemoteConfigClient {
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 10;
    /** A valid preset is a few kB. */
    private static final long MAX_RESPONSE_BYTES = 256 * 1024;

    private static final Pattern REGION_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-:]{1,64}$");
    private static final Pattern SOUND_PATTERN = Pattern.compile("^[A-Za-z0-9_.]{1,64}$");
    /** Resolved to {@code plugins/MLSAC/animations/<type>.yml}, so no separators and no dots. */
    private static final Pattern ANIMATION_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,48}$");
    private static final Pattern VIOLATION_LEVEL_PATTERN = Pattern.compile("^[1-9][0-9]{0,2}$");

    private static final List<String> MODEL_KEYS = Arrays.asList("fast", "pro", "ultra", "experimental");
    /** Alert-only on these stable models would disable bans entirely. */
    private static final List<String> ALWAYS_PUNISHING_MODELS = Arrays.asList("fast", "pro");
    private static final List<String> TROLL_TYPES =
            Arrays.asList("shuffle_inventory", "drop_weapon", "launch");

    private static final int MAX_REGIONS = 200;
    private static final int MAX_ACTIONS = 50;
    private static final int MAX_STAGES = 10;
    private static final int MAX_TROLL_ACTIONS = 10;

    private final OkHttpClient httpClient;
    private final Logger logger;
    private final boolean debug;

    public RemoteConfigClient(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * Result of a fetch.
     *
     * <p>{@link #hasConfig()} separates "the account has this preset" from the normal "test mode
     * off / no such preset", which is an empty snapshot rather than an error.
     */
    public static final class Snapshot {
        private final String preset;
        private final String hash;
        private final Map<String, Object> overlay;
        private final List<String> replacedSections;

        private Snapshot(String preset, String hash, Map<String, Object> overlay, List<String> replacedSections) {
            this.preset = preset;
            this.hash = hash;
            this.overlay = overlay;
            this.replacedSections = replacedSections;
        }

        public static Snapshot empty() {
            return new Snapshot(null, "", null, java.util.Collections.emptyList());
        }

        public boolean hasConfig() {
            return overlay != null;
        }

        public String getPreset() {
            return preset;
        }

        /** Stable identity of the loaded values; used to skip redundant reloads. */
        public String getHash() {
            return hash;
        }

        public Map<String, Object> getOverlay() {
            return overlay;
        }

        /** Paths whose whole section the preset owns and therefore replaces, not merges. */
        public List<String> getReplacedSections() {
            return replacedSections;
        }
    }

    /**
     * Blocking fetch; call from an async thread.
     *
     * @return a snapshot, or {@code null} on failure (network error, auth failure, malformed
     *         body), in which case the caller keeps what it already had.
     */
    public Snapshot fetch(String endpoint, String apiKey, String presetName) {
        HttpUrl url = buildUrl(endpoint, presetName);
        if (url == null) {
            logger.warning("[RemoteConfig] Invalid detection.endpoint - cannot fetch preset");
            return null;
        }

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("X-API-Key", apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            if (code == 401 || code == 403) {
                logger.warning("[RemoteConfig] Preset fetch rejected (HTTP " + code
                        + ") - check detection.api-key");
                return null;
            }
            if (code == 404 || code == 405) {
                if (debug) {
                    logger.info("[RemoteConfig] Backend has no preset endpoint - using local configuration");
                }
                return Snapshot.empty();
            }
            if (!response.isSuccessful()) {
                if (debug) {
                    logger.warning("[RemoteConfig] Preset fetch failed: HTTP " + code);
                }
                return null;
            }

            if (response.body() == null) {
                return null;
            }
            // Capped, so a hostile or broken endpoint cannot make the server buffer an
            // unbounded body.
            String payload = response.peekBody(MAX_RESPONSE_BYTES).string();
            return parse(payload);
        } catch (Exception exception) {
            if (debug) {
                logger.warning("[RemoteConfig] Preset fetch error: " + exception.getMessage());
            }
            return null;
        }
    }

    private HttpUrl buildUrl(String endpoint, String presetName) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return null;
        }
        HttpUrl base = HttpUrl.parse(endpoint.trim());
        if (base == null) {
            return null;
        }
        return base.newBuilder()
                .addPathSegments("api/v1/plugin/config")
                .addQueryParameter("preset", presetName)
                .build();
    }

    Snapshot parse(String payload) {
        JsonObject root;
        try {
            JsonElement parsed = new JsonParser().parse(payload);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (Exception exception) {
            if (debug) {
                logger.warning("[RemoteConfig] Malformed preset response: " + exception.getMessage());
            }
            return null;
        }

        if (!root.has("data") || !root.get("data").isJsonObject()) {
            return null;
        }
        JsonObject data = root.getAsJsonObject("data");
        if (!data.has("config") || !data.get("config").isJsonObject()) {
            // Test mode off, or no preset with that name. A normal state, not a failure.
            return Snapshot.empty();
        }

        JsonObject config = data.getAsJsonObject("config");
        Map<String, Object> nested = new LinkedHashMap<>();
        List<String> replacedSections = new ArrayList<>();

        readDetection(config, nested, replacedSections);
        readAlerts(config, nested);
        readViolation(config, nested);
        readPenalties(config, nested, replacedSections);
        readAlertResponses(config, nested);

        if (nested.isEmpty()) {
            if (debug) {
                logger.warning("[RemoteConfig] Preset contained no usable settings");
            }
            return Snapshot.empty();
        }

        Map<String, Object> overlay = ConfigLayers.flatten(nested);
        String hash = str(data, "hash", "");
        if (hash.isEmpty()) {
            hash = Integer.toHexString(overlay.toString().hashCode());
        }
        return new Snapshot(str(data, "preset", ""), hash, overlay, replacedSections);
    }

    // ── Section readers ────────────────────────────────────────────────────────────────────

    private void readDetection(JsonObject config, Map<String, Object> out, List<String> replaced) {
        JsonObject detection = obj(config, "detection");
        if (detection == null) {
            return;
        }

        JsonObject worldguard = obj(detection, "worldguard");
        if (worldguard != null) {
            Map<String, Object> target = new LinkedHashMap<>();
            putBool(worldguard, "enabled", target, "enabled");

            JsonArray regions = arr(worldguard, "disabled-regions");
            if (regions != null) {
                List<String> parsed = new ArrayList<>();
                for (JsonElement element : regions) {
                    if (parsed.size() >= MAX_REGIONS) {
                        break;
                    }
                    String region = asString(element);
                    if (region != null && REGION_PATTERN.matcher(region).matches()) {
                        parsed.add(region);
                    } else {
                        warnDropped("detection.worldguard.disabled-regions entry");
                    }
                }
                target.put("disabled-regions", parsed);
            }
            if (!target.isEmpty()) {
                nest(out, "detection", "worldguard", target);
            }
        }

        JsonObject models = obj(detection, "models");
        if (models != null) {
            Map<String, Object> parsedModels = new LinkedHashMap<>();
            for (String key : MODEL_KEYS) {
                JsonObject model = obj(models, key);
                if (model == null) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                String name = sanitizeText(str(model, "name", ""), 32);
                if (!name.isEmpty()) {
                    entry.put("name", name);
                }
                // Absent means enabled, so a preset written before the switch existed does not
                // turn every model off.
                entry.put("enabled", bool(model, "enabled", true));
                boolean onlyAlert = bool(model, "only-alert", false);
                entry.put("only-alert", onlyAlert && !ALWAYS_PUNISHING_MODELS.contains(key));
                parsedModels.put(key, entry);
            }
            if (!parsedModels.isEmpty()) {
                nest(out, "detection", "models", parsedModels);
                // The preset lists every model it wants, so stale local keys must go.
                replaced.add("detection.models");
            }
        }
    }

    private void readAlerts(JsonObject config, Map<String, Object> out) {
        JsonObject alerts = obj(config, "alerts");
        if (alerts == null) {
            return;
        }
        Map<String, Object> target = new LinkedHashMap<>();
        putClamped(alerts, "threshold", target, "threshold", 0.0, 1.0);
        putBool(alerts, "console", target, "console");

        JsonObject sound = obj(alerts, "sound");
        if (sound != null) {
            Map<String, Object> soundTarget = new LinkedHashMap<>();
            putBool(sound, "enabled", soundTarget, "enabled");
            String type = str(sound, "type", "");
            if (SOUND_PATTERN.matcher(type).matches()) {
                soundTarget.put("type", type);
            } else if (!type.isEmpty()) {
                warnDropped("alerts.sound.type");
            }
            putClamped(sound, "volume", soundTarget, "volume", 0.0, 10.0);
            putClamped(sound, "pitch", soundTarget, "pitch", 0.5, 2.0);
            if (!soundTarget.isEmpty()) {
                target.put("sound", soundTarget);
            }
        }
        if (!target.isEmpty()) {
            out.put("alerts", target);
        }
    }

    private void readViolation(JsonObject config, Map<String, Object> out) {
        JsonObject violation = obj(config, "violation");
        if (violation == null) {
            return;
        }
        Map<String, Object> target = new LinkedHashMap<>();
        putClamped(violation, "threshold", target, "threshold", 1.0, 100000.0);
        putClamped(violation, "reset-value", target, "reset-value", 0.0, 100000.0);
        putClamped(violation, "multiplier", target, "multiplier", 0.0, 10000.0);

        // At or above the flag threshold the penalty re-triggers on every sample.
        Object threshold = target.get("threshold");
        Object resetValue = target.get("reset-value");
        if (threshold instanceof Number && resetValue instanceof Number
                && ((Number) resetValue).doubleValue() >= ((Number) threshold).doubleValue()) {
            target.put("reset-value", ((Number) threshold).doubleValue() / 2.0);
            warnDropped("violation.reset-value (>= threshold, halved)");
        }

        JsonObject decay = obj(violation, "decay");
        if (decay != null) {
            Map<String, Object> decayTarget = new LinkedHashMap<>();
            putClamped(decay, "threshold", decayTarget, "threshold", 0.0, 1.0);
            putClamped(decay, "amount", decayTarget, "amount", 0.0, 10000.0);
            if (!decayTarget.isEmpty()) {
                target.put("decay", decayTarget);
            }
        }

        JsonObject vlDecay = obj(violation, "vl-decay");
        if (vlDecay != null) {
            Map<String, Object> vlTarget = new LinkedHashMap<>();
            putBool(vlDecay, "enabled", vlTarget, "enabled");
            putClampedInt(vlDecay, "interval", vlTarget, "interval", 1, 86400);
            putClampedInt(vlDecay, "amount", vlTarget, "amount", 0, 10000);
            if (!vlTarget.isEmpty()) {
                target.put("vl-decay", vlTarget);
            }
        }
        if (!target.isEmpty()) {
            out.put("violation", target);
        }
    }

    private void readPenalties(JsonObject config, Map<String, Object> out, List<String> replaced) {
        JsonObject penalties = obj(config, "penalties");
        if (penalties == null) {
            return;
        }
        Map<String, Object> target = new LinkedHashMap<>();

        JsonObject animation = obj(penalties, "animation");
        if (animation != null) {
            Map<String, Object> animationTarget = new LinkedHashMap<>();
            putBool(animation, "enabled", animationTarget, "enabled");
            String type = str(animation, "type", "");
            if (ANIMATION_PATTERN.matcher(type).matches()) {
                animationTarget.put("type", type);
            } else if (!type.isEmpty()) {
                warnDropped("penalties.animation.type");
            }
            if (!animationTarget.isEmpty()) {
                target.put("animation", animationTarget);
            }
        }

        JsonObject actions = obj(penalties, "actions");
        if (actions != null) {
            Map<String, Object> actionTarget = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : actions.entrySet()) {
                if (actionTarget.size() >= MAX_ACTIONS) {
                    break;
                }
                String level = entry.getKey();
                if (!VIOLATION_LEVEL_PATTERN.matcher(level).matches()) {
                    warnDropped("penalties.actions key '" + level + "'");
                    continue;
                }
                // Stripped here as well as on the server: a newline in a console command would be
                // dispatched as a second command.
                String command = sanitizeText(asString(entry.getValue()), 256);
                if (command.isEmpty()) {
                    warnDropped("penalties.actions." + level);
                    continue;
                }
                actionTarget.put(level, command);
            }
            if (!actionTarget.isEmpty()) {
                target.put("actions", actionTarget);
                // The preset is authoritative for the punishment ladder, so a level it dropped
                // must not survive the merge.
                replaced.add("penalties.actions");
            }
        }

        if (!target.isEmpty()) {
            out.put("penalties", target);
        }
    }

    private void readAlertResponses(JsonObject config, Map<String, Object> out) {
        JsonObject responses = obj(config, "alert-responses");
        if (responses == null) {
            return;
        }
        Map<String, Object> target = new LinkedHashMap<>();
        putBool(responses, "enabled", target, "enabled");

        JsonObject alerts = obj(responses, "alerts");
        if (alerts != null && alerts.has("buffer-step-percent")) {
            double step = num(alerts, "buffer-step-percent", Double.NaN);
            if (!Double.isNaN(step) && step > 0.0 && step <= 1.0) {
                Map<String, Object> alertTarget = new LinkedHashMap<>();
                alertTarget.put("buffer-step-percent", step);
                target.put("alerts", alertTarget);
            } else {
                warnDropped("alert-responses.alerts.buffer-step-percent");
            }
        }

        JsonObject damage = obj(responses, "damage-reduction");
        if (damage != null) {
            Map<String, Object> damageTarget = new LinkedHashMap<>();
            putBool(damage, "enabled", damageTarget, "enabled");
            JsonArray stages = arr(damage, "stages");
            if (stages != null) {
                List<Map<String, Object>> parsed = new ArrayList<>();
                for (JsonElement element : stages) {
                    if (parsed.size() >= MAX_STAGES) {
                        break;
                    }
                    Map<String, Object> stage = readDamageStage(element);
                    if (stage != null) {
                        parsed.add(stage);
                    }
                }
                damageTarget.put("stages", parsed);
            }
            if (!damageTarget.isEmpty()) {
                target.put("damage-reduction", damageTarget);
            }
        }

        JsonObject troll = obj(responses, "troll");
        if (troll != null) {
            Map<String, Object> trollTarget = new LinkedHashMap<>();
            putBool(troll, "enabled", trollTarget, "enabled");
            JsonArray actions = arr(troll, "actions");
            if (actions != null) {
                List<Map<String, Object>> parsed = new ArrayList<>();
                for (JsonElement element : actions) {
                    if (parsed.size() >= MAX_TROLL_ACTIONS) {
                        break;
                    }
                    Map<String, Object> action = readTrollAction(element);
                    if (action != null) {
                        parsed.add(action);
                    }
                }
                trollTarget.put("actions", parsed);
            }
            if (!trollTarget.isEmpty()) {
                target.put("troll", trollTarget);
            }
        }

        if (!target.isEmpty()) {
            out.put("alert-responses", target);
        }
    }

    private Map<String, Object> readDamageStage(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            warnDropped("alert-responses.damage-reduction stage");
            return null;
        }
        JsonObject stage = element.getAsJsonObject();
        double buffer = num(stage, "buffer", Double.NaN);
        double reduction = num(stage, "reduction-percent", Double.NaN);
        double duration = num(stage, "duration-seconds", Double.NaN);
        if (Double.isNaN(buffer) || buffer <= 0.0
                || Double.isNaN(reduction) || reduction < 0.0 || reduction > 100.0
                || Double.isNaN(duration) || duration < 1.0 || duration > 3600.0) {
            warnDropped("alert-responses.damage-reduction stage");
            return null;
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("buffer", Math.min(buffer, 100000.0));
        parsed.put("reduction-percent", reduction);
        parsed.put("duration-seconds", (int) duration);
        return parsed;
    }

    private Map<String, Object> readTrollAction(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            warnDropped("alert-responses.troll action");
            return null;
        }
        JsonObject action = element.getAsJsonObject();
        String type = str(action, "type", "").toLowerCase(Locale.ROOT);
        double buffer = num(action, "buffer", Double.NaN);
        if (!TROLL_TYPES.contains(type) || Double.isNaN(buffer) || buffer <= 0.0) {
            warnDropped("alert-responses.troll action");
            return null;
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("type", type);
        parsed.put("buffer", Math.min(buffer, 100000.0));
        parsed.put("cooldown-seconds", (int) clamp(num(action, "cooldown-seconds", 0), 0, 3600));
        parsed.put("only-sword", bool(action, "only-sword", true));
        parsed.put("horizontal-velocity", clamp(num(action, "horizontal-velocity", 1.4), 0.0, 10.0));
        parsed.put("vertical-velocity", clamp(num(action, "vertical-velocity", 0.45), 0.0, 10.0));
        parsed.put("message", sanitizeText(str(action, "message", ""), 256));
        return parsed;
    }

    // ── Primitive helpers ──────────────────────────────────────────────────────────────────

    private void nest(Map<String, Object> out, String parent, String child, Map<String, Object> value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) out.computeIfAbsent(parent,
                key -> new LinkedHashMap<String, Object>());
        section.put(child, value);
    }

    private void putBool(JsonObject source, String key, Map<String, Object> target, String targetKey) {
        if (source.has(key) && source.get(key).isJsonPrimitive()) {
            target.put(targetKey, bool(source, key, false));
        }
    }

    private void putClamped(JsonObject source, String key, Map<String, Object> target, String targetKey,
                            double min, double max) {
        double value = num(source, key, Double.NaN);
        if (Double.isNaN(value)) {
            if (source.has(key)) {
                warnDropped(targetKey);
            }
            return;
        }
        target.put(targetKey, clamp(value, min, max));
    }

    private void putClampedInt(JsonObject source, String key, Map<String, Object> target, String targetKey,
                               int min, int max) {
        double value = num(source, key, Double.NaN);
        if (Double.isNaN(value)) {
            if (source.has(key)) {
                warnDropped(targetKey);
            }
            return;
        }
        target.put(targetKey, (int) clamp(value, min, max));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static JsonObject obj(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static JsonArray arr(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonArray() ? parent.getAsJsonArray(key) : null;
    }

    private static String str(JsonObject parent, String key, String fallback) {
        String value = parent.has(key) ? asString(parent.get(key)) : null;
        return value != null ? value : fallback;
    }

    private static String asString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private static boolean bool(JsonObject parent, String key, boolean fallback) {
        if (!parent.has(key) || !parent.get(key).isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = parent.getAsJsonPrimitive(key);
        return primitive.isBoolean() ? primitive.getAsBoolean() : fallback;
    }

    private static double num(JsonObject parent, String key, double fallback) {
        if (!parent.has(key) || !parent.get(key).isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = parent.getAsJsonPrimitive(key);
        if (!primitive.isNumber()) {
            return fallback;
        }
        double value = primitive.getAsDouble();
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : value;
    }

    /** Strips control characters (newlines included) and truncates. */
    private static String sanitizeText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(text.length(), maxLength));
        for (int i = 0; i < text.length() && builder.length() < maxLength; i++) {
            char character = text.charAt(i);
            if (character >= 0x20 && character != 0x7F) {
                builder.append(character);
            }
        }
        return builder.toString().trim();
    }

    private void warnDropped(String what) {
        if (debug) {
            logger.warning("[RemoteConfig] Dropped invalid " + what + " from the preset");
        }
    }
}
