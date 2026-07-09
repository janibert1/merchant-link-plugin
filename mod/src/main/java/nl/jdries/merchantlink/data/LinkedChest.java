package nl.jdries.merchantlink.data;

import net.minecraft.util.math.BlockPos;

/**
 * A chest linked to a merchant profile. Unlike the Paper plugin (which caches
 * a copy of the chest's contents and syncs it on inventory open/close), this
 * mod always reads the live {@code Inventory} off the physical block entity
 * at the stored location — simpler, and always correct even if the chest was
 * modified through hoppers/other mods while nobody had it open.
 */
public final class LinkedChest {
    public final String worldKey;
    public final BlockPos pos;
    public final LinkKind kind;

    public LinkedChest(String worldKey, BlockPos pos, LinkKind kind) {
        this.worldKey = worldKey;
        this.pos = pos.toImmutable();
        this.kind = kind;
    }

    public boolean sameBlock(String otherWorldKey, BlockPos otherPos) {
        return worldKey.equals(otherWorldKey) && pos.equals(otherPos);
    }
}
