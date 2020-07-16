package cn.nukkit.utils.spawners;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.entity.BaseEntity;
import cn.nukkit.entity.passive.EntityPanda;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.utils.AbstractEntitySpawner;
import cn.nukkit.utils.Spawner;
import cn.nukkit.utils.Utils;

public class PandaSpawner extends AbstractEntitySpawner {

    public PandaSpawner(Spawner spawnTask) {
        super(spawnTask);
    }

    public void spawn(Player player, Position pos, Level level) {
        if (level.getBlockIdAt((int) pos.x, (int) pos.y, (int) pos.z) != Block.GRASS ||
                pos.y > 255 || pos.y < 1 ||
                level.isNether || level.isEnd ||
                level.isAnimalSpawningAllowedByTime()){
            return;
        }

        final int biomeId = level.getBiomeId((int) pos.x, (int) pos.z);
        if (biomeId != 21 && biomeId != 168 && biomeId != 169){
            return;
        }

        BaseEntity entity = this.spawnTask.createEntity("Panda", pos.add(0, 1, 0));
        if (Utils.rand(1, 20) == 1) {
            entity.setBaby(true);
        }
    }

    @Override
    public final int getEntityNetworkId() {
        return EntityPanda.NETWORK_ID;
    }
}
