package com.dragontamer.managers;

import com.dragontamer.DragonTamerPlugin;
import com.dragontamer.data.Dragon;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * BattleManager v1.3.2 — multi-arena, smooth dragon flight, watcher support.
 *
 * Smooth flight: both battle dragons orbit the arena center at opposite angles,
 * updating every 4 ticks. A 10-second intro circle runs before the countdown.
 * Arenas spawn in 'dragon_arena' world, offset per slot so concurrent battles
 * never overlap. Blocks are tracked and removed 3 seconds after battle ends.
 * /dr watch <player> lets spectators teleport to a live arena's bleachers.
 */
public class BattleManager {

    private final DragonTamerPlugin plugin;
    private final BattleAI ai;

    private final Map<UUID, UUID> pendingRequests = new HashMap<>();
    private final Map<UUID, Battle> activeBattles  = new HashMap<>();
    private final Map<UUID, Long>  dodgeCooldowns  = new HashMap<>();

    private final Set<Integer>    usedArenaSlots  = new HashSet<>();
    private final Map<UUID, UUID> watcherBattleMap = new HashMap<>();  // watcher -> challengerUUID

    // =========================================================================
    //  Inner: ArenaData — tracks every block placed so we can clean it up
    // =========================================================================

    public static class ArenaData {
        final List<int[]> placed = new ArrayList<>();  // [x, y, z, oldTypeByte]
        void record(World w, int x, int y, int z) {
            Block b = w.getBlockAt(x, y, z);
            placed.add(new int[]{x, y, z, b.getType().getId(), b.getData()});
        }
    }

    // =========================================================================
    //  Inner: Battle
    // =========================================================================

    public static class Battle {
        public final UUID challenger;
        public final UUID target;
        public EnderDragon challengerDragon;
        public EnderDragon targetDragon;
        public Location challengerReturn;
        public Location targetReturn;

        public BossBar challengerOwnBar;
        public BossBar challengerEnemyBar;
        public BossBar targetOwnBar;
        public BossBar targetEnemyBar;

        public BukkitTask aiTask;
        public BukkitTask bossBarTask;
        public BukkitTask flightTask;   // smooth circular flight for both dragons

        public int  arenaIndex  = 0;
        public ArenaData arenaData = new ArenaData();
        public Set<UUID>           watchers      = new HashSet<>();
        public Map<UUID, Location> watcherReturns = new HashMap<>();

        public Battle(UUID challenger, UUID target) {
            this.challenger = challenger;
            this.target     = target;
        }
    }

    // =========================================================================
    //  Constructor
    // =========================================================================

    public BattleManager(DragonTamerPlugin plugin) {
        this.plugin = plugin;
        this.ai     = new BattleAI(plugin);
    }

    // =========================================================================
    //  Pending requests
    // =========================================================================

    public void sendBattleRequest(UUID challengerUUID, UUID targetUUID) {
        pendingRequests.put(challengerUUID, targetUUID);
        new BukkitRunnable() {
            @Override public void run() { pendingRequests.remove(challengerUUID, targetUUID); }
        }.runTaskLater(plugin, 30 * 20L);
    }

    public UUID getChallengerFor(UUID targetUUID) {
        for (Map.Entry<UUID, UUID> e : pendingRequests.entrySet())
            if (e.getValue().equals(targetUUID)) return e.getKey();
        return null;
    }

    public void rejectRequest(UUID targetUUID) {
        UUID challenger = getChallengerFor(targetUUID);
        if (challenger != null) pendingRequests.remove(challenger);
    }

    // =========================================================================
    //  Start battle
    // =========================================================================

    public void startBattle(UUID challengerUUID, UUID targetUUID) {
        pendingRequests.remove(challengerUUID);

        Player challenger = Bukkit.getPlayer(challengerUUID);
        Player target     = Bukkit.getPlayer(targetUUID);
        Dragon cDragon    = plugin.getDragonManager().getDragon(challengerUUID);
        Dragon tDragon    = plugin.getDragonManager().getDragon(targetUUID);

        if (challenger == null || target == null || cDragon == null || tDragon == null) return;

        // --- Concurrent battle cap ---
        int maxBattles = plugin.getConfig().getInt("battle.max-concurrent-battles", 5);
        if (activeBattles.size() >= maxBattles) {
            plugin.getMessageUtils().sendRaw(challenger, "&cМаксимальное число битв достигнуто. Попробуйте позже.");
            plugin.getMessageUtils().sendRaw(target,     "&cМаксимальное число битв достигнуто. Попробуйте позже.");
            return;
        }

        // --- Find base arena location ---
        Location baseCenter = getArenaLocation();
        if (baseCenter == null) {
            plugin.getMessageUtils().sendRaw(challenger, "&cМир арены &e" +
                plugin.getConfig().getString("arena.world", "dragon_arena") + "&c не загружен!");
            plugin.getMessageUtils().sendRaw(target,     "&cМир арены не найден.");
            return;
        }

        // --- Allocate arena slot ---
        int slot = 0;
        while (usedArenaSlots.contains(slot)) slot++;
        usedArenaSlots.add(slot);

        double spacing = plugin.getConfig().getDouble("battle.arena-spacing", 200.0);
        Location arenaCenter = baseCenter.clone().add(slot * spacing, 0, 0);

        // --- Save return positions ---
        Location cReturn = challenger.getLocation().clone();
        Location tReturn = target.getLocation().clone();

        // --- Build arena ---
        plugin.getMessageUtils().send(challenger, "battle-arena-building");
        plugin.getMessageUtils().send(target,     "battle-arena-building");

        Battle battle = new Battle(challengerUUID, targetUUID);
        battle.arenaIndex = slot;
        buildArena(arenaCenter, battle.arenaData);

        // --- Despawn companion dragons ---
        plugin.getDragonManager().despawnDragon(cDragon);
        plugin.getDragonManager().despawnDragon(tDragon);

        // --- Teleport players to stands (FIXED: stand on bleachers floor) ---
        int half = plugin.getConfig().getInt("battle.arena-size", 75) / 2;
        
        // Игроки на первом ряду трибун (строятся на floorY + 1)
        Location cStand = arenaCenter.clone().add(0, 1, -(half - 4));
        Location tStand = arenaCenter.clone().add(0, 1,  (half - 4));
        
        challenger.teleport(cStand);
        target.teleport(tStand);

        battle.challengerReturn = cReturn;
        battle.targetReturn     = tReturn;

        // --- Spawn battle dragons high above center ---
        World arenaWorld = arenaCenter.getWorld();
        double dragonY   = arenaCenter.getY() + 20;
        EnderDragon cEnt = (EnderDragon) arenaWorld.spawnEntity(
            arenaCenter.clone().add(-15, 20, 0), EntityType.ENDER_DRAGON);
        EnderDragon tEnt = (EnderDragon) arenaWorld.spawnEntity(
            arenaCenter.clone().add( 15, 20, 0), EntityType.ENDER_DRAGON);

        setupBattleDragon(cEnt, cDragon);
        setupBattleDragon(tEnt, tDragon);
        cDragon.setEntity(cEnt);
        tDragon.setEntity(tEnt);

        battle.challengerDragon = cEnt;
        battle.targetDragon     = tEnt;

        // --- BossBars ---
        String cName = plugin.getDragonManager().getDragonDisplayName(cDragon);
        String tName = plugin.getDragonManager().getDragonDisplayName(tDragon);
        battle.challengerOwnBar   = createBar("§a⚔ Ваш дракон: "   + stripColor(cName), BarColor.GREEN, BarStyle.SEGMENTED_10);
        battle.challengerEnemyBar = createBar("§c☠ Враг: "          + stripColor(tName), BarColor.RED,   BarStyle.SEGMENTED_10);
        battle.targetOwnBar       = createBar("§a⚔ Ваш дракон: "   + stripColor(tName), BarColor.GREEN, BarStyle.SEGMENTED_10);
        battle.targetEnemyBar     = createBar("§c☠ Враг: "          + stripColor(cName), BarColor.RED,   BarStyle.SEGMENTED_10);
        battle.challengerOwnBar.addPlayer(challenger);
        battle.challengerEnemyBar.addPlayer(challenger);
        battle.targetOwnBar.addPlayer(target);
        battle.targetEnemyBar.addPlayer(target);

        activeBattles.put(challengerUUID, battle);

        // --- Start smooth flight task immediately ---
        startFlightTask(battle, arenaCenter);

        // --- Intro circle (10s) → countdown → battle ---
        startIntroPause(battle, challenger, target, cDragon, tDragon, cEnt, tEnt, arenaCenter);
    }

    // =========================================================================
    //  Smooth circular flight task
    // =========================================================================

    /**
     * Runs every 4 ticks. Both dragons orbit the arena center at opposite angles.
     * Radius 20 blocks, height arenaCenter.y + 17.
     */
    private void startFlightTask(Battle battle, Location center) {
        final double radius     = 20.0;
        final double flightY    = center.getY() + 17;
        final double angleStep  = 0.07;  // radians per 4 ticks ≈ 0.35 rad/s ≈ full circle in ~18 s
        final double[] angle    = {0.0};

        battle.flightTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!activeBattles.containsKey(battle.challenger)) { cancel(); return; }

                EnderDragon cEnt = battle.challengerDragon;
                EnderDragon tEnt = battle.targetDragon;

                angle[0] += angleStep;

                moveDragonCircle(cEnt, center, radius, flightY, angle[0]);
                moveDragonCircle(tEnt, center, radius, flightY, angle[0] + Math.PI);

                // Sparkle trail particles
                spawnTrailParticles(cEnt);
                spawnTrailParticles(tEnt);
            }
        }.runTaskTimer(plugin, 4L, 4L);
    }

    private void moveDragonCircle(EnderDragon dragon, Location center,
                                   double radius, double flightY, double angle) {
        if (dragon == null || dragon.isDead()) return;

        double tx = center.getX() + radius * Math.cos(angle);
        double tz = center.getZ() + radius * Math.sin(angle);

        // Smooth yaw facing direction of travel (tangent)
        double tangentX = -Math.sin(angle);
        double tangentZ =  Math.cos(angle);
        float  yaw = (float) (Math.toDegrees(Math.atan2(-tangentX, tangentZ)) + 180f);

        Location target = new Location(center.getWorld(), tx, flightY, tz, yaw, 0f);
        safeMoveDragon(dragon, target);
    }

    private void safeMoveDragon(EnderDragon dragon, Location target) {
        if (dragon == null || target.getWorld() == null) return;
        Chunk c = target.getChunk();
        if (!c.isLoaded()) c.load();
        try {
            dragon.teleport(target);
            dragon.setVelocity(new Vector(0, 0, 0));
            dragon.setFallDistance(0);
        } catch (Throwable ignored) {}
    }

    private void spawnTrailParticles(EnderDragon dragon) {
        if (dragon == null || dragon.isDead()) return;
        Location loc = dragon.getLocation();
        try {
            loc.getWorld().spawnParticle(Particle.DRAGON_BREATH,
                loc.getX(), loc.getY() + 1, loc.getZ(), 6, 1.2, 0.8, 1.2, 0.02);
        } catch (Throwable ignored) {
            try {
                loc.getWorld().spawnParticle(Particle.FLAME,
                    loc.getX(), loc.getY() + 1, loc.getZ(), 6, 1.2, 0.8, 1.2, 0.02);
            } catch (Throwable ignored2) {}
        }
    }

    // =========================================================================
    //  Intro pause (10 seconds of circling before countdown)
    // =========================================================================

    private void startIntroPause(Battle battle, Player challenger, Player target,
                                  Dragon cDragon, Dragon tDragon,
                                  EnderDragon cEnt, EnderDragon tEnt, Location center) {
        String introMsg = plugin.getConfig().getString("messages.battle-intro",
            "&6Драконы готовятся к бою! Бой начнётся через 10 секунд...");
        if (challenger.isOnline()) challenger.sendMessage(plugin.getMessageUtils().colorize(introMsg));
        if (target.isOnline())     target.sendMessage(plugin.getMessageUtils().colorize(introMsg));

        // Roar at start of intro
        center.getWorld().playSound(center, Sound.ENTITY_ENDERDRAGON_GROWL, 2f, 0.7f);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!activeBattles.containsKey(battle.challenger)) { cancel(); return; }
                // After 10s intro, start 3-2-1 countdown
                startCountdown(battle, challenger, target, cDragon, tDragon, cEnt, tEnt);
            }
        }.runTaskLater(plugin, 200L);  // 200 ticks = 10 seconds
    }

    // =========================================================================
    //  3-2-1 countdown
    // =========================================================================

    private void startCountdown(Battle battle, Player challenger, Player target,
                                 Dragon cDragon, Dragon tDragon,
                                 EnderDragon cEnt, EnderDragon tEnt) {
        new BukkitRunnable() {
            int count = 3;
            @Override
            public void run() {
                if (!activeBattles.containsKey(battle.challenger)) { cancel(); return; }
                if (count > 0) {
                    String msg = plugin.getMessageUtils().get("battle-countdown",
                        "{count}", String.valueOf(count));
                    if (challenger.isOnline()) challenger.sendMessage(msg);
                    if (target.isOnline())     target.sendMessage(msg);
                    sendWatcherMessage(battle, msg);
                    Location cLoc = cEnt.getLocation();
                    cEnt.getWorld().playSound(cLoc, Sound.BLOCK_NOTE_BASS, 1f, 1f);
                    count--;
                } else {
                    cancel();
                    launchBattle(battle, challenger, target, cDragon, tDragon, cEnt, tEnt);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // =========================================================================
    //  Launch battle (AI + bossbar polling)
    // =========================================================================

    private void launchBattle(Battle battle, Player challenger, Player target,
                               Dragon cDragon, Dragon tDragon,
                               EnderDragon cEnt, EnderDragon tEnt) {
        if (!activeBattles.containsKey(battle.challenger)) return;

        plugin.getMessageUtils().send(challenger, "battle-start");
        plugin.getMessageUtils().send(target,     "battle-start");
        sendWatcherMessage(battle, plugin.getMessageUtils().get("battle-start"));
        cEnt.getWorld().playSound(cEnt.getLocation(), Sound.ENTITY_ENDERDRAGON_GROWL, 2f, 0.8f);

        int aiInterval = plugin.getConfig().getInt("battle.ai-tick-interval", 15);

        battle.aiTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!activeBattles.containsKey(battle.challenger)) { cancel(); return; }
                if (cEnt.isDead() || tEnt.isDead()) { cancel(); return; }
                ai.tick(battle);
            }
        }.runTaskTimer(plugin, aiInterval, aiInterval);

        battle.bossBarTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!activeBattles.containsKey(battle.challenger)) { cancel(); return; }
                updateBossBar(battle.challengerOwnBar,   cEnt);
                updateBossBar(battle.challengerEnemyBar, tEnt);
                updateBossBar(battle.targetOwnBar,       tEnt);
                updateBossBar(battle.targetEnemyBar,     cEnt);
                if (cEnt.isDead() || tEnt.isDead()) {
                    boolean challengerWon = tEnt.isDead() && !cEnt.isDead();
                    endBattle(battle, cDragon, tDragon, challengerWon);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // =========================================================================
    //  End battle
    // =========================================================================

    private void endBattle(Battle battle, Dragon cDragon, Dragon tDragon, boolean challengerWon) {
        activeBattles.remove(battle.challenger);

        Player challenger = Bukkit.getPlayer(battle.challenger);
        Player target     = Bukkit.getPlayer(battle.target);

        Dragon winner   = challengerWon ? cDragon : tDragon;
        Dragon loser    = challengerWon ? tDragon : cDragon;
        Player winnerPl = challengerWon ? challenger : target;
        Player loserPl  = challengerWon ? target : challenger;
        Location winReturn = challengerWon ? battle.challengerReturn : battle.targetReturn;
        Location losReturn = challengerWon ? battle.targetReturn : battle.challengerReturn;

        if (battle.aiTask      != null) battle.aiTask.cancel();
        if (battle.bossBarTask != null) battle.bossBarTask.cancel();
        if (battle.flightTask  != null) battle.flightTask.cancel();

        removeBars(battle);
        ai.clearCooldowns(battle.challenger);
        ai.clearCooldowns(battle.target);

        if (battle.challengerDragon != null && !battle.challengerDragon.isDead())
            battle.challengerDragon.remove();
        if (battle.targetDragon != null && !battle.targetDragon.isDead())
            battle.targetDragon.remove();
        cDragon.setEntity(null);
        tDragon.setEntity(null);

        long winExp      = plugin.getConfig().getLong("battle-win-exp", 50);
        long winCoins    = plugin.getConfig().getLong("battle-win-coins", 100);
        long recoveryMin = plugin.getConfig().getLong("recovery-time", 5);

        // --- Winner ---
        plugin.getDragonManager().addExperience(winner, winExp);
        if (winnerPl != null && winnerPl.isOnline()) {
            plugin.getMessageUtils().send(winnerPl, "battle-won",
                "{exp}", String.valueOf(winExp), "{coins}", String.valueOf(winCoins));
            winnerPl.getWorld().playSound(winnerPl.getLocation(),
                Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 1.0f);
            winnerPl.getWorld().spawnParticle(Particle.FIREWORKS_SPARK,
                winnerPl.getLocation().add(0, 1, 0), 60, 1.5, 2.0, 1.5, 0.15);
            if (winReturn != null) winnerPl.teleport(winReturn);
            // Spawn dragon after teleport to return location
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (winnerPl.isOnline() && !winner.isRecovering())
                    plugin.getDragonManager().spawnDragonForPlayer(winnerPl, winner);
            }, 5L);
        }

        // --- Loser ---
        long recoveryEnd = System.currentTimeMillis() + recoveryMin * 60_000L;
        loser.setRecoveryEndTime(recoveryEnd);
        plugin.getDataManager().saveDragon(loser);
        if (loserPl != null && loserPl.isOnline()) {
            plugin.getMessageUtils().send(loserPl, "battle-lost", "{time}", String.valueOf(recoveryMin));
            loserPl.getWorld().playSound(loserPl.getLocation(),
                Sound.ENTITY_ENDERDRAGON_DEATH, 1.5f, 0.8f);
            if (losReturn != null) loserPl.teleport(losReturn);
        }

        // --- Notify and return watchers, then clean arena after 3 s ---
        returnWatchers(battle);
        final int slot = battle.arenaIndex;
        final ArenaData data = battle.arenaData;
        Location cleanCenter = getArenaSlotCenter(slot);
        new BukkitRunnable() {
            @Override public void run() {
                if (cleanCenter != null) clearArena(data, cleanCenter.getWorld());
                usedArenaSlots.remove(slot);
            }
        }.runTaskLater(plugin, 60L);  // 3 seconds

        // --- Loser recovery ---
        new BukkitRunnable() {
            @Override public void run() {
                loser.setRecoveryEndTime(0);
                plugin.getDataManager().saveDragon(loser);
                Player loserOnline = Bukkit.getPlayer(loser.getOwnerUUID());
                if (loserOnline != null && loserOnline.isOnline()) {
                    plugin.getDragonManager().spawnDragonForPlayer(loserOnline, loser);
                    plugin.getMessageUtils().sendRaw(loserOnline, "&aВаш дракон восстановился!");
                }
            }
        }.runTaskLater(plugin, recoveryMin * 60 * 20L);
    }

    // =========================================================================
    //  Arena construction & cleanup
    // =========================================================================

    /**
     * Builds the arena and records every block placed so it can be cleared later.
     */
    private void buildArena(Location center, ArenaData data) {
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int size    = plugin.getConfig().getInt("battle.arena-size", 75);
        int wallH   = plugin.getConfig().getInt("battle.arena-wall-height", 22);
        int bleach  = plugin.getConfig().getInt("battle.bleacher-rows", 3);
        int half    = size / 2;

        // ========== ПОЛ АРЕНЫ (игровая площадка) ==========
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                placeBlock(world, cx + x, cy, cz + z, Material.OBSIDIAN, data);
            }
        }

        // Barrier walls (4 sides)
        for (int y = 0; y <= wallH; y++) {
            for (int i = -half; i <= half; i++) {
                placeBlock(world, cx + i, cy + y, cz - half, Material.BARRIER, data);
                placeBlock(world, cx + i, cy + y, cz + half, Material.BARRIER, data);
                placeBlock(world, cx - half, cy + y, cz + i, Material.BARRIER, data);
                placeBlock(world, cx + half, cy + y, cz + i, Material.BARRIER, data);
            }
        }

        // Quartz bleachers (north and south inside walls)
        for (int row = 0; row < bleach; row++) {
            int stepY_n   = cy + 1 + row;   // трибуны поднимаются
            int stepY_s   = cy + 1 + row;
            int stepZ_n   = cz - half + 1 + row;
            int stepZ_s   = cz + half - 1 - row;
            for (int x = -half + 1; x <= half - 1; x++) {
                placeBlock(world, cx + x, stepY_n, stepZ_n, Material.QUARTZ_BLOCK, data);
                placeBlock(world, cx + x, stepY_s, stepZ_s, Material.QUARTZ_BLOCK, data);
            }
        }

        // ========== ПОЛ ПОД ТРИБУНАМИ (чтобы игроки не падали) ==========
        for (int y = cy; y <= cy + bleach; y++) {
            for (int row = 0; row < bleach; row++) {
                int z_north = cz - half + 1 + row;
                int z_south = cz + half - 1 - row;
                for (int x = -half + 1; x <= half - 1; x++) {
                    if (y == cy) {
                        // Самый нижний слой — прочный блок
                        placeBlock(world, cx + x, y, z_north, Material.STONE, data);
                        placeBlock(world, cx + x, y, z_south, Material.STONE, data);
                    }
                }
            }
        }

        // Corner pillars
        for (int corner = 0; corner < 4; corner++) {
            int sx = (corner < 2) ? cx - half : cx + half;
            int sz = (corner % 2 == 0) ? cz - half : cz + half;
            for (int y = 0; y <= wallH + 3; y++)
                placeBlock(world, sx, cy + y, sz, Material.LOG_2, data);
        }
    }

    @SuppressWarnings("deprecation")
    private void placeBlock(World world, int x, int y, int z, Material mat, ArenaData data) {
        try {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType() == mat) return;
            data.record(world, x, y, z);
            b.setType(mat, false);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void clearArena(ArenaData data, World world) {
        for (int[] rec : data.placed) {
            try {
                Block b = world.getBlockAt(rec[0], rec[1], rec[2]);
                b.setType(Material.AIR, false);
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    //  Watcher system
    // =========================================================================

    public void watchBattle(Player watcher, UUID challengerUUID) {
        Battle battle = activeBattles.get(challengerUUID);
        if (battle == null) {
            plugin.getMessageUtils().sendRaw(watcher, "&cЭта битва уже завершилась.");
            return;
        }

        Location arenaCenter = getArenaSlotCenter(battle.arenaIndex);
        if (arenaCenter == null) {
            plugin.getMessageUtils().sendRaw(watcher, "&cНе могу найти арену битвы.");
            return;
        }

        // Store return location
        battle.watcherReturns.put(watcher.getUniqueId(), watcher.getLocation().clone());
        battle.watchers.add(watcher.getUniqueId());
        watcherBattleMap.put(watcher.getUniqueId(), challengerUUID);

        // Teleport to north bleacher row 1 seat
        int half = plugin.getConfig().getInt("battle.arena-size", 75) / 2;
        Location seat = arenaCenter.clone().add(0, 1, -(half - 2));
        watcher.teleport(seat);

        // BossBars for watcher too
        battle.challengerOwnBar.addPlayer(watcher);
        battle.challengerEnemyBar.addPlayer(watcher);

        plugin.getMessageUtils().sendRaw(watcher, "&aВы наблюдаете за битвой! Не вмешивайтесь.");
    }

    public boolean isWatcher(UUID playerUUID) {
        return watcherBattleMap.containsKey(playerUUID);
    }

    public void removeWatcher(UUID watcherUUID) {
        UUID challengerUUID = watcherBattleMap.remove(watcherUUID);
        if (challengerUUID == null) return;
        Battle battle = activeBattles.get(challengerUUID);
        if (battle != null) {
            battle.watchers.remove(watcherUUID);
            battle.watcherReturns.remove(watcherUUID);
            Player watcherOnline = Bukkit.getPlayer(watcherUUID);
            if (watcherOnline != null) {
                if (battle.challengerOwnBar   != null) battle.challengerOwnBar.removePlayer(watcherOnline);
                if (battle.challengerEnemyBar != null) battle.challengerEnemyBar.removePlayer(watcherOnline);
            }
        }
    }

    private void returnWatchers(Battle battle) {
        for (UUID watcherId : new HashSet<>(battle.watchers)) {
            Player watcher = Bukkit.getPlayer(watcherId);
            Location ret   = battle.watcherReturns.get(watcherId);
            if (watcher != null && watcher.isOnline() && ret != null) {
                watcher.teleport(ret);
                plugin.getMessageUtils().sendRaw(watcher, "&6Битва завершена. Вы возвращены на своё место.");
            }
            watcherBattleMap.remove(watcherId);
        }
        battle.watchers.clear();
        battle.watcherReturns.clear();
    }

    private void sendWatcherMessage(Battle battle, String msg) {
        for (UUID watcherId : battle.watchers) {
            Player w = Bukkit.getPlayer(watcherId);
            if (w != null && w.isOnline()) w.sendMessage(msg);
        }
    }

    // =========================================================================
    //  Dodge /dr dodge
    // =========================================================================

    public boolean doDodge(Player player, String direction) {
        long now   = System.currentTimeMillis();
        long cdMs  = plugin.getConfig().getLong("battle.dodge-cooldown-seconds", 5) * 1000L;
        Long last  = dodgeCooldowns.get(player.getUniqueId());
        if (last != null && now - last < cdMs) {
            long rem = (cdMs - (now - last)) / 1000 + 1;
            plugin.getMessageUtils().send(player, "dodge-cooldown", "{time}", String.valueOf(rem));
            return false;
        }
        dodgeCooldowns.put(player.getUniqueId(), now);

        double strength = plugin.getConfig().getDouble("battle.dodge-strength", 1.6);
        float  yaw      = player.getLocation().getYaw();
        double yawRad   = Math.toRadians(yaw);
        String dirKey;
        Vector dodgeVec;

        switch (direction.toLowerCase()) {
            case "left":
                dodgeVec = new Vector(-Math.cos(yawRad + Math.PI / 2), 0.4,
                    -Math.sin(yawRad + Math.PI / 2)).normalize().multiply(strength);
                dirKey = "влево"; break;
            case "right":
                dodgeVec = new Vector(-Math.cos(yawRad - Math.PI / 2), 0.4,
                    -Math.sin(yawRad - Math.PI / 2)).normalize().multiply(strength);
                dirKey = "вправо"; break;
            case "up":
                dodgeVec = new Vector(0, 1.0, 0).multiply(strength * 0.9);
                dirKey = "вверх"; break;
            default:
                plugin.getMessageUtils().sendRaw(player, "&cНеверное направление! Используйте left, right или up.");
                return false;
        }
        player.setVelocity(dodgeVec);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMEN_TELEPORT, 1f, 1.3f);
        player.getWorld().spawnParticle(Particle.CLOUD,
            player.getLocation().add(0, 1, 0), 15, 0.4, 0.4, 0.4, 0.05);
        plugin.getMessageUtils().send(player, "dodge-used", "{dir}", dirKey);
        return true;
    }

    // =========================================================================
    //  Arena location helpers
    // =========================================================================

    private Location getArenaLocation() {
        String worldName = plugin.getConfig().getString("arena.world", "dragon_arena");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        double x = plugin.getConfig().getDouble("arena.x", 0.5);
        double y = plugin.getConfig().getDouble("arena.y", 100.0);
        double z = plugin.getConfig().getDouble("arena.z", 0.5);
        return new Location(world, x, y, z);
    }

    private Location getArenaSlotCenter(int slot) {
        Location base = getArenaLocation();
        if (base == null) return null;
        double spacing = plugin.getConfig().getDouble("battle.arena-spacing", 200.0);
        return base.clone().add(slot * spacing, 0, 0);
    }

    // =========================================================================
    //  BossBar helpers
    // =========================================================================

    private BossBar createBar(String title, BarColor color, BarStyle style) {
        BossBar bar = Bukkit.createBossBar(title, color, style);
        bar.setProgress(1.0);
        bar.setVisible(true);
        return bar;
    }

    private void updateBossBar(BossBar bar, EnderDragon dragon) {
        if (bar == null || dragon == null) return;
        if (dragon.isDead()) {
            bar.setProgress(0.0);
        } else {
            double progress = Math.max(0.0, Math.min(1.0, dragon.getHealth() / dragon.getMaxHealth()));
            bar.setProgress(progress);
        }
    }

    private void removeBars(Battle battle) {
        if (battle.challengerOwnBar   != null) { battle.challengerOwnBar.removeAll();   battle.challengerOwnBar.setVisible(false); }
        if (battle.challengerEnemyBar != null) { battle.challengerEnemyBar.removeAll(); battle.challengerEnemyBar.setVisible(false); }
        if (battle.targetOwnBar       != null) { battle.targetOwnBar.removeAll();       battle.targetOwnBar.setVisible(false); }
        if (battle.targetEnemyBar     != null) { battle.targetEnemyBar.removeAll();     battle.targetEnemyBar.setVisible(false); }
    }

    // =========================================================================
    //  Dragon setup
    // =========================================================================

    private void setupBattleDragon(EnderDragon entity, Dragon dragon) {
        double maxHp = plugin.getDragonManager().getMaxHealth(dragon);
        entity.setMaxHealth(maxHp);
        entity.setHealth(Math.min(dragon.getCurrentHealth(), maxHp));
        entity.setCustomName(ChatColor.translateAlternateColorCodes('&',
            plugin.getDragonManager().getDragonDisplayName(dragon)));
        entity.setCustomNameVisible(true);
        entity.setRemoveWhenFarAway(false);
    }

    // =========================================================================
    //  Utility
    // =========================================================================

    private static String stripColor(String s) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s));
    }

    public boolean isInBattle(UUID uuid) {
        for (Battle b : activeBattles.values())
            if (b.challenger.equals(uuid) || b.target.equals(uuid)) return true;
        return false;
    }

    public boolean hasPendingRequest(UUID targetUUID) {
        return pendingRequests.containsValue(targetUUID);
    }

    /** Returns the Battle UUID (challenger key) for a given participant, or null. */
    public UUID getBattleKey(UUID participantUUID) {
        for (Map.Entry<UUID, Battle> e : activeBattles.entrySet()) {
            Battle b = e.getValue();
            if (b.challenger.equals(participantUUID) || b.target.equals(participantUUID))
                return e.getKey();
        }
        return null;
    }

    public Battle getBattle(UUID challengerUUID) {
        return activeBattles.get(challengerUUID);
    }

    public Collection<Battle> getActiveBattles() {
        return activeBattles.values();
    }
}