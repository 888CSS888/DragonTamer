import com.dragontamer.DragonTamerPlugin;
import com.dragontamer.data.Dragon;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;

public class EntityListener implements Listener {
    private final DragonTamerPlugin plugin;

    public EntityListener(DragonTamerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof EnderDragon)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!(victim instanceof EnderDragon)) return;
        EnderDragon dragonEntity = (EnderDragon) victim;
        Dragon dragon = getDragonByEntity(dragonEntity);
        if (dragon == null) return;
        boolean inBattle = plugin.getBattleManager().isInBattle(dragon.getOwnerUUID());
        if (!inBattle) event.setCancelled(true);
    }

    private Dragon getDragonByEntity(EnderDragon entity) {
        for (Dragon dragon : plugin.getDragonManager().getAllDragons()) {
            if (entity.equals(dragon.getEntity())) return dragon;
        }
        return null;
    }
}