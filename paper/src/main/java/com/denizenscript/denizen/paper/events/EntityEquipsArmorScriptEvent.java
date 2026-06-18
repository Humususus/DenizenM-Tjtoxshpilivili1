package com.denizenscript.denizen.paper.events;

import com.denizenscript.denizen.objects.EntityTag;
import com.denizenscript.denizen.utilities.implementation.BukkitScriptEntryData;
import com.denizenscript.denizen.events.BukkitScriptEvent;
import com.denizenscript.denizen.objects.ItemTag;
import com.denizenscript.denizen.objects.PlayerTag;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntryData;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class EntityEquipsArmorScriptEvent extends BukkitScriptEvent implements Listener {

    // <--[event]
    // @Events
    // <entity> equips|unequips armor|helmet|chestplate|leggings|boots|body
    // <entity> equips|unequips <item>
    //
    // @Plugin Paper
    //
    // @Group Paper
    //
    // @Triggers when an entity equips or unequips an item in an armor or body slot.
    //
    // @Context
    // <context.entity> returns the EntityTag that equipped or unequipped the item.
    // <context.new_item> returns the ItemTag that is now in the slot.
    // <context.old_item> returns the ItemTag that used to be in the slot.
    // <context.is_swap> returns true if an item was swapped for another (neither old nor new is air).
    // <context.slot> returns the name of the slot (helmet, chestplate, leggings, boots, body).
    //
    // @Player When the entity is a player.
    //
    // -->

    public static final HashMap<String, EquipmentSlot> slotsByName = new HashMap<>();
    public static final HashMap<EquipmentSlot, String> namesBySlot = new HashMap<>();

    static {
        registerSlot("helmet", EquipmentSlot.HEAD);
        registerSlot("chestplate", EquipmentSlot.CHEST);
        registerSlot("leggings", EquipmentSlot.LEGS);
        registerSlot("boots", EquipmentSlot.FEET);
        registerSlot("body", EquipmentSlot.BODY);
    }

    public static void registerSlot(String name, EquipmentSlot slot) {
        slotsByName.put(name, slot);
        namesBySlot.put(slot, name);
    }

    public EntityEquipsArmorScriptEvent() {
        registerCouldMatcher("<entity> (equips|unequips) armor|helmet|chestplate|leggings|boots|body");
        registerCouldMatcher("<entity> (equips|unequips) <item>");
    }

    public ItemTag oldItem;
    public ItemTag newItem;
    public EquipmentSlot slot;
    public PlayerTag player;
    public EntityTag entity;

    private static final Set<EquipmentSlot> VALID_ARMOR_SLOTS = Set.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.BODY);

    @Override
    public boolean matches(ScriptPath path) {
        String action = path.eventArgLowerAt(1);
        String itemCompare = path.eventArgLowerAt(2);

        if (!path.tryArgObject(0, entity)) {
            return false;
        }

        boolean isUnequip = action.equals("unequips");
        ItemTag relevantItem = isUnequip ? oldItem : newItem;
        EquipmentSlot slotType = slotsByName.get(itemCompare);
        if (slotType != null) {
            if (slot != slotType) {
                return false;
            }
            if (relevantItem.getMaterial().getMaterial() == Material.AIR) {
                return false;
            }
        }
        else if (itemCompare.equals("armor")) {
            if (!VALID_ARMOR_SLOTS.contains(slot)) {
                return false;
            }
            if (relevantItem.getMaterial().getMaterial() == Material.AIR) {
                return false;
            }
        }
        else {
            if (relevantItem.getMaterial().getMaterial() == Material.AIR) {
                return false;
            }
            if (!relevantItem.tryAdvancedMatcher(itemCompare, path.context)) {
                return false;
            }
        }

        return super.matches(path);
    }

    @Override
    public ScriptEntryData getScriptEntryData() {
        return new BukkitScriptEntryData(player, null);
    }

    @Override
    public ObjectTag getContext(String name) {
        return switch (name) {
            case "entity" -> entity;
            case "new_item" -> newItem;
            case "old_item" -> oldItem;
            case "slot" -> new ElementTag(namesBySlot.get(slot));
            default -> super.getContext(name);
        };
    }

    @EventHandler
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (EntityTag.isCitizensNPC(event.getEntity())) {
            return;
        }

        entity = new EntityTag(event.getEntity());
        player = (event.getEntity() instanceof Player p) ? new PlayerTag(p) : null;
        for (Map.Entry<EquipmentSlot, EntityEquipmentChangedEvent.EquipmentChange> entry : event.getEquipmentChanges().entrySet()) {
            if (!VALID_ARMOR_SLOTS.contains(entry.getKey())) {
                continue;
            }

            slot = entry.getKey();
            oldItem = new ItemTag(entry.getValue().oldItem());
            newItem = new ItemTag(entry.getValue().newItem());
            fire(event);
        }
    }
}