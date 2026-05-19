import com.dragontamer.DragonTamerPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MessageUtils {
    private final DragonTamerPlugin plugin;

    public MessageUtils(DragonTamerPlugin plugin) {
        this.plugin = plugin;
    }

    public String colorize(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public String getPrefix() {
        return colorize(plugin.getConfig().getString("messages.prefix", "&8[&6DragonTamer&8] "));
    }

    public String get(String key) {
        String raw = plugin.getConfig().getString("messages." + key, "&cНет сообщения: " + key);
        return colorize(raw);
    }

    public String get(String key, String... replacements) {
        String msg = get(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    public void send(CommandSender sender, String key, String... replacements) {
        if (sender == null) return;
        sender.sendMessage(getPrefix() + get(key, replacements));
    }

    public void sendRaw(CommandSender sender, String message) {
        if (sender == null) return;
        sender.sendMessage(getPrefix() + colorize(message));
    }
}