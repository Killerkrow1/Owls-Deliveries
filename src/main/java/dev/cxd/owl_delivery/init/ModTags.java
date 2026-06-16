package dev.cxd.owl_delivery.init;

import dev.cxd.owl_delivery.OwlsDeliveries;
import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

public class ModTags {

    public static class Items {
        public static final TagKey<Item> BUNDLE =
                createTag("bundle");

        private static TagKey<Item> createTag(String name) {
            return TagKey.of(RegistryKeys.ITEM, new Identifier(OwlsDeliveries.MOD_ID, name));
        }
    }
}