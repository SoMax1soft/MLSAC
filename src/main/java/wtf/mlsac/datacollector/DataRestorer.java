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

package wtf.mlsac.datacollector;

import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.data.TickData;
import wtf.mlsac.util.SecurityUtil;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

public class DataRestorer {
    private final JavaPlugin plugin;
    private final File restoredDataFolder;

    public DataRestorer(JavaPlugin plugin) {
        this.plugin = plugin;
        this.restoredDataFolder = new File(plugin.getDataFolder(), "restored_data");
        if (!restoredDataFolder.exists()) {
            restoredDataFolder.mkdirs();
        }
    }

    public boolean restoreData(String playerName, List<TickData> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = SecurityUtil.sanitizeFileName(playerName) + "_" + timestamp + ".csv";
        File file = new File(restoredDataFolder, fileName);

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            // Write CSV header
            writer.println(TickData.getHeader());

            // Write ticks
            // In history we don't know if they are cheating or not, 
            // but for data collection/restoration we usually mark as unknown or 0
            for (TickData tick : history) {
                writer.println(tick.toCsv("LEGIT")); // Marking as legit by default for FP analysis
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to restore data for " + playerName, e);
            return false;
        }
    }

    public File getRestoredDataFolder() {
        return restoredDataFolder;
    }
}
