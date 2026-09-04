package com.hibiscusmc.hmccosmetics.user;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.api.events.*;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.config.WardrobeSettings;
import com.hibiscusmc.hmccosmetics.config.section.Wardrobe;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticHolder;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.behavior.CosmeticMovementBehavior;
import com.hibiscusmc.hmccosmetics.cosmetic.behavior.CosmeticUpdateBehavior;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticArmorType;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticAuraType;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBackpackType;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBalloonType;
import com.hibiscusmc.hmccosmetics.database.UserData;
import com.hibiscusmc.hmccosmetics.hooks.misc.HookPacketEvents;
import com.hibiscusmc.hmccosmetics.hooks.misc.HookTAB;
import com.hibiscusmc.hmccosmetics.user.manager.UserBackpackManager;
import com.hibiscusmc.hmccosmetics.user.manager.UserBalloonManager;
import com.hibiscusmc.hmccosmetics.user.manager.UserWardrobeManager;
import com.hibiscusmc.hmccosmetics.util.HMCCInventoryUtils;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import lombok.Getter;
import lombok.Setter;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.util.InventoryUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;

public class CosmeticUser implements CosmeticHolder {

    /** Bukkit metadata key the network's own vanish sets on a hidden player. See {@link #isVanished()}. */
    private static final String VANISH_METADATA = "vanished";

    @Getter
    private final UUID uniqueId;
    private int taskId = -1;
    private final HashMap<CosmeticSlot, Cosmetic> playerCosmetics = new HashMap<>();
    private UserWardrobeManager userWardrobeManager;
    private UserBalloonManager userBalloonManager;
    @Getter @Nullable
    private UserBackpackManager userBackpackManager;

    // Cosmetic Settings/Toggles
    private final ArrayList<HiddenReason> hiddenReason = new ArrayList<>();
    private final HashMap<CosmeticSlot, Color> colors = new HashMap<>();

    /** Last entity flag byte sent for the aura, or null when none is applied. See {@link #refreshAuraOnStateChange()}. */
    private Byte lastAuraFlags;
    /** Whether the aura still owes a second send, to land after the server's own byte for this tick. */
    private boolean auraResendPending;
    /** Entity id registered for glow interception, or -1. Kept so it can be dropped without the player. */
    private int auraEntityId = -1;

    @Getter @Setter
    @ApiStatus.Internal
    private boolean hidingBackpackPose = false; // This is so dumb, but I need to know if a user has their backpack hidden because of their pose. See #214. Probably move over to a better system in the future.

    /**
     * Use {@link #CosmeticUser(UUID)} instead and use {@link #initialize(UserData)} to populate the user with data.
     * @param uuid
     * @param data
     */
    @Deprecated(forRemoval = true, since = "2.7.5")
    public CosmeticUser(UUID uuid, UserData data) {
        this(uuid);
        initialize(data);
    }

    public CosmeticUser(@NotNull UUID uuid) {
        this.uniqueId = uuid;
    }

    /**
     * Initialize the {@link CosmeticUser}.
     * @param userData the associated {@link UserData}
     * @return the {@link CosmeticUser}
     * @apiNote Initialize is called after {@link CosmeticUserProvider#createCosmeticUser(UUID)} so it is possible to
     * populate an extending version of {@link CosmeticUser} with data then override this method to apply your
     * own state.
     */
    public CosmeticUser initialize(final @Nullable UserData userData) {
        if(userData != null) {
            // CosmeticSlot -> Entry<Cosmetic, Integer>
            for(final Map.Entry<CosmeticSlot, Map.Entry<Cosmetic, Integer>> entry : userData.getCosmetics().entrySet()) {
                final Cosmetic cosmetic = entry.getValue().getKey();
                final Integer colorRGBInt = entry.getValue().getValue();

                if (!this.canApplyCosmetic(cosmetic)) {
                    MessagesUtil.sendDebugMessages("Cannot apply cosmetic[id=" + cosmetic.getId() + "]");
                    continue;
                }

                Color color = null;
                if (colorRGBInt != -1) color = Color.fromRGB(colorRGBInt); // -1 is defined as no color; anything else is a color

                this.addCosmetic(cosmetic, color);
            }
            this.applyHiddenState(userData.getHiddenReasons());
        }

        return this;
    }

    /**
     * This method is only called from {@link #initialize(UserData)} and can't be called directly.
     * This is used to help hooking plugins apply custom logic to the user.
     */
    protected boolean applyCosmetic(@NotNull Cosmetic cosmetic, @Nullable Color color) {
        this.addCosmetic(cosmetic, color);
        return true;
    }

    /**
     * This method is only called from {@link #initialize(UserData)} and can't be called directly.
     * This is used to help hooking plugins apply custom logic to the user.
     */
    protected boolean canApplyCosmetic(@NotNull Cosmetic cosmetic) {
        return canEquipCosmetic(cosmetic, false);
    }

    /**
     * This method is only called from {@link #initialize(UserData)} and can't be called directly.
     * This is used to help hooking plugins apply custom logic to the user.
     */
    protected void applyHiddenState(@NotNull List<HiddenReason> hiddenReasons) {
        if(!hiddenReason.isEmpty()) {
            for(final HiddenReason reason : this.hiddenReason) {
                this.silentlyAddHideFlag(reason);
            }
            return;
        }

        Player bukkitPlayer = getPlayer();
        if (bukkitPlayer != null && Settings.isDisabledGamemodesEnabled() && Settings.getDisabledGamemodes().contains(bukkitPlayer.getGameMode().toString())) {
            MessagesUtil.sendDebugMessages("Hiding cosmetics due to gamemode");
            hideCosmetics(HiddenReason.GAMEMODE);
        } else if (this.isHidden(HiddenReason.GAMEMODE)) {
            MessagesUtil.sendDebugMessages("Showing cosmetics for gamemode");
            showCosmetics(HiddenReason.GAMEMODE);
        }

        if (bukkitPlayer != null && Settings.getDisabledWorlds().contains(bukkitPlayer.getLocation().getWorld().getName())) {
            MessagesUtil.sendDebugMessages("Hiding Cosmetics due to world");
            hideCosmetics(CosmeticUser.HiddenReason.WORLD);
        } else if (this.isHidden(HiddenReason.WORLD)) {
            MessagesUtil.sendDebugMessages("Showing Cosmetics due to world");
            showCosmetics(HiddenReason.WORLD);
        }

        if (bukkitPlayer != null && bukkitPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            hideCosmetics(HiddenReason.POTION);
        }

        if (Settings.isAllPlayersHidden()) {
            hideCosmetics(HiddenReason.DISABLED);
        }

        for (final HiddenReason reason : hiddenReasons) {
            // VANISH is derived fresh on every tick, never restored. Bringing it back here would
            // record the player as hidden without hiding anything: silentlyAddHideFlag only sets the
            // flag, and the tick's hideCosmetics(VANISH) then returns early because the reason is
            // already listed, so a cosmetic drawn while loading (an aura, most visibly) stays up for
            // the whole session.
            if (reason == HiddenReason.VANISH) continue;

            this.silentlyAddHideFlag(reason);
        }
    }

    /**
     * Start ticking against the {@link CosmeticUser}.
     * @implNote The tick-rate is determined by the tick period specified in the configuration, if it is less-than or equal to 0
     * there will be no {@link BukkitTask} created, and the {@link CosmeticUser#taskId} will be -1
     */
    public final void startTicking() {
        int tickPeriod = Settings.getTickPeriod();
        if(tickPeriod <= 0) {
            MessagesUtil.sendDebugMessages("CosmeticUser tick is disabled.");
            return;
        }

        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(HMCCosmeticsPlugin.getInstance(), this::tick, 0, tickPeriod);
        this.taskId = task.getTaskId();
    }

    /**
     * Dispatch an operation to happen against this {@link CosmeticUser}
     * at a pre-determined tick-rate.
     * The tick-rate is determined by the tick period specified in the configuration.
     */
    protected void tick() {
        MessagesUtil.sendDebugMessages("Tick[uuid=" + uniqueId + "]", Level.INFO);

        if (Hooks.isInvisible(uniqueId) || isVanished()) {
            this.hideCosmetics(HiddenReason.VANISH);
        } else {
            this.showCosmetics(HiddenReason.VANISH);
        }

        this.updateCosmetic();
    }

    /**
     * Whether the server has marked this player as vanished.
     *
     * <p>{@link Hooks#isInvisible} only answers for the vanish plugins HibiscusCommons ships a hook
     * for, and this network's vanish is its own. The {@code vanished} metadata key is the contract it
     * publishes instead, the same one TAB reads to keep a vanished player out of the tab list, so
     * reading it here needs no dependency on that plugin.</p>
     */
    private boolean isVanished() {
        final Player player = getPlayer();
        return player != null && player.hasMetadata(VANISH_METADATA);
    }

    public void destroy() {
        if(this.taskId != -1) { // ensure we're actually ticking this user.
            Bukkit.getScheduler().cancelTask(taskId);
        }

        despawnBackpack();
        despawnBalloon();
        clearAura();
    }

    @Override
    public @Nullable Cosmetic getCosmetic(@NotNull CosmeticSlot slot) {
        return playerCosmetics.getOrDefault(slot, null);
    }

    @Override
    public @NotNull ImmutableCollection<Cosmetic> getCosmetics() {
        return ImmutableList.copyOf(playerCosmetics.values());
    }

    @Override
    public void addCosmetic(@NotNull Cosmetic cosmetic, @Nullable Color color) {
        // API
        PlayerCosmeticEquipEvent event = new PlayerCosmeticEquipEvent(this, cosmetic);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        cosmetic = event.getCosmetic();
        // Internal
        if (playerCosmetics.containsKey(cosmetic.getSlot())) {
            removeCosmeticSlot(cosmetic.getSlot());
        }

        playerCosmetics.put(cosmetic.getSlot(), cosmetic);
        if (color != null) colors.put(cosmetic.getSlot(), color);
        MessagesUtil.sendDebugMessages("addPlayerCosmetic[id=" + cosmetic.getId() + "]");
        if (!isHidden()) {
            if (cosmetic.getSlot() == CosmeticSlot.BACKPACK) {
                CosmeticBackpackType backpackType = (CosmeticBackpackType) cosmetic;
                spawnBackpack(backpackType);
                MessagesUtil.sendDebugMessages("addPlayerCosmetic[spawnBackpack,id=" + cosmetic.getId() + "]");
            }
            if (cosmetic.getSlot() == CosmeticSlot.BALLOON) {
                CosmeticBalloonType balloonType = (CosmeticBalloonType) cosmetic;
                spawnBalloon(balloonType);
            }
            if (cosmetic.getSlot() == CosmeticSlot.AURA) {
                refreshAura();
            }
        }
        // API
        PlayerCosmeticPostEquipEvent postEquipEvent = new PlayerCosmeticPostEquipEvent(this, cosmetic);
        Bukkit.getPluginManager().callEvent(postEquipEvent);
    }

    /**
     * @deprecated Use {@link #addCosmetic(Cosmetic)} instead
     */
    @Deprecated(since = "2.7.7", forRemoval = true)
    public void addPlayerCosmetic(@NotNull Cosmetic cosmetic) {
        addCosmetic(cosmetic);
    }

    /**
     * @deprecated Use {@link #addCosmetic(Cosmetic, Color)} instead
     */
    @Deprecated(since = "2.7.7", forRemoval = true)
    public void addPlayerCosmetic(@NotNull Cosmetic cosmetic, @Nullable Color color) {
        addCosmetic(cosmetic, color);
    }

    @Override
    public void removeCosmeticSlot(@NotNull CosmeticSlot slot) {
        // API
        PlayerCosmeticRemoveEvent event = new PlayerCosmeticRemoveEvent(this, getCosmetic(slot));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        // Internal
        if (slot == CosmeticSlot.BACKPACK) {
            despawnBackpack();
        }
        if (slot == CosmeticSlot.BALLOON) {
            despawnBalloon();
        }
        if (slot == CosmeticSlot.AURA) {
            clearAura();
        }
        colors.remove(slot);
        playerCosmetics.remove(slot);
        removeArmor(slot);
    }

    @Override
    public boolean hasCosmeticInSlot(@NotNull CosmeticSlot slot) {
        return playerCosmetics.containsKey(slot);
    }

    public @NotNull Set<CosmeticSlot> getSlotsWithCosmetics() {
        return Set.copyOf(playerCosmetics.keySet());
    }

    @Override
    public boolean updateCosmetic(@NotNull CosmeticSlot slot) {
        final Cosmetic cosmetic = playerCosmetics.get(slot);
        if(cosmetic == null) {
            return false;
        }

        if(!(cosmetic instanceof CosmeticUpdateBehavior behavior)) {
            MessagesUtil.sendDebugMessages("Attempted to update cosmetic that does not implement CosmeticUpdateBehavior");
            return false;
        }

        behavior.dispatchUpdate(this);
        return true;
    }

    @Override
    public boolean updateMovementCosmetic(@NotNull CosmeticSlot slot, @NotNull final Location from, @NotNull final Location to) {
        final Cosmetic cosmetic = playerCosmetics.get(slot);
        if(cosmetic == null) {
            return false;
        }

        if(!(cosmetic instanceof CosmeticMovementBehavior behavior)) {
            MessagesUtil.sendDebugMessages("Attempted to update cosmetic that does not implement CosmeticMovementBehavior");
            return false;
        }

        behavior.dispatchMove(this, from, to);
        return true;
    }

    public boolean updateCosmetic(@NotNull final Cosmetic cosmetic) {
        return updateCosmetic(cosmetic.getSlot());
    }

    public void updateCosmetic() {
        MessagesUtil.sendDebugMessages("updateCosmetic (All) - start");
        final HashMap<EquipmentSlot, ItemStack> items = new HashMap<>();

        for(final Cosmetic cosmetic : playerCosmetics.values()) {
            if(!(cosmetic instanceof CosmeticUpdateBehavior behavior)) {
                continue;
            }

            // defers item updates to end of operation
            if(cosmetic instanceof CosmeticArmorType armorType) {
                if (isInWardrobe()) return;
                if (!(getEntity() instanceof HumanEntity humanEntity)) return;

                boolean requireEmpty = Settings.getSlotOption(armorType.getEquipSlot()).isRequireEmpty();
                boolean isAir = humanEntity.getInventory().getItem(armorType.getEquipSlot()).getType().isAir();
                MessagesUtil.sendDebugMessages("updateCosmetic (All) - " + armorType.getId() + " - " + requireEmpty + " - " + isAir);
                if (requireEmpty && !isAir) continue;

                items.put(HMCCInventoryUtils.getEquipmentSlot(armorType.getSlot()), armorType.getItem(this));
            } else {
                behavior.dispatchUpdate(this);
            }
        }

        final Entity entity = this.getEntity();
        if(!items.isEmpty() && entity != null) {
            NMSHandlers.getHandler().getPacketBuilder().buildEntityEquipmentSlotUpdatePacket(entity.getEntityId(), items).sendPacket(HMCCPacketManager.getViewers(entity.getLocation()));
            MessagesUtil.sendDebugMessages("updateCosmetic (All) - end - " + items.size());
        }
    }

    public ItemStack getUserCosmeticItem(@NotNull CosmeticSlot slot) {
        Cosmetic cosmetic = getCosmetic(slot);
        if (cosmetic == null) return new ItemStack(Material.AIR);
        return getUserCosmeticItem(cosmetic);
    }

    public ItemStack getUserCosmeticItem(@NotNull Cosmetic cosmetic) {
        ItemStack item = null;
        if (!hiddenReason.isEmpty()) {
            if (cosmetic instanceof CosmeticBackpackType || cosmetic instanceof CosmeticBalloonType) return new ItemStack(Material.AIR);
            return getPlayer().getInventory().getItem(HMCCInventoryUtils.getEquipmentSlot(cosmetic.getSlot()));
        }
        if (cosmetic instanceof CosmeticArmorType armorType) {
            item = armorType.getItem(this, cosmetic.getItem());
        }
        if (cosmetic instanceof CosmeticBackpackType) {
            item = cosmetic.getItem();
        }
        if (cosmetic instanceof CosmeticBalloonType) {
            if (cosmetic.getItem() == null) {
                item = new ItemStack(Material.LEATHER_HORSE_ARMOR);
            } else {
                item = cosmetic.getItem();
            }
        }
        return getUserCosmeticItem(cosmetic, item);
    }

    @SuppressWarnings("deprecation")
    public @NotNull ItemStack getUserCosmeticItem(@NotNull Cosmetic cosmetic, @Nullable ItemStack item) {
        if (item == null) {
            //MessagesUtil.sendDebugMessages("GetUserCosemticUser Item is null");
            return new ItemStack(Material.AIR);
        }
        // Some callers (e.g. CosmeticBackpackType's cached firstperson item) pass a shared ItemStack
        // instance that is reused on every tick. This method mutates item's meta below (styleMeta in
        // particular appends to whatever lore is already there), so without cloning first, a shared
        // instance would accumulate duplicate lore lines on every call until it exceeds the 256-line cap.
        item = item.clone();
        if (item.hasItemMeta()) {
            ItemMeta itemMeta = item.getItemMeta();

            if (item.getType() == Material.PLAYER_HEAD) {
                SkullMeta skullMeta = (SkullMeta) itemMeta;
                if (skullMeta.getPersistentDataContainer().has(InventoryUtils.getSkullOwner(), PersistentDataType.STRING)) {
                    String owner = skullMeta.getPersistentDataContainer().get(InventoryUtils.getSkullOwner(), PersistentDataType.STRING);

                    owner = Hooks.processPlaceholders(getPlayer(), owner);

                    skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
                    //skullMeta.getPersistentDataContainer().remove(InventoryUtils.getSkullOwner()); // Don't really need this?
                }
                if (skullMeta.getPersistentDataContainer().has(InventoryUtils.getSkullTexture(), PersistentDataType.STRING)) {
                    String texture = skullMeta.getPersistentDataContainer().get(InventoryUtils.getSkullTexture(), PersistentDataType.STRING);

                    texture = Hooks.processPlaceholders(getPlayer(), texture);

                    Bukkit.getUnsafe().modifyItemStack(item, "{SkullOwner:{Id:[I;0,0,0,0],Properties:{textures:[{Value:\""
                            + texture + "\"}]}}}");
                    //skullMeta.getPersistentDataContainer().remove(InventoryUtils.getSkullTexture()); // Don't really need this?
                }

                itemMeta = skullMeta;
            }

            if (Settings.isItemProcessingDisplayName()) {
                if (itemMeta.hasDisplayName()) {
                    String displayName = itemMeta.getDisplayName();
                    itemMeta.setDisplayName(Hooks.processPlaceholders(getPlayer(), displayName));
                }
            }
            if (Settings.isItemProcessingLore()) {
                List<String> processedLore = new ArrayList<>();
                if (itemMeta.hasLore()) {
                    for (String loreLine : itemMeta.getLore()) {
                        processedLore.add(Hooks.processPlaceholders(getPlayer(), loreLine));
                    }
                }
                itemMeta.setLore(processedLore);
            }


            itemMeta.getPersistentDataContainer().set(HMCCInventoryUtils.getCosmeticKey(), PersistentDataType.STRING, cosmetic.getId());
            itemMeta.getPersistentDataContainer().set(InventoryUtils.getOwnerKey(), PersistentDataType.STRING, getEntity().getUniqueId().toString());

            // Matches the GUI treatment: hide the tooltip lines Minecraft writes by itself ("Dyed",
            // "When worn: +3 Armor", enchantments, trims) and drop the italics a custom name gets by
            // default, so an equipped cosmetic's tooltip shows only its own name and lore.
            itemMeta.addItemFlags(ItemFlag.values());
            Component displayNameComponent = itemMeta.displayName();
            if (displayNameComponent != null) {
                itemMeta.displayName(displayNameComponent.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }

            cosmetic.styleMeta(itemMeta);

            item.setItemMeta(itemMeta);

            if (colors.containsKey(cosmetic.getSlot())) {
                Color color = colors.get(cosmetic.getSlot());
                item = NMSHandlers.getHandler().getUtilHandler().setColor(item, color);
            }
        }
        return item;
    }

    public @Nullable UserBalloonManager getBalloonManager() {
        return this.userBalloonManager;
    }

    public @Nullable UserWardrobeManager getWardrobeManager() {
        return userWardrobeManager;
    }

    /**
     * Use {@link #enterWardrobe(Wardrobe, boolean)} instead.
     * @param ignoreDistance
     * @param wardrobe
     */
    @Deprecated(forRemoval = true, since = "2.7.5")
    public void enterWardrobe(boolean ignoreDistance, @NotNull Wardrobe wardrobe) {
        enterWardrobe(wardrobe, ignoreDistance);
    }

    /**
     * This method is used to enter a wardrobe. You can listen to the {@link PlayerWardrobeEnterEvent} to cancel the event or modify any data.
     * @param wardrobe The wardrobe to enter. Use {@link WardrobeSettings#getWardrobe(String)} to get pre-existing wardrobe or use your own by {@link Wardrobe}.
     * @param ignoreDistance If true, the player can enter the wardrobe from any distance. If false, the player must be within the distance set in the wardrobe (If wardrobe has a distance of 0 or lower, the player can enter from any distance).
     */
    public void enterWardrobe(@NotNull Wardrobe wardrobe, boolean ignoreDistance) {
        if (wardrobe.hasPermission() && !getPlayer().hasPermission(wardrobe.getPermission())) {
            MessagesUtil.sendMessage(getPlayer(), "no-permission");
            return;
        }
        if (!wardrobe.canEnter(this) && !ignoreDistance) {
            MessagesUtil.sendMessage(getPlayer(), "not-near-wardrobe");
            return;
        }
        if (!wardrobe.getLocation().hasAllLocations()) {
            MessagesUtil.sendMessage(getPlayer(), "wardrobe-not-setup");
            return;
        }
        PlayerWardrobeEnterEvent event = new PlayerWardrobeEnterEvent(this, wardrobe);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        wardrobe = event.getWardrobe();

        if (userWardrobeManager == null) {
            userWardrobeManager = new UserWardrobeManager(this, wardrobe);
            userWardrobeManager.start();
        }
    }

    /**
     * Use {@link #leaveWardrobe(boolean)} instead.
     */
    @Deprecated(forRemoval = true, since = "2.7.5")
    public void leaveWardrobe() {
        leaveWardrobe(false);
    }

    /**
     * Causes the player to leave the wardrobe. If a player is not in the wardrobe, this will do nothing, use (@{@link #isInWardrobe()} to check if they are).
     * @param ejected If true, the player was ejected from the wardrobe (Skips transition). If false, the player left the wardrobe normally.
     */
    public void leaveWardrobe(boolean ejected) {
        UserWardrobeManager userWardrobe = getWardrobeManager();
        if (userWardrobe == null) return;

        if (userWardrobe.getWardrobeStatus() != UserWardrobeManager.WardrobeStatus.RUNNING) return;
        PlayerWardrobeLeaveEvent event = new PlayerWardrobeLeaveEvent(this, userWardrobe);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        MessagesUtil.sendDebugMessages("Leaving Wardrobe");

        userWardrobe.setWardrobeStatus(UserWardrobeManager.WardrobeStatus.STOPPING);

        if (WardrobeSettings.isEnabledTransition() && !ejected) {
            MessagesUtil.sendTitle(
                    getPlayer(),
                    WardrobeSettings.getTransitionText(),
                    WardrobeSettings.getTransitionFadeIn(),
                    WardrobeSettings.getTransitionStay(),
                    WardrobeSettings.getTransitionFadeOut()
            );
            Bukkit.getScheduler().runTaskLater(HMCCosmeticsPlugin.getInstance(), () -> {
                userWardrobeManager.end();
                userWardrobeManager = null;
                refreshAura();
            }, WardrobeSettings.getTransitionDelay());
        } else {
            userWardrobeManager.end();
            userWardrobeManager = null;
            refreshAura();
        }
    }

    /**
     * This checks if the player is in a wardrobe. If they are, it will return true, else false. See {@link #getWardrobeManager()} to get the wardrobe manager.
     * @return If the player is in a wardrobe.
     */
    public boolean isInWardrobe() {
        return userWardrobeManager != null;
    }

    public void spawnBackpack(CosmeticBackpackType cosmeticBackpackType) {
        if (this.userBackpackManager != null) return;
        this.userBackpackManager = new UserBackpackManager(this);
        userBackpackManager.spawnBackpack(cosmeticBackpackType);
        // The stand spawns already glowing when an aura is on, but its team is what colors it, and
        // that only reaches this backpack's own UUID once the aura is pushed again.
        refreshAura();
    }

    public void despawnBackpack() {
        if (userBackpackManager == null) return;
        userBackpackManager.despawnBackpack();
        userBackpackManager = null;
    }

    public boolean isBackpackSpawned() {
        return this.userBackpackManager != null;
    }

    public boolean isBalloonSpawned() {
        return this.userBalloonManager != null;
    }

    public void spawnBalloon(CosmeticBalloonType cosmeticBalloonType) {
        if (this.userBalloonManager != null) return;

        org.bukkit.entity.Entity entity = getEntity();

        UserBalloonManager userBalloonManager1 = new UserBalloonManager(this, entity.getLocation());
        userBalloonManager1.getModelEntity().teleport(entity.getLocation().add(cosmeticBalloonType.getBalloonOffset()));

        userBalloonManager1.spawnModel(cosmeticBalloonType, getCosmeticColor(cosmeticBalloonType.getSlot()));
        userBalloonManager1.addPlayerToModel(this, cosmeticBalloonType, getCosmeticColor(cosmeticBalloonType.getSlot()));

        this.userBalloonManager = userBalloonManager1;
    }

    public void despawnBalloon() {
        if (this.userBalloonManager == null) return;
        this.userBalloonManager.remove();
        this.userBalloonManager = null;
    }

    /**
     * Re-asserts the wearer's aura to everyone currently in range: its color through TAB (which no-ops
     * when the color is already the one applied) and its glowing bit through a metadata packet. Called
     * on equip, on unhide and on every user tick, the last of which is what reaches players who have
     * only just come into range.
     *
     * <p>Refuses to draw anything while the wearer is hidden. Several callers reach here from a state
     * change rather than from an equip, and a backpack respawn is one of them: its pose is recomputed
     * on sneak and sprint, so without this guard a vanished moderator got their aura back the moment
     * they moved, and it stuck, because {@link #trackAuraGlow} had put the entity back into the
     * packet interceptor that holds the glowing bit in.</p>
     */
    public void refreshAura() {
        if (isHidden()) return;
        if (!(getCosmetic(CosmeticSlot.AURA) instanceof CosmeticAuraType aura)) return;

        Player player = getPlayer();
        if (player == null) return;

        HookTAB.setGlowColor(player, aura.getColor());
        paintMannequinTeam(player, aura.getColor());
        trackAuraGlow(player);
        sendAuraGlow(player, true);
        sendBackpackAura(aura.getColor());
    }

    /** Whether an aura is equipped and currently being drawn. */
    public boolean hasAura() {
        return !isHidden() && hasCosmeticInSlot(CosmeticSlot.AURA);
    }

    private void trackAuraGlow(@NotNull Player player) {
        auraEntityId = player.getEntityId();
        HookPacketEvents.trackGlow(auraEntityId);
    }

    /** Kept by id rather than looked up again, so this still works for a player already on their way out. */
    private void untrackAuraGlow() {
        if (auraEntityId == -1) return;

        HookPacketEvents.untrackGlow(auraEntityId);
        auraEntityId = -1;
    }

    /**
     * Lights the backpack up along with its wearer, so the two read as one silhouette.
     *
     * <p>A backpack is an invisible armor stand carrying the cosmetic on its head, and the outline of a
     * glowing entity is drawn around whatever it wears, so the same metadata bit does the job. The
     * color cannot come from the wearer's team though, since that one only paints the wearer: the
     * stand gets a team of ours, which TAB has no interest in because its only member is not a
     * player.</p>
     *
     * @param color the aura color, or null to put the backpack back to normal
     */
    private void sendBackpackAura(@Nullable ChatColor color) {
        if (userBackpackManager == null) return;

        List<Player> viewers = userBackpackManager.getEntityManager().getViewers();
        int armorStandId = userBackpackManager.getFirstArmorStandId();
        String teamName = auraTeamName();

        HMCCPacketManager.sendEntityFlagsPacket(armorStandId, HMCCPacketManager.getArmorStandFlags(color != null), viewers);

        if (color == null) {
            HookPacketEvents.removeTeam(viewers, teamName);
            return;
        }
        HookPacketEvents.createTeam(viewers, teamName, color, List.of(userBackpackManager.getArmorStandUuid().toString()));
    }

    /** Short enough to stay within the sixteen characters older clients accept for a team name. */
    private String auraTeamName() {
        return "aura_" + uniqueId.toString().substring(0, 8);
    }

    /**
     * Re-sends the aura only when the wearer's flag byte has changed since the last send.
     *
     * <p>The glowing bit shares metadata index 0 with sneaking, sprinting, burning, swimming and
     * gliding. Every one of those transitions makes the server write that byte itself, which drops the
     * bit, so an aura left to the once-a-second refresh visibly blinks out on each of them. Comparing
     * the byte every tick catches all of them, including the ones no event announces, and costs a
     * packet only when something actually moved.</p>
     *
     * <p>The change is answered twice, this tick and the next, because of where in the tick this runs:
     * the scheduler fires before the entity tracker flushes dirty metadata, so on the tick a flag
     * changes our packet goes out first and the server's own byte lands on top of it and wins. The
     * second send is the one that sticks. Sending only once looks fine in a debugger and blinks for a
     * full second in game, since by then the compare below is happy and stays quiet.</p>
     */
    public void refreshAuraOnStateChange() {
        if (isHidden() || !hasCosmeticInSlot(CosmeticSlot.AURA)) return;

        Player player = getPlayer();
        if (player == null) return;

        byte flags = auraFlags(player, true);
        boolean changed = lastAuraFlags == null || lastAuraFlags != flags;
        if (!changed && !auraResendPending) return;

        sendAuraGlow(player, true);
        auraResendPending = changed;
    }

    /** Drops the glowing bit on the client and hands the nametag prefix back to TAB. */
    public void clearAura() {
        // Ahead of everything else: while the entity is still tracked, the packet sent below to put the
        // bit out would be caught on its way to the client and have the bit put straight back in.
        untrackAuraGlow();

        Player player = getPlayer();
        if (player == null) return;

        HookTAB.clearGlowColor(player);
        paintMannequinTeam(player, null);
        sendAuraGlow(player, false);
        sendBackpackAura(null);
        lastAuraFlags = null;
        auraResendPending = false;
    }

    /**
     * Recolors the wardrobe mannequin's team, which is what an aura previews through while its wearer
     * is inside the wardrobe. Does nothing outside it, and nothing before the mannequin exists: the
     * wardrobe names it only once its opening transition has played out.
     */
    private void paintMannequinTeam(@NotNull Player player, @Nullable ChatColor color) {
        if (!isInWardrobe()) return;

        String npcName = userWardrobeManager.getNpcName();
        if (npcName == null) return;

        HookPacketEvents.setTeamColor(player, npcName, color);
    }

    private void sendAuraGlow(@NotNull Player player, boolean glowing) {
        byte flags = auraFlags(player, glowing);
        lastAuraFlags = flags;

        if (isInWardrobe()) {
            HMCCPacketManager.sendEntityFlagsPacket(userWardrobeManager.getNPC_ID(), flags, List.of(player));
            return;
        }

        HMCCPacketManager.sendEntityFlagsPacket(player.getEntityId(), flags, HMCCPacketManager.getViewers(player.getLocation()));
    }

    /**
     * In the wardrobe the wearer's own entity is hidden and what they are looking at is the mannequin,
     * so the packet names that entity instead and only they receive it. The mannequin has no state of
     * its own to preserve, and it must not inherit the wearer's: the wardrobe makes the wearer
     * invisible while they are inside it, and copying that byte over would make the mannequin vanish.
     */
    private byte auraFlags(@NotNull Player player, boolean glowing) {
        return isInWardrobe()
                ? HMCCPacketManager.getGlowOnlyFlags(glowing)
                : HMCCPacketManager.getEntityFlags(player, glowing);
    }

    public void respawnBackpack() {
        if (!hasCosmeticInSlot(CosmeticSlot.BACKPACK)) return;
        final Cosmetic cosmetic = getCosmetic(CosmeticSlot.BACKPACK);
        despawnBackpack();
        if (!hiddenReason.isEmpty()) return;
        spawnBackpack((CosmeticBackpackType) cosmetic);
        MessagesUtil.sendDebugMessages("Respawned Backpack for " + getEntity().getName());
    }

    public void respawnBalloon() {
        if (!hasCosmeticInSlot(CosmeticSlot.BALLOON)) return;
        final Cosmetic cosmetic = getCosmetic(CosmeticSlot.BALLOON);
        despawnBalloon();
        if (!hiddenReason.isEmpty()) return;
        spawnBalloon((CosmeticBalloonType) cosmetic);
        MessagesUtil.sendDebugMessages("Respawned Balloon for " + getEntity().getName());
    }

    public void removeArmor(CosmeticSlot slot) {
        EquipmentSlot equipmentSlot = HMCCInventoryUtils.getEquipmentSlot(slot);
        if (equipmentSlot == null) return;
        if (getPlayer() != null) {
            HMCCPacketManager.equipmentSlotUpdate(getEntity().getEntityId(), equipmentSlot, getPlayer().getInventory().getItem(equipmentSlot), HMCCPacketManager.getViewers(getEntity().getLocation()));
        } else {
            HMCCPacketManager.equipmentSlotUpdate(getEntity().getEntityId(), this, slot, HMCCPacketManager.getViewers(getEntity().getLocation()));
        }
    }

    /**
     * This returns the player associated with the user. Some users may not have a player attached, ie, they are npcs
     * wearing cosmetics through an addon. If you need to get locations, use getEntity instead.
     * @return Player
     */
    @Nullable
    public Player getPlayer() {
        return Bukkit.getPlayer(uniqueId);
    }

    /**
     * This gets the entity associated with the user.
     * @return Entity
     */
    public Entity getEntity() {
        return getPlayer();
    }

    public Color getCosmeticColor(CosmeticSlot slot) {
        return colors.get(slot);
    }

    public List<CosmeticSlot> getDyeableSlots() {
        ArrayList<CosmeticSlot> dyeableSlots = new ArrayList<>();

        for (Cosmetic cosmetic : playerCosmetics.values()) {
            if (cosmetic.isDyeable()) dyeableSlots.add(cosmetic.getSlot());
        }

        return dyeableSlots;
    }

    @Override
    public boolean canEquipCosmetic(@NotNull Cosmetic cosmetic, boolean ignoreWardrobe) {
        if (!cosmetic.isEnabled()) return false;
        if (!cosmetic.requiresPermission()) return true;
        if (isInWardrobe() && !ignoreWardrobe) {
            if (WardrobeSettings.isTryCosmeticsInWardrobe() && userWardrobeManager.getWardrobeStatus().equals(UserWardrobeManager.WardrobeStatus.RUNNING)) return true;
        }
        final Player player = getPlayer();
        if (player != null) return player.hasPermission(cosmetic.getPermission());
        // This sucks, but basically if we can find a player, use that. If not, try to find the entity. If it can't find the entity, just return false.
        final Entity entity = getEntity();
        if (entity != null) return entity.hasPermission(cosmetic.getPermission());
        return false;
    }

    public void hidePlayer() {
        Player player = getPlayer();
        if (player == null) return;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(player)) continue;
            p.hidePlayer(HMCCosmeticsPlugin.getInstance(), player);
            player.hidePlayer(HMCCosmeticsPlugin.getInstance(), p);

            // hidePlayer() also drops both from each other's tab list on this server; only the
            // in-world model should disappear, so re-send their tab entries right after hiding.
            HMCCPacketManager.sendFakePlayerInfoPacket(player, player.getEntityId(), player.getUniqueId(), player.getName(), List.of(p));
            HMCCPacketManager.sendFakePlayerInfoPacket(p, p.getEntityId(), p.getUniqueId(), p.getName(), List.of(player));
        }
    }

    public void showPlayer() {
        Player player = getPlayer();
        if (player == null) return;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.showPlayer(HMCCosmeticsPlugin.getInstance(), player);
            player.showPlayer(HMCCosmeticsPlugin.getInstance(), p);
        }
    }

    public void hideCosmetics(@NotNull HiddenReason reason) {
        if (hiddenReason.contains(reason)) return;

        PlayerCosmeticHideEvent event = new PlayerCosmeticHideEvent(this, reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        hiddenReason.add(reason);
        if (hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
            despawnBalloon();
            //getBalloonManager().removePlayerFromModel(getPlayer());
            //getBalloonManager().sendRemoveLeashPacket();
        }
        if (hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
            despawnBackpack();
        }
        if (hasCosmeticInSlot(CosmeticSlot.AURA)) {
            clearAura();
        }
        updateCosmetic();
        MessagesUtil.sendDebugMessages("HideCosmetics");
    }

    /**
     * This is used to silently add a hidden flag to the user. This will not trigger any events or checks, nor do anything else
     * @param reason
     */
    public void silentlyAddHideFlag(@NotNull HiddenReason reason) {
        if (!hiddenReason.contains(reason)) hiddenReason.add(reason);
    }

    public void showCosmetics(@NotNull HiddenReason reason) {
        if (hiddenReason.isEmpty()) return;
        if (!hiddenReason.contains(reason)) return;

        PlayerCosmeticShowEvent event = new PlayerCosmeticShowEvent(this, reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        hiddenReason.remove(reason);
        if (isHidden()) return;
        if (hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
            if (!isBalloonSpawned()) respawnBalloon();
            CosmeticBalloonType balloonType = (CosmeticBalloonType) getCosmetic(CosmeticSlot.BALLOON);
            getBalloonManager().addPlayerToModel(this, balloonType);
            List<Player> viewer = HMCCPacketManager.getViewers(getEntity().getLocation());
            HMCCPacketManager.sendLeashPacket(getBalloonManager().getPufferfishBalloonId(), getPlayer().getEntityId(), viewer);
        }
        if (hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
            if (!isBackpackSpawned()) respawnBackpack();
            CosmeticBackpackType cosmeticBackpackType = (CosmeticBackpackType) getCosmetic(CosmeticSlot.BACKPACK);
            ItemStack item = getUserCosmeticItem(cosmeticBackpackType);
            userBackpackManager.setItem(item);
        }
        if (hasCosmeticInSlot(CosmeticSlot.AURA)) {
            refreshAura();
        }
        updateCosmetic();
        MessagesUtil.sendDebugMessages("ShowCosmetics");
    }

    public boolean isHidden() {
        return !hiddenReason.isEmpty();
    }

    public boolean isHidden(HiddenReason reason) {
        return hiddenReason.contains(reason);
    }

    public @NotNull List<HiddenReason> getHiddenReasons() {
        return hiddenReason;
    }

    public void clearHiddenReasons() {
        hiddenReason.clear();
    }

    public enum HiddenReason {
        NONE,
        WORLDGUARD,
        PLUGIN,
        VANISH,
        POTION,
        ACTION,
        COMMAND,
        EMOTE,
        GAMEMODE,
        WORLD,
        DISABLED
    }
}
