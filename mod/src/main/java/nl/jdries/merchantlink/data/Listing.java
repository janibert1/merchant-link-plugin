package nl.jdries.merchantlink.data;

import net.minecraft.item.ItemStack;

public final class Listing {
    public final String itemKey;
    public final ItemStack product;
    public ItemStack cost;

    public Listing(String itemKey, ItemStack product, ItemStack cost) {
        this.itemKey = itemKey;
        this.product = product;
        this.cost = cost;
    }

    public Listing copy() {
        return new Listing(itemKey, product.copy(), cost == null ? null : cost.copy());
    }
}
