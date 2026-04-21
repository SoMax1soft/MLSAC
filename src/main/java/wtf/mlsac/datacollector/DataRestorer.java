package wtf.mlsac.datacollector;

import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.data.TickData;
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
        String fileName = playerName + "_" + timestamp + ".csv";
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
