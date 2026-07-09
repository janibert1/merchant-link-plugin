package nl.jdries.merchantlink.manager;

import com.mojang.serialization.DataResult;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;

/**
 * Serializes {@link ItemStack}s to/from a self-contained string, for storage
 * in SQLite. Uses vanilla's own {@code ItemStack.CODEC} (component-aware —
 * survives enchantments, custom names, etc.) encoded to SNBT text, instead of
 * the Paper plugin's Bukkit-specific {@code BukkitObjectOutputStream}
 * approach.
 */
public final class ItemCodecs {
    private ItemCodecs() {
    }

    public static String serialize(RegistryWrapper.WrapperLookup registries, ItemStack stack) {
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);
        DataResult<NbtElement> result = ItemStack.CODEC.encodeStart(ops, stack);
        NbtElement element = result.getOrThrow();
        return element.toString();
    }

    public static ItemStack deserialize(RegistryWrapper.WrapperLookup registries, String blob) {
        if (blob == null || blob.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            NbtCompound nbt = StringNbtReader.readCompound(blob);
            RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);
            DataResult<ItemStack> result = ItemStack.CODEC.parse(ops, nbt);
            return result.getOrThrow();
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** Key used to identify a distinct listing/item-type, ignoring stack count. */
    public static String itemKey(RegistryWrapper.WrapperLookup registries, ItemStack stack) {
        return serialize(registries, stack.copyWithCount(1));
    }
}
