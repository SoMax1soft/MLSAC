/*
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

import org.bukkit.Bukkit;
import org.geysermc.geyser.api.GeyserApi;

import java.util.UUID;
import java.util.logging.Logger;
import java.lang.reflect.Method;

public class GeyserUtil {

    private static boolean geyserAvailable = false;
    private static boolean floodgateAvailable = false;
    private static Object floodgateApiInstance = null;
    private static Method isFloodgatePlayerMethod = null;

    static {
        try {
            Class.forName("org.geysermc.geyser.api.GeyserApi");
            geyserAvailable = true;
        } catch (ClassNotFoundException e) {
            geyserAvailable = false;
        }

        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstanceMethod = floodgateApiClass.getMethod("getInstance");
            floodgateApiInstance = getInstanceMethod.invoke(null);
            isFloodgatePlayerMethod = floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class);
            floodgateAvailable = true;
        } catch (Exception e) {
            floodgateAvailable = false;
        }
    }

    public static boolean isBedrockPlayer(UUID uuid) {
        if (geyserAvailable) {
            try {
                GeyserApi api = GeyserApi.api();
                if (api != null && api.isBedrockPlayer(uuid)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        
        if (floodgateAvailable && floodgateApiInstance != null && isFloodgatePlayerMethod != null) {
            try {
                Object result = isFloodgatePlayerMethod.invoke(floodgateApiInstance, uuid);
                if (result instanceof Boolean && (Boolean) result) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    public static boolean isGeyserAvailable() {
        return geyserAvailable || floodgateAvailable;
    }
}
