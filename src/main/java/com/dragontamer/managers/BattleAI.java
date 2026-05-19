import com.dragontamer.DragonTamerPlugin;
import com.dragontamer.data.Dragon;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.SmallFireball;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BattleAI — искусственный интеллект дракона в битве.
 *
 * 3 типа атак с индивидуальными откатами:
 *  1. FIRE_CANNON  — одиночный огненный шар в стиле Гаста (точный выстрел по цели)
 *  2. FIREBALL_FAN — веер из 5 шаров (разброс ±30°)
 *  3. MELEE        — ближний удар лапой/хвостом (телепорт к цели + прямой урон)
 */
public class BattleAI {

    public enum AttackType {
        FIRE_CANNON,
        FIREBALL_FAN,
        MELEE
    }

    private final DragonTamerPlugin plugin;

    /** cooldowns[attackerOwnerUUID][attackType] = System.currentTimeMillis() последней атаки */
    private final Map<UUID, Map<AttackType, Long>> cooldowns = new HashMap<>();

    public BattleAI(DragonTamerPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    //  Главный метод — вызывается каждые N тиков из BattleManager
    // -------------------------------------------------------------------------

    /**
     * Обновляет ИИ для обоих драконов в битве: каждый выбирает и выполняет атаку.
     */
    public void tick(BattleManager.Battle battle) {
        Dragon cDragon = plugin.getDragonManager().getDragon(battle.challenger);
        Dragon tDragon = plugin.getDragonManager().getDragon(battle.target);
        if (cDragon == null || tDragon == null) return;

        EnderDragon cEnt = battle.challengerDragon;
        EnderDragon tEnt = battle.targetDragon;

        if (cEnt == null || cEnt.isDead() || tEnt == null || tEnt.isDead()) return;

        tickDragonAI(cDragon, cEnt, tDragon, tEnt, battle.challenger);
        tickDragonAI(tDragon, tEnt, cDragon, cEnt, battle.target);
    }

    // -------------------------------------------------------------------------
    //  Логика выбора и выполнения атаки
    // -------------------------------------------------------------------------

    private void tickDragonAI(Dragon attacker, EnderDragon attackerEnt,
                               Dragon victim, EnderDragon victimEnt,
                               UUID attackerOwner) {
        if (attackerEnt.isDead() || victimEnt.isDead()) return;

        long now = System.currentTimeMillis();
        Map<AttackType, Long> cd = cooldowns.computeIfAbsent(attackerOwner, k -> new HashMap<>());

        long cdFireCannon  = plugin.getConfig().getLong("battle.ai-cooldown-fire-cannon", 4000);
        long cdFan         = plugin.getConfig().getLong("battle.ai-cooldown-fireball-fan", 8000);
        long cdMelee       = plugin.getConfig().getLong("battle.ai-cooldown-melee", 3000);
        double meleeRange  = plugin.getConfig().getDouble("battle.melee-range", 18.0);

        // Проверяем доступные атаки
        boolean canFireCannon = (now - cd.getOrDefault(AttackType.FIRE_CANNON, 0L)) >= cdFireCannon;
        boolean canFan        = (now - cd.getOrDefault(AttackType.FIREBALL_FAN, 0L)) >= cdFan;
        boolean canMelee      = (now - cd.getOrDefault(AttackType.MELEE, 0L)) >= cdMelee;

        double dist = attackerEnt.getLocation().distance(victimEnt.getLocation());

        // Выбираем атаку: приоритет — ближний бой если рядом, иначе случайный дальний
        AttackType chosen = null;

        if (canMelee && dist <= meleeRange) {
            chosen = AttackType.MELEE;
        } else {
            // Случайный выбор из доступных дальних атак
            if (canFireCannon && canFan) {
                chosen = ThreadLocalRandom.current().nextBoolean()
                    ? AttackType.FIRE_CANNON : AttackType.FIREBALL_FAN;
            } else if (canFireCannon) {
                chosen = AttackType.FIRE_CANNON;
            } else if (canFan) {
                chosen = AttackType.FIREBALL_FAN;
            } else if (canMelee) {
                chosen = AttackType.MELEE;
            }
        }

        if (chosen == null) return;

        // Выполняем атаку
        cd.put(chosen, now);
        double baseDmg = plugin.getDragonManager().getDamage(attacker);

        switch (chosen) {
            case FIRE_CANNON:
                executeFireCannon(attackerEnt, victimEnt, baseDmg);
                break;
            case FIREBALL_FAN:
                executeFireballFan(attackerEnt, victimEnt, baseDmg);
                break;
            case MELEE:
                executeMelee(attacker, attackerEnt, victim, victimEnt, baseDmg);
                break;
        }
    }

    // -------------------------------------------------------------------------
    //  Атака 1: Огненная пушка (Ghast-стиль)
    // -------------------------------------------------------------------------

    private void executeFireCannon(EnderDragon attacker, EnderDragon victim, double damage) {
        Location from = attacker.getLocation().clone().add(0, 2, 0);
        Location to   = victim.getLocation().clone().add(0, 2, 0);
        World world   = from.getWorld();

        // Направление выстрела
        Vector dir = to.toVector().subtract(from.toVector()).normalize();

        SmallFireball fb = world.spawn(from, SmallFireball.class);
        fb.setShooter(attacker);
        fb.setDirection(dir.multiply(1.8));
        fb.setIsIncendiary(false);
        fb.setYield(0f); // без разрушения блоков

        // Звук рыка при выстреле
        world.playSound(from, Sound.ENTITY_GHAST_SHOOT, 1.4f, 0.9f);
        world.playSound(from, Sound.ENTITY_ENDERDRAGON_GROWL, 0.6f, 1.2f);

        // Частицы вокруг атакующего
        world.spawnParticle(Particle.FLAME, from, 20, 1.5, 1.0, 1.5, 0.08);
        world.spawnParticle(Particle.SMOKE_LARGE, from, 10, 1.0, 0.5, 1.0, 0.05);

        // Урон при попадании (упрощённый — применяем сразу + эффект через 10 тиков)
        applyDelayedHit(attacker, victim, damage * 1.0, 10L, from);
    }

    // -------------------------------------------------------------------------
    //  Атака 2: Веер из 5 шаров
    // -------------------------------------------------------------------------

    private void executeFireballFan(EnderDragon attacker, EnderDragon victim, double damage) {
        Location from = attacker.getLocation().clone().add(0, 2, 0);
        Location to   = victim.getLocation().clone().add(0, 2, 0);
        World world   = from.getWorld();

        Vector baseDir = to.toVector().subtract(from.toVector()).normalize();

        int fanCount = 5;
        double spreadAngle = 30.0; // градусы по обе стороны

        for (int i = 0; i < fanCount; i++) {
            double angleDeg = -spreadAngle + (spreadAngle * 2.0 / (fanCount - 1)) * i;
            double angleRad = Math.toRadians(angleDeg);

            // Поворот вектора по оси Y
            double cos = Math.cos(angleRad);
            double sin = Math.sin(angleRad);
            double nx = baseDir.getX() * cos - baseDir.getZ() * sin;
            double nz = baseDir.getX() * sin + baseDir.getZ() * cos;
            Vector fanDir = new Vector(nx, baseDir.getY() * 0.85, nz).normalize();

            SmallFireball fb = world.spawn(from, SmallFireball.class);
            fb.setShooter(attacker);
            fb.setDirection(fanDir.multiply(1.5));
            fb.setIsIncendiary(false);
            fb.setYield(0f);
        }

        // Звук + частицы залпа
        world.playSound(from, Sound.ENTITY_GHAST_SHOOT, 1.6f, 0.7f);
        world.playSound(from, Sound.ENTITY_ENDERDRAGON_GROWL, 0.8f, 0.8f);
        world.spawnParticle(Particle.EXPLOSION_NORMAL, from, 30, 2.0, 1.0, 2.0, 0.1);
        world.spawnParticle(Particle.FLAME, from, 40, 2.0, 1.5, 2.0, 0.12);

        // Урон по жертве (средний)
        applyDelayedHit(attacker, victim, damage * 0.7, 12L, from);
    }

    // -------------------------------------------------------------------------
    //  Атака 3: Ближний бой (лапа/хвост)
    // -------------------------------------------------------------------------

    private void executeMelee(Dragon attacker, EnderDragon attackerEnt,
                              Dragon victim, EnderDragon victimEnt, double damage) {
        Location victimLoc = victimEnt.getLocation();
        Location strikePos = victimLoc.clone().add(0, 3, 0);
        World world = victimLoc.getWorld();

        // Дракон-атакующий телепортируется к жертве
        attackerEnt.teleport(strikePos);

        // Зрелищные частицы удара
        world.spawnParticle(Particle.EXPLOSION_LARGE, victimLoc, 6, 1.5, 1.5, 1.5, 0.0);
        world.spawnParticle(Particle.CRIT, victimLoc.clone().add(0, 1, 0), 40, 1.0, 1.0, 1.0, 0.3);
        world.spawnParticle(Particle.SWEEP_ATTACK, victimLoc.clone().add(0, 2, 0), 8, 1.5, 0.5, 1.5, 0.0);
        world.spawnParticle(Particle.SMOKE_LARGE, victimLoc, 20, 1.2, 1.2, 1.2, 0.05);

        // Звук удара
        world.playSound(victimLoc, Sound.ENTITY_GENERIC_BIG_FALL, 1.8f, 0.6f);
        world.playSound(victimLoc, Sound.ENTITY_ENDERDRAGON_GROWL, 1.0f, 0.7f);
        world.playSound(victimLoc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.4f, 0.5f);

        // Прямой урон по HP дракона-жертвы
        double meleeDmgPercent = plugin.getConfig().getDouble("battle.melee-damage-percent", 0.10);
        double meleeDmg = victimEnt.getMaxHealth() * meleeDmgPercent;
        double newHp = Math.max(1.0, victimEnt.getHealth() - meleeDmg);
        victimEnt.setHealth(newHp);
    }

    // -------------------------------------------------------------------------
    //  Вспомогательные методы
    // -------------------------------------------------------------------------

    /**
     * Задержанный урон — применяется через delay тиков (имитация полёта снаряда).
     * Если жертва жива и не умерла за этот тик — наносим урон и играем эффекты.
     */
    private void applyDelayedHit(EnderDragon attacker, EnderDragon victim,
                                  double damage, long delayTicks, Location fireOrigin) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (victim == null || victim.isDead()) return;
            if (attacker == null || attacker.isDead()) return;

            double newHp = Math.max(1.0, victim.getHealth() - damage);
            victim.setHealth(newHp);

            Location hitLoc = victim.getLocation().clone().add(0, 2, 0);
            World world = hitLoc.getWorld();

            // Взрыв частиц при попадании
            world.spawnParticle(Particle.EXPLOSION_LARGE, hitLoc, 4, 1.0, 1.0, 1.0, 0.0);
            world.spawnParticle(Particle.CRIT_MAGIC, hitLoc, 25, 0.8, 0.8, 0.8, 0.2);
            world.spawnParticle(Particle.FLAME, hitLoc, 30, 1.0, 1.0, 1.0, 0.1);

            // Звук попадания
            world.playSound(hitLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.3f);

        }, delayTicks);
    }

    /**
     * Очищает данные откатов для игрока (вызывается при завершении битвы).
     */
    public void clearCooldowns(UUID ownerUUID) {
        cooldowns.remove(ownerUUID);
    }
}
