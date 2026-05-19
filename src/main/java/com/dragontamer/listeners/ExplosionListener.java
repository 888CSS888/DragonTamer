import com.dragontamer.DragonTamerPlugin;
import com.dragontamer.data.Dragon;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;

public class ExplosionListener implements Listener {
    private final DragonTamerPlugin plugin;

    public ExplosionListener(DragonTamerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();

        if (entity instanceof EnderDragon) {
            EnderDragon dragon = (EnderDragon) entity;
            if (isDragonManaged(dragon)) {
                event.setCancelled(true);
                event.blockList().clear();
                return;
            }
        }

        if (entity instanceof Fireball) {
            Fireball fireball = (Fireball) entity;
            if (fireball.getShooter() instanceof EnderDragon) {
                EnderDragon shooter = (EnderDragon) fireball.getShooter();
                if (isDragonManaged(shooter)) {
                    event.setCancelled(true);
                    event.blockList().clear();
                }
            }
        }
    }

    private boolean isDragonManaged(EnderDragon dragon) {
        for (Dragon d : plugin.getDragonManager().getAllDragons()) {
            if (dragon.equals(d.getEntity())) return true;
        }
        return false;
    }
}
