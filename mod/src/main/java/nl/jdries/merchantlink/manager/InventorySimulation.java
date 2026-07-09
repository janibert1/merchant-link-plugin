package nl.jdries.merchantlink.manager;

import net.minecraft.item.ItemStack;

/**
 * In-memory simulation of a fixed-size item container, ported from the Paper
 * plugin's {@code InventorySimulation}. Used to dry-run a purchase (stock,
 * buyer inventory space, return-chest space) before touching any real
 * inventory, so a partially-applied trade can never happen.
 */
public final class InventorySimulation {
    private final ItemStack[] contents;

    private InventorySimulation(ItemStack[] contents) {
        this.contents = contents;
    }

    public static InventorySimulation copyOf(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null || source[i].isEmpty() ? ItemStack.EMPTY : source[i].copy();
        }
        return new InventorySimulation(copy);
    }

    public ItemStack[] contents() {
        return contents;
    }

    public boolean canFit(ItemStack item) {
        return copyOf(contents).add(item);
    }

    public boolean add(ItemStack item) {
        int remaining = item.getCount();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, item)) {
                continue;
            }
            int room = stack.getMaxCount() - stack.getCount();
            int moved = Math.min(room, remaining);
            stack.setCount(stack.getCount() + moved);
            remaining -= moved;
            if (remaining <= 0) {
                return true;
            }
        }
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].isEmpty()) {
                continue;
            }
            ItemStack clone = item.copy();
            int moved = Math.min(clone.getMaxCount(), remaining);
            clone.setCount(moved);
            contents[i] = clone;
            remaining -= moved;
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    public boolean canRemove(ItemStack item) {
        return copyOf(contents).remove(item);
    }

    public boolean remove(ItemStack item) {
        int remaining = item.getCount();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, item)) {
                continue;
            }
            int moved = Math.min(stack.getCount(), remaining);
            stack.setCount(stack.getCount() - moved);
            if (stack.getCount() <= 0) {
                contents[i] = ItemStack.EMPTY;
            }
            remaining -= moved;
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    /** Removes up to {@code amount} of items matching {@code target}, returns how many were actually removed. */
    public int removeMatching(ItemStack target, int amount) {
        int removed = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, target)) {
                continue;
            }
            int moved = Math.min(stack.getCount(), amount - removed);
            stack.setCount(stack.getCount() - moved);
            if (stack.getCount() <= 0) {
                contents[i] = ItemStack.EMPTY;
            }
            removed += moved;
            if (removed >= amount) {
                return removed;
            }
        }
        return removed;
    }

    public static ItemStack multiplied(ItemStack stack, int multiplier) {
        if (stack == null || stack.isEmpty() || multiplier <= 0) {
            return null;
        }
        ItemStack clone = stack.copy();
        clone.setCount(stack.getCount() * multiplier);
        return clone;
    }
}
