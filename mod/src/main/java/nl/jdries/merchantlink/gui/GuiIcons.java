package nl.jdries.merchantlink.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import nl.jdries.merchantlink.data.LinkKind;
import nl.jdries.merchantlink.data.LinkedChest;
import nl.jdries.merchantlink.manager.MerchantManager;

import java.util.List;

/** Small helper for building the read-only display {@link ItemStack}s used across the mod's menus. */
public final class GuiIcons {
    private GuiIcons() {
    }

    public static ItemStack named(Item item, Text name, List<Text> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, name);
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    public static Text gray(String s) {
        return Text.literal(s).formatted(Formatting.GRAY);
    }

    public static Text yellow(String s) {
        return Text.literal(s).formatted(Formatting.YELLOW);
    }

    public static Text red(String s) {
        return Text.literal(s).formatted(Formatting.RED);
    }

    public static Text gold(String s) {
        return Text.literal(s).formatted(Formatting.GOLD);
    }

    public static ItemStack linkedChestIcon(MerchantManager manager, LinkedChest chest, String actionLine) {
        Item icon = chest.kind == LinkKind.RETURN ? net.minecraft.item.Items.ENDER_CHEST : net.minecraft.item.Items.CHEST;
        return named(icon, gold(chest.kind.display + " Chest"), List.of(
            gray(chest.worldKey + " " + chest.pos.getX() + ", " + chest.pos.getY() + ", " + chest.pos.getZ()),
            yellow(actionLine)
        ));
    }
}
