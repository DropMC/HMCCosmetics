package com.hibiscusmc.hmccosmetics.user.manager;

import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBackpackType;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import lombok.Getter;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.nms.NMSPacketBuilder;
import me.lojosho.hibiscuscommons.packets.wrapper.PacketWrapper;
import me.lojosho.hibiscuscommons.util.ServerUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UserBackpackManager {

    @Getter
    private boolean backpackHidden;
    @Getter
    private final int invisibleArmorStand;
    /**
     * Fixed for the life of the backpack, because it is the stand's team entry: a team lists anything
     * that is not a player by UUID, so respawning the stand for a new viewer under a fresh random one
     * would leave that viewer's copy outside the aura's team, and uncolored.
     */
    @Getter
    private final UUID armorStandUuid = UUID.randomUUID();
    private ArrayList<Integer> particleCloud = new ArrayList<>();
    @Getter
    private final CosmeticUser user;
    @Getter
    private final UserEntity entityManager;

    public UserBackpackManager(CosmeticUser user) {
        Location location = user.getEntity().getLocation();
        this.user = user;
        this.backpackHidden = false;
        this.invisibleArmorStand = ServerUtils.getNextEntityId(location.getWorld());
        this.entityManager = new UserEntity(user.getUniqueId());
        if (user.getEntity() != null) this.entityManager.refreshViewers(location); // Fixes an issue where a player, who somehow removes their potions, but doesn't have an entity produces an NPE (it's dumb)
    }

    public int getFirstArmorStandId() {
        return invisibleArmorStand;
    }

    public void spawnBackpack(CosmeticBackpackType cosmeticBackpackType) {
        MessagesUtil.sendDebugMessages("spawnBackpack Bukkit - Start");

        spawn(cosmeticBackpackType);
    }

    private void spawn(CosmeticBackpackType cosmeticBackpackType) {
        Location location = user.getEntity().getLocation();
        getEntityManager().setIds(List.of(invisibleArmorStand));
        getEntityManager().teleport(location);
        final List<Player> outsideViewers = getEntityManager().getViewers();

        NMSPacketBuilder packetBuilder = NMSHandlers.getHandler().getPacketBuilder();

        final List<PacketWrapper> outsideBundle = new ArrayList<>(16);
        final List<PacketWrapper> ownerBundle = new ArrayList<>(16);

        outsideBundle.addAll(HMCCPacketManager.getInvisibleArmorStand(getFirstArmorStandId(), location, armorStandUuid, user.hasAura()));

        double scaleValue = 1;
        if (user.getPlayer() != null) {
            AttributeInstance scaleAttribute = user.getPlayer().getAttribute(Attribute.SCALE);
            if (scaleAttribute != null) {
                scaleValue = scaleAttribute.getValue();
                outsideBundle.add(packetBuilder.buildEntityAttributePacket(getFirstArmorStandId(), Attribute.SCALE, scaleValue));
            }
        }

        Entity entity = user.getEntity();

        int[] passengerIDs = new int[entity.getPassengers().size() + 1];

        for (int i = 0; i < entity.getPassengers().size(); i++) {
            passengerIDs[i] = entity.getPassengers().get(i).getEntityId();
        }

        passengerIDs[passengerIDs.length - 1] = this.getFirstArmorStandId();

        if (cosmeticBackpackType.isFirstPersonCompadible()) {
            for (int i = particleCloud.size(); i < cosmeticBackpackType.getHeight(); i++) {
                int entityId = ServerUtils.getNextEntityId(location.getWorld());
                ownerBundle.addAll(HMCCPacketManager.getCloudHandleEffect(entityId, location, UUID.randomUUID()));
                //if (scaleValue != 1) ownerBundle.add(packetBuilder.buildEntityAttributePacket(entityId, Attribute.SCALE, scaleValue)); // Todo: figure out how to impl scaling, area clouds can not scale and will kick the player if sent
                this.particleCloud.add(entityId);
            }
            // Copied code from updating the backpack
            for (int i = 0; i < particleCloud.size(); i++) {
                if (i == 0) ownerBundle.add(packetBuilder.buildEntityMountPacket(entity.getEntityId(), new int[]{particleCloud.get(i)}));
                else ownerBundle.add(packetBuilder.buildEntityMountPacket(particleCloud.get(i - 1), new int[]{particleCloud.get(i)}));
            }
            ownerBundle.add(packetBuilder.buildEntityMountPacket(particleCloud.getLast(), new int[]{getFirstArmorStandId()}));
            if (!user.isHidden()) ownerBundle.add(packetBuilder.buildEntityEquipmentSlotUpdatePacket(getFirstArmorStandId(), Map.of(EquipmentSlot.HEAD, user.getUserCosmeticItem(cosmeticBackpackType, cosmeticBackpackType.getFirstPersonBackpack()))));
        }
        outsideBundle.add(packetBuilder.buildEntityEquipmentSlotUpdatePacket(getFirstArmorStandId(), Map.of(EquipmentSlot.HEAD, user.getUserCosmeticItem(cosmeticBackpackType))));
        outsideBundle.add(packetBuilder.buildEntityMountPacket(entity.getEntityId(), passengerIDs));

        NMSHandlers.getHandler().getPacketSender().sendBundle(ownerBundle, user.getPlayer());
        NMSHandlers.getHandler().getPacketSender().sendBundle(outsideBundle, outsideViewers);

        MessagesUtil.sendDebugMessages("spawnBackpack Bukkit - Finish");
    }

    public void despawnBackpack() {
        int[] existingPassengers = user.getEntity().getPassengers().stream()
                .mapToInt(Entity::getEntityId)
                .toArray();
        if (existingPassengers.length > 0) HMCCPacketManager.sendRidingPacket(user.getEntity().getEntityId(), existingPassengers, getEntityManager().getViewers());

        HMCCPacketManager.sendEntityDestroyPacket(invisibleArmorStand, getEntityManager().getViewers());
        if (particleCloud != null) {
            for (Integer entityId : particleCloud) {
                HMCCPacketManager.sendEntityDestroyPacket(entityId, getEntityManager().getViewers());
            }
            this.particleCloud = null;
        }
    }

    public void hideBackpack() {
        if (user.isHidden()) return;
        //getArmorStand().getEquipment().clear();
        backpackHidden = true;
    }

    public void showBackpack() {
        if (!backpackHidden) return;
        CosmeticBackpackType cosmeticBackpackType = (CosmeticBackpackType) user.getCosmetic(CosmeticSlot.BACKPACK);
        ItemStack item = user.getUserCosmeticItem(cosmeticBackpackType);
        //getArmorStand().getEquipment().setHelmet(item);
        backpackHidden = false;
    }

    public void setVisibility(boolean shown) {
        backpackHidden = shown;
    }

    public ArrayList<Integer> getAreaEffectEntityId() {
        return particleCloud;
    }

    public void setItem(ItemStack item) {
        HMCCPacketManager.equipmentSlotUpdate(getFirstArmorStandId(), EquipmentSlot.HEAD, item, getEntityManager().getViewers());
    }

    public void clearItems() {
        ItemStack item = new ItemStack(Material.AIR);
        HMCCPacketManager.equipmentSlotUpdate(getFirstArmorStandId(), EquipmentSlot.HEAD, item, getEntityManager().getViewers());
    }
}
