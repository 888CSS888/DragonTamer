import com.dragontamer.DragonTamerPlugin;
import com.dragontamer.data.Dragon;
import com.dragontamer.data.DragonType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;

/**
 * GUI-меню коллекции драконов игрока.
 * Размер: 54 слота (6 рядов × 9).
 * Слоты 0–44: карточки драконов (до 45 штук).
 * Слоты 45–53: нижняя панель управления.
 */
public class DragonCollectionMenu {

    public static final String TITLE = ChatColor.GOLD + "" + ChatColor.BOLD + " Коллекция Драконов ";
    private static final int SIZE = 54;

    private final DragonTamerPlugin plugin;

    public DragonCollectionMenu(DragonTamerPlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    //  Открытие меню
    // =========================================================================

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, SIZE, TITLE);

        List<Dragon> collection = plugin.getDragonManager().getCollection(player.getUniqueId());
        Dragon activeDragon     = plugin.getDragonManager().getDragon(player.getUniqueId());

        // Карточки драконов (слоты 0–44)
        for (int i = 0; i < Math.min(collection.size(), 45); i++) {
            Dragon d = collection.get(i);
            boolean isActive = activeDragon != null && activeDragon.getDragonId().equals(d.getDragonId());
            inv.setItem(i, buildDragonCard(d, isActive));
        }

        // Заполняем пустые слоты серыми панелями
        ItemStack filler = makeItem(Material.STAINED_GLASS_PANE, 7,
            ChatColor.DARK_GRAY + " ", null);
        for (int i = 0; i < 45; i++)
            if (inv.getItem(i) == null) inv.setItem(i, filler);

        // Нижняя панель
        buildBottomBar(inv, player, collection.size());

        plugin.getCollectionListener().trackOpen(player.getUniqueId());
        player.openInventory(inv);

        player.playSound(player.getLocation(), Sound.BLOCK_ENDERCHEST_OPEN, 0.7f, 1.1f);
    }

    // =========================================================================
    //  Построение нижней панели
    // =========================================================================

    private void buildBottomBar(Inventory inv, Player player, int total) {
        // Тёмно-фиолетовые панели по бокам
        ItemStack border = makeItem(Material.STAINED_GLASS_PANE, 5, ChatColor.DARK_PURPLE + " ", null);
        for (int slot : new int[]{45, 46, 47, 51, 52, 53})
            inv.setItem(slot, border);

        // Слот 48: информация о коллекции
        List<String> infoLore = new ArrayList<>();
        infoLore.add(ChatColor.GRAY + "Всего драконов: " + ChatColor.YELLOW + total);
        infoLore.add(ChatColor.GRAY + "Нажмите на дракона, чтобы");
        infoLore.add(ChatColor.GRAY + "сделать его активным.");
        inv.setItem(48, makeItem(Material.BOOK, 0,
            ChatColor.AQUA + "" + ChatColor.BOLD + "Коллекция",
            infoLore));

        // Слот 49: кнопка закрытия
        List<String> closeLore = new ArrayList<>();
        closeLore.add(ChatColor.GRAY + "Закрыть меню коллекции");
        inv.setItem(49, makeItem(Material.BARRIER, 0,
            ChatColor.RED + "" + ChatColor.BOLD + "Закрыть",
            closeLore));

        // Слот 50: информация об активном драконе
        Dragon active = plugin.getDragonManager().getDragon(player.getUniqueId());
        if (active != null) {
            List<String> activeLore = new ArrayList<>();
            if (active.hasNickname())
                activeLore.add(ChatColor.GRAY + "Имя: " + ChatColor.YELLOW + active.getNickname());
            activeLore.add(ChatColor.GRAY + "Тип: " + ChatColor.translateAlternateColorCodes('&',
                active.getType().getColorCode() + getTypeName(active.getType())));
            activeLore.add(ChatColor.GRAY + "Уровень: " + ChatColor.YELLOW + active.getLevel());
            double hp = active.getEntity() != null && !active.getEntity().isDead()
                ? active.getEntity().getHealth() : active.getCurrentHealth();
            activeLore.add(ChatColor.GRAY + "Здоровье: " + ChatColor.GREEN
                + String.format("%.0f", hp) + "/" + String.format("%.0f", plugin.getDragonManager().getMaxHealth(active)));
            inv.setItem(50, makeItem(Material.NETHER_STAR, 0,
                ChatColor.GOLD + "" + ChatColor.BOLD + "Активный дракон",
                activeLore));
        } else {
            inv.setItem(50, makeItem(Material.STAINED_GLASS_PANE, 14,
                ChatColor.RED + "Нет активного дракона", null));
        }
    }

    // =========================================================================
    //  Карточка дракона
    // =========================================================================

    public ItemStack buildDragonCard(Dragon dragon, boolean isActive) {
        DragonType type = dragon.getType();
        String typeName = getTypeName(type);
        String evo      = plugin.getDragonManager().getEvolutionSuffix(dragon);
        double maxHp    = plugin.getDragonManager().getMaxHealth(dragon);
        double hp       = dragon.getEntity() != null && !dragon.getEntity().isDead()
                          ? dragon.getEntity().getHealth()
                          : dragon.getCurrentHealth();
        long nextExp    = plugin.getDragonManager().getExpForNextLevel(dragon);

        // Имя карточки — никнейм (если есть) или тип
        String displayTitle = dragon.hasNickname()
            ? ChatColor.translateAlternateColorCodes('&', type.getColorCode() + dragon.getNickname())
            : ChatColor.translateAlternateColorCodes('&', type.getColorCode() + typeName);

        String cardName;
        if (isActive) {
            cardName = ChatColor.GREEN + "★ " + displayTitle + ChatColor.GREEN + " [АКТИВНЫЙ]";
        } else {
            cardName = displayTitle;
        }

        // Лор
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "──────────────────");

        // Никнейм в лоре
        if (dragon.hasNickname()) {
            lore.add(ChatColor.GRAY + "Имя: " + ChatColor.YELLOW + dragon.getNickname());
        }
        lore.add(ChatColor.GRAY + "Тип: " + ChatColor.translateAlternateColorCodes('&', type.getColorCode() + typeName));
        lore.add(ChatColor.GRAY + "Уровень: " + ChatColor.YELLOW + dragon.getLevel());
        lore.add(ChatColor.GRAY + "Опыт: " + ChatColor.YELLOW + dragon.getExperience()
            + ChatColor.GRAY + "/" + ChatColor.YELLOW + nextExp);

        // Полоска здоровья
        String hpBar = buildHpBar(hp, maxHp);
        lore.add(ChatColor.GRAY + "Здоровье: " + ChatColor.GREEN + String.format("%.0f", hp)
            + ChatColor.GRAY + "/" + ChatColor.GREEN + String.format("%.0f", maxHp));
        lore.add(hpBar);

        // Эволюция
        if (evo != null && !evo.isEmpty())
            lore.add(ChatColor.GRAY + "Эволюция: " + ChatColor.LIGHT_PURPLE + evo);

        // Режим
        String mode;
        if (dragon.isOrbitMode())       mode = ChatColor.AQUA + "Орбита";
        else if (dragon.isAutoFarmMode()) mode = ChatColor.GREEN + "Авто-ферма";
        else if (dragon.isFollowing())    mode = ChatColor.WHITE + "Следует";
        else                              mode = ChatColor.GRAY + "Стоит";
        lore.add(ChatColor.GRAY + "Режим: " + mode);

        // Восстановление
        if (dragon.isRecovering()) {
            lore.add("");
            lore.add(ChatColor.RED + "" + ChatColor.BOLD + "⚠ Восстановление: "
                + dragon.getRecoveryRemainingMinutes() + " мин.");
        }

        lore.add(ChatColor.DARK_GRAY + "──────────────────");

        // Подсказка действия
        if (isActive) {
            lore.add(ChatColor.GOLD + "★ Это ваш текущий дракон");
            lore.add(ChatColor.GRAY + "Переименовать: " + ChatColor.WHITE + "/dr name <имя>");
        } else {
            lore.add(ChatColor.GREEN + "▶ Нажмите, чтобы выбрать");
        }

        // Материал карточки
        Material mat = isActive ? Material.NETHER_STAR : Material.DRAGON_EGG;

        ItemStack card = new ItemStack(mat);
        ItemMeta meta = card.getItemMeta();
        meta.setDisplayName(cardName);
        meta.setLore(lore);
        card.setItemMeta(meta);
        return card;
    }

    // =========================================================================
    //  Вспомогательные методы
    // =========================================================================

    private String getTypeName(DragonType type) {
        return plugin.getConfig().getString(
            "dragon-types." + type.name() + ".display-name", type.getDisplayName());
    }

    private String buildHpBar(double hp, double maxHp) {
        int bars     = 10;
        int filled   = maxHp > 0 ? (int) Math.round((hp / maxHp) * bars) : 0;
        StringBuilder sb = new StringBuilder(ChatColor.GRAY + "[");
        for (int i = 0; i < bars; i++) {
            if (i < filled) sb.append(ChatColor.GREEN).append("■");
            else            sb.append(ChatColor.DARK_GRAY).append("■");
        }
        sb.append(ChatColor.GRAY).append("]");
        return "  " + sb;
    }

    private ItemStack makeItem(Material mat, int data, String name, List<String> lore) {
        @SuppressWarnings("deprecation")
        ItemStack item = data > 0 ? new ItemStack(mat, 1, (short) data) : new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}