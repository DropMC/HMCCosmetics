package com.hibiscusmc.hmccosmetics.user.manager;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.WardrobeSettings;
import com.hibiscusmc.hmccosmetics.config.section.Wardrobe;
import com.hibiscusmc.hmccosmetics.config.section.WardrobeLocation;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBalloonType;
import com.hibiscusmc.hmccosmetics.gui.Menu;
import com.hibiscusmc.hmccosmetics.gui.Menus;
import com.hibiscusmc.hmccosmetics.gui.bedrock.BedrockWardrobeForm;
import com.hibiscusmc.hmccosmetics.hooks.misc.HookFloodgate;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.HMCCInventoryUtils;
import com.hibiscusmc.hmccosmetics.util.HMCCServerUtils;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import lombok.Getter;
import lombok.Setter;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.nms.NMSPacketBuilder;
import me.lojosho.hibiscuscommons.nms.NMSPacketSender;
import me.lojosho.hibiscuscommons.packets.wrapper.PacketWrapper;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class UserWardrobeManager {

    /**
     * How far above its position Geyser puts the camera of the entity a Bedrock player is watching
     * from: the interaction entity's default height of 1 times the 0.85 eye ratio Geyser applies to
     * every entity. See {@link #bedrockCameraAnchor()}.
     */
    private static final double BEDROCK_ANCHOR_EYE_HEIGHT = 0.85;

    @Getter
    private final int NPC_ID;
    @Getter
    private final int ARMORSTAND_ID;
    @Getter
    private final UUID WARDROBE_UUID;
    @Getter
    private String npcName;
    @Getter
    private GameMode originalGamemode;
    @Getter
    private final CosmeticUser user;
    @Getter
    private final Wardrobe wardrobe;
    @Getter
    private final WardrobeLocation wardrobeLocation;
    @Getter
    private final Location viewingLocation;
    @Getter
    private final Location npcLocation;
    @Getter
    private Location exitLocation;
    @Getter
    private BossBar bossBar;
    @Getter
    private boolean active;
    @Setter
    @Getter
    private WardrobeStatus wardrobeStatus;
    /**
     * The tab and page to come back to while the player is inside this wardrobe. Stepping out of the
     * menu to look at the mannequin and clicking to reopen it lands on the same place rather than on
     * the default menu, and both are forgotten on leaving, since the manager goes with the session.
     */
    @Getter
    @Setter
    private Menu lastOpenMenu;
    @Getter
    @Setter
    private int lastOpenPage = 1;
    /**
     * Whether this session belongs to a Bedrock player, decided once on entry so that every step of
     * the session agrees on it even if the hook stops answering halfway through.
     * <p>
     * A Bedrock player gets the same wardrobe as everyone else, pinned camera included, but two of
     * the packets that build it have to be different because of how Geyser reads them. Both are
     * marked at the point they are sent; in short, the camera cannot be anchored to an armor stand
     * and the client cannot be told it is in spectator.
     * </p>
     */
    @Getter
    private final boolean bedrock;
    /** Flight the player walked in with, recorded in {@link #start()} and given back in {@link #end()}. */
    private boolean previousAllowFlight;
    private boolean previousFlying;
    /** Whether jump was already down on the previous input, so holding it does not reopen the menu. */
    @Getter
    @Setter
    private boolean jumpHeld;

    private NMSPacketBuilder packetBuilder = NMSHandlers.getHandler().getPacketBuilder();
    private NMSPacketSender packetSender = NMSHandlers.getHandler().getPacketSender();

    public UserWardrobeManager(CosmeticUser user, Wardrobe wardrobe) {
        World world = user.getEntity().getWorld();
        NPC_ID = me.lojosho.hibiscuscommons.util.ServerUtils.getNextEntityId(world);
        ARMORSTAND_ID = me.lojosho.hibiscuscommons.util.ServerUtils.getNextEntityId(world);
        WARDROBE_UUID = UUID.randomUUID();
        this.user = user;
        this.bedrock = HookFloodgate.isBedrockPlayer(user.getPlayer());

        this.wardrobe = wardrobe;
        this.wardrobeLocation = wardrobe.getLocation();

        this.exitLocation = wardrobeLocation.getLeaveLocation();
        this.viewingLocation = wardrobeLocation.getViewerLocation();
        this.npcLocation = wardrobeLocation.getNpcLocation();

        String defaultMenu = wardrobe.getDefaultMenu();
        if (defaultMenu != null) {
            // User has defined a custom menu in the wardrobe config
            Menu menu = Menus.getMenu(defaultMenu);
            if (menu != null) {
                // User provided a good, valid menu
                this.lastOpenMenu = Menus.getMenu(defaultMenu);
            } else {
                // User provided a menu that does not exist in HMCC
                this.lastOpenMenu = Menus.getDefaultMenu();
                MessagesUtil.sendDebugMessages("Unable to set menu (" + defaultMenu + ") in wardrobe " + getWardrobe().getId() + ". Defaulting to default menu defined in config.yml", Level.WARNING);
                if (this.lastOpenMenu == null) {
                    // That means that even the default menu is null in the config.
                    MessagesUtil.sendDebugMessages("Unable to set any menu in wardrobe " + getWardrobe().getId() + " as the fallback default menu (defined in config.yml) is invalid.", Level.WARNING);
                }
            }
        }

        wardrobeStatus = WardrobeStatus.SETUP;
    }

    public void start() {
        setWardrobeStatus(WardrobeStatus.STARTING);
        Player player = user.getPlayer();

        this.originalGamemode = player.getGameMode();
        // Read before the line below starts handing out flight, or what gets restored on the way out
        // is the wardrobe's own doing rather than what the player walked in with.
        this.previousAllowFlight = player.getAllowFlight();
        this.previousFlying = player.isFlying();
        if (WardrobeSettings.isReturnLastLocation()) {
            this.exitLocation = player.getLocation().clone();
        }

        user.hidePlayer();
        if (!Bukkit.getServer().getAllowFlight()) player.setAllowFlight(true);
        List<Player> viewer = Collections.singletonList(player);
        List<Player> outsideViewers = HMCCPacketManager.getViewers(viewingLocation);
        outsideViewers.remove(player);

        Runnable run = () -> {
            if (!player.isOnline()) {
                end();
                return;
            }

            List<PacketWrapper> viewerPackets = new ArrayList<>();

            // Camera anchor
            if (bedrock) {
                // An armor stand cannot hold a Bedrock camera: Geyser rebuilds one from its own yaw
                // (ArmorStandEntity#moveAbsoluteRaw passes yaw where pitch belongs), so the camera
                // ends up pitched to wherever the wardrobe happens to face, which is the floor for
                // any yaw past 90. An interaction entity is a plain entity to Geyser and keeps the
                // pitch it is given. It needs no metadata: Geyser spawns it invisible already.
                final Location anchor = bedrockCameraAnchor();
                viewerPackets.add(packetBuilder.buildEntitySpawnPacket(ARMORSTAND_ID, UUID.randomUUID(), EntityType.INTERACTION, anchor));
                viewerPackets.add(packetBuilder.buildEntityTeleportPacket(ARMORSTAND_ID, anchor.getX(), anchor.getY(), anchor.getZ(), anchor.getYaw(), anchor.getPitch(), false));
                viewerPackets.add(packetBuilder.buildEntityRotateHeadPacket(ARMORSTAND_ID, anchor));
            } else {
                viewerPackets.add(packetBuilder.buildEntitySpawnPacket(ARMORSTAND_ID, UUID.randomUUID(), EntityType.ARMOR_STAND, viewingLocation));
                viewerPackets.add(packetBuilder.buildEntityMetadataPacket(ARMORSTAND_ID, HMCCPacketManager.getInvisibleArmorStandData()));
                viewerPackets.add(packetBuilder.buildEntityTeleportPacket(ARMORSTAND_ID, viewingLocation.getX(), viewingLocation.getY(), viewingLocation.getZ(), viewingLocation.getYaw(), viewingLocation.getPitch(), false));
                viewerPackets.add(packetBuilder.buildEntityRotateHeadPacket(ARMORSTAND_ID, viewingLocation));
            }

            // Player
            player.teleport(viewingLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.setInvisible(true);
            // Bedrock is told adventure instead of spectator. Geyser refuses to forward the swing of
            // any session it believes is in spectator (BedrockAnimateTranslator), and that swing is
            // the punch that opens the menu, so spectator would leave the player staring at a
            // mannequin with no way in. Nothing else is lost by it: the camera below is what holds
            // the player still, and an adventure client sends no block breaks while the server still
            // has them in survival.
            viewerPackets.add(packetBuilder.buildPlayerGamemodeChangePacket(bedrock ? GameMode.ADVENTURE : GameMode.SPECTATOR));
            viewerPackets.add(packetBuilder.buildEntityCameraPacket(ARMORSTAND_ID));

            // NPC
            npcName = "Mannequin";
            if (npcName.length() >= 16) {
                npcName = npcName.substring(0, 15);
            }
            viewerPackets.add(packetBuilder.buildPlayerInfoAddPacket(player, NPC_ID, WARDROBE_UUID, npcName));
            viewerPackets.add(packetBuilder.buildEntitySpawnPacket(NPC_ID, WARDROBE_UUID, EntityType.PLAYER, npcLocation));
            viewerPackets.add(packetBuilder.buildEntityMetadataPacket(NPC_ID, HMCCPacketManager.getPlayerOverlayMetaData()));
            viewerPackets.add(packetBuilder.buildPlayerScoreboardRemovePacket(player, npcName));
            viewerPackets.add(packetBuilder.buildPlayerScoreboardCreatePacket(player, npcName));
            viewerPackets.add(packetBuilder.buildPlayerScoreboardAddPlayersPacket(player, npcName));
            AttributeInstance scaleAttribute = user.getPlayer().getAttribute(Attribute.SCALE);
            if (scaleAttribute != null) {
                viewerPackets.add(packetBuilder.buildEntityAttributePacket(NPC_ID, Attribute.SCALE, scaleAttribute.getValue()));
            }

            // Location
            viewerPackets.add(packetBuilder.buildEntityRotateHeadPacket(NPC_ID, npcLocation));
            viewerPackets.add(packetBuilder.buildEntityRotatePacket(NPC_ID, npcLocation, true));

            // Misc
            if (user.hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
                // Maybe null as backpack maybe despawned before entering
                if (user.getUserBackpackManager() == null) user.respawnBackpack();
                if (user.isBackpackSpawned()) {
                    user.getUserBackpackManager().getEntityManager().teleport(npcLocation.clone().add(0, 2, 0));

                    viewerPackets.add(packetBuilder.buildEntityEquipmentSlotUpdatePacket(user.getUserBackpackManager().getFirstArmorStandId(), Map.of(EquipmentSlot.HEAD, user.getUserCosmeticItem(user.getCosmetic(CosmeticSlot.BACKPACK)))));
                    viewerPackets.add(packetBuilder.buildEntityMountPacket(NPC_ID, new int[]{user.getUserBackpackManager().getFirstArmorStandId()}));
                }
            }

            packetSender.sendBundle(viewerPackets, viewer);

            if (bedrock) holdBedrockPlayerInPlace(player);

            if (user.hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
                if (user.getBalloonManager() == null) user.respawnBalloon();
                if (user.isBalloonSpawned()) {
                    CosmeticBalloonType cosmetic = (CosmeticBalloonType) user.getCosmetic(CosmeticSlot.BALLOON);
                    user.getBalloonManager().sendRemoveLeashPacket(viewer);
                    user.getBalloonManager().sendLeashPacket(NPC_ID);
                    //PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getModelId(), NPC_ID, viewer);

                    Location balloonLocation = npcLocation.clone().add(cosmetic.getBalloonOffset());
                    HMCCPacketManager.sendTeleportPacket(user.getBalloonManager().getPufferfishBalloonId(), balloonLocation, false, viewer);
                    user.getBalloonManager().getModelEntity().teleport(balloonLocation);
                    user.getBalloonManager().setLocation(balloonLocation);
                }
            }

            if (WardrobeSettings.isEnabledBossbar()) {
                float progress = WardrobeSettings.getBossbarProgress();
                Component message = MessagesUtil.processStringNoKey(player, WardrobeSettings.getBossbarMessage());

                bossBar = BossBar.bossBar(message, progress, WardrobeSettings.getBossbarColor(), WardrobeSettings.getBossbarOverlay());
                //Audience target = BukkitAudiences.create(HMCCosmeticsPlugin.getInstance()).player(player);

                player.showBossBar(bossBar);
            }

            if (WardrobeSettings.isEnterOpenMenu() && !bedrock) {
                Menu menu = Menus.getDefaultMenu();
                if (menu != null) menu.openMenu(user);
            }

            this.active = true;
            update();
            setWardrobeStatus(WardrobeStatus.RUNNING);
            // Bedrock is always handed the menu on arrival, whatever enter-open-menu says: the
            // control that reopens it is a jump, which is not a control anyone would guess at.
            if (bedrock) openWardrobeMenu();
            // The aura is drawn on whichever entity the wearer is looking at, which just became the
            // mannequin. Without this the preview only appears on the next user tick.
            user.refreshAura();
        };


        if (WardrobeSettings.isEnabledTransition()) {
            MessagesUtil.sendTitle(
                    user.getPlayer(),
                    WardrobeSettings.getTransitionText(),
                    WardrobeSettings.getTransitionFadeIn(),
                    WardrobeSettings.getTransitionStay(),
                    WardrobeSettings.getTransitionFadeOut()
            );
            Bukkit.getScheduler().runTaskLater(HMCCosmeticsPlugin.getInstance(), run, WardrobeSettings.getTransitionDelay());
        } else {
            run.run();
        }

    }

    /**
     * Puts a Bedrock player's camera back on the anchor, in first person.
     * <p>
     * Geyser treats jump as its own control while a camera is pinned and cycles the view between
     * first and third person with it ({@code InputCache#processInputs}). Jump is also the only
     * button left that can open the menu, so every press has to undo that. Re-sending the camera
     * packet is what does it: the translator behind it starts the pin over, and it starts in first
     * person.
     * </p>
     */
    public void repinBedrockCamera() {
        Player player = user.getPlayer();
        if (player == null) return;

        packetBuilder.buildEntityCameraPacket(ARMORSTAND_ID).sendPacket(Collections.singletonList(player));
    }

    /**
     * Opens the wardrobe's menu, whichever kind this player can be shown.
     * <p>
     * Bedrock gets {@link BedrockWardrobeForm}. Its client will not open a container while the
     * camera is pinned to the mannequin, and the pinned camera is the wardrobe itself, so the chest
     * menu cannot be relied on there at all: it has been seen to open and then stop opening again
     * with nothing on this side having changed.
     * </p>
     */
    public void openWardrobeMenu() {
        if (bedrock) {
            BedrockWardrobeForm.open(user);
            return;
        }

        Menu menu = lastOpenMenu != null ? lastOpenMenu : Menus.getDefaultMenu();
        if (menu != null) menu.openMenu(user);
    }

    /**
     * Where a Bedrock camera anchor has to sit to end up where the Java one does.
     * <p>
     * Geyser puts the camera at the anchor's eye, which it takes as 0.85 of the entity's height. The
     * Java anchor is a marker armor stand, and a marker's box is zero, so that camera sits exactly on
     * the viewing location. An interaction entity keeps Geyser's default height of 1 until the server
     * says otherwise, so its eye is 0.85 above it and the anchor drops by that much to compensate.
     * </p>
     * The rotation is the viewing location's own, which is the whole reason for the swap.
     */
    @NotNull
    private Location bedrockCameraAnchor() {
        return viewingLocation.clone().subtract(0, BEDROCK_ANCHOR_EYE_HEIGHT, 0);
    }

    /**
     * Keeps the Bedrock client from falling on its own while it is parked in the wardrobe.
     * <p>
     * Geyser stops forwarding movement the moment the camera is pinned, so the server never sees the
     * player move either way. The client still runs gravity locally though, and an adventure client
     * left falling for a whole wardrobe session is a long way down from where it is supposed to be
     * when the camera is handed back. Flight granted by the server also keeps the anticheat happy.
     */
    private void holdBedrockPlayerInPlace(@NotNull Player player) {
        player.setAllowFlight(true);
        player.setFlying(true);
    }

    /**
     * Gives back what {@link #holdBedrockPlayerInPlace} borrowed. Flight has to go before the exit
     * teleport, or a player who could not fly to begin with lands back outside still flying.
     */
    private void releaseBedrockPlayer(@NotNull Player player) {
        player.setFlying(previousFlying);
        player.setAllowFlight(previousAllowFlight);
    }

    public void end() {
        setWardrobeStatus(WardrobeStatus.STOPPING);
        Player player = user.getPlayer();

        List<Player> viewer = Collections.singletonList(player);
        List<Player> outsideViewers = HMCCPacketManager.getViewers(viewingLocation);
        outsideViewers.remove(player);

        if (player == null) return;
        if (bedrock) releaseBedrockPlayer(player);
        else if (!Bukkit.getServer().getAllowFlight()) player.setAllowFlight(false);

        Runnable run = () -> {
            this.active = false;

            // For Wardrobe Temp Cosmetics
            for (Cosmetic cosmetic : user.getCosmetics()) {
                MessagesUtil.sendDebugMessages("Checking... " + cosmetic.getId());
                if (!user.canEquipCosmetic(cosmetic)) {
                    MessagesUtil.sendDebugMessages("Unable to keep " + cosmetic.getId());
                    user.removeCosmeticSlot(cosmetic.getSlot());
                }
            }

            // NPC
            if (user.isBalloonSpawned()) user.getBalloonManager().sendRemoveLeashPacket();
            HMCCPacketManager.sendEntityDestroyPacket(NPC_ID, viewer); // Success
            HMCCPacketManager.sendRemovePlayerPacket(player, WARDROBE_UUID, viewer); // Success

            // Player
            packetBuilder.buildEntityCameraPacket(player.getEntityId()).sendPacket(viewer);
            user.getPlayer().setInvisible(false);

            // Armorstand
            HMCCPacketManager.sendEntityDestroyPacket(ARMORSTAND_ID, viewer); // Sucess

            //PacketManager.sendEntityDestroyPacket(player.getEntityId(), viewer); // Success
            if (WardrobeSettings.isForceExitGamemode()) {
                MessagesUtil.sendDebugMessages("Force Exit Gamemode " + WardrobeSettings.getExitGamemode());
                player.setGameMode(WardrobeSettings.getExitGamemode());
                packetBuilder.buildPlayerGamemodeChangePacket(WardrobeSettings.getExitGamemode()).sendPacket(viewer);
            } else {
                MessagesUtil.sendDebugMessages("Original Gamemode " + this.originalGamemode);
                player.setGameMode(this.originalGamemode);
                packetBuilder.buildPlayerGamemodeChangePacket(this.originalGamemode).sendPacket(viewer);
            }
            user.showPlayer();

            if (user.hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
                user.respawnBackpack();
                //PacketManager.ridingMountPacket(player.getEntityId(), VIEWER.getBackpackEntity().getEntityId(), viewer);
            }

            if (user.hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
                //user.respawnBalloon();
                //PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getPufferfishBalloonId(), player.getEntityId(), viewer);
            }

            player.teleport(Objects.requireNonNullElseGet(exitLocation, () -> player.getWorld().getSpawnLocation()), PlayerTeleportEvent.TeleportCause.PLUGIN);

            HashMap<EquipmentSlot, ItemStack> items = new HashMap<>();
            for (EquipmentSlot slot : HMCCInventoryUtils.getPlayerArmorSlots()) {
                ItemStack item = player.getInventory().getItem(slot);
                items.put(slot, item);
            }
            /*
            if (WardrobeSettings.isEquipPumpkin()) {
                items.put(EquipmentSlot.HEAD, player.getInventory().getHelmet());
            }
             */
            packetBuilder.buildEntityEquipmentSlotUpdatePacket(player.getEntityId(), items).sendPacket(viewer);

            if (WardrobeSettings.isEnabledBossbar()) {
                //Audience target = BukkitAudiences.create(HMCCosmeticsPlugin.getInstance()).player(player);
                player.hideBossBar(bossBar);
            }

            // The controls hint would otherwise sit on screen for its own fade after the player is
            // already back outside, where neither control does anything.
            player.sendActionBar(Component.empty());

            user.updateCosmetic();
        };
        run.run();
    }

    private void update() {
        final AtomicInteger data = new AtomicInteger();

        BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
                Player player = user.getPlayer();
                if (!active || player == null) {
                    MessagesUtil.sendDebugMessages("WardrobeEnd[user=" + user.getUniqueId() + ",reason=Active is false]");
                    this.cancel();
                    return;
                }
                MessagesUtil.sendDebugMessages("WardrobeUpdate[user=" + user.getUniqueId() + ",status=" + getWardrobeStatus() + "]");

                // Neither control is discoverable, so the hint stays up for the whole session instead of
                // flashing once on entry. Resent every run because the action bar fades on its own, and so
                // it comes back by itself once a menu stops covering the HUD.
                MessagesUtil.sendActionBar(player, bedrock ? "wardrobe-controls-bedrock" : "wardrobe-controls");

                List<Player> viewer = Collections.singletonList(player);
                List<Player> outsideViewers = HMCCPacketManager.getViewers(viewingLocation);
                outsideViewers.remove(player);

                Location location = npcLocation;
                int yaw = data.get();
                location.setYaw(yaw);

                HMCCPacketManager.sendRotateHeadPacket(NPC_ID, location, viewer);
                user.hidePlayer();
                int rotationSpeed = WardrobeSettings.getRotationSpeed();
                int newYaw = HMCCServerUtils.getNextYaw(yaw - 30, rotationSpeed);
                location.setYaw(newYaw);
                packetBuilder.buildEntityRotatePacket(NPC_ID, newYaw, 0, false).sendPacket(viewer);
                int nextyaw = HMCCServerUtils.getNextYaw(yaw, rotationSpeed);
                data.set(nextyaw);

                for (CosmeticSlot slot : CosmeticSlot.values().values()) {
                    HMCCPacketManager.equipmentSlotUpdate(NPC_ID, user, slot, viewer);
                }

                if (user.hasCosmeticInSlot(CosmeticSlot.BACKPACK) && user.getUserBackpackManager() != null) {
                    HMCCPacketManager.sendTeleportPacket(user.getUserBackpackManager().getFirstArmorStandId(), location, false, viewer);
                    packetBuilder.buildEntityMountPacket(NPC_ID, new int[]{user.getUserBackpackManager().getFirstArmorStandId()}).sendPacket(viewer);
                    user.getUserBackpackManager().getEntityManager().setRotation(nextyaw);
                    HMCCPacketManager.sendEntityDestroyPacket(user.getUserBackpackManager().getFirstArmorStandId(), outsideViewers);
                }

                if (user.hasCosmeticInSlot(CosmeticSlot.BALLOON) && user.isBalloonSpawned()) {
                    // The two lines below broke, solved by listening to PlayerCosmeticPostEquipEvent
                    //PacketManager.sendTeleportPacket(user.getBalloonManager().getPufferfishBalloonId(), npcLocation.add(Settings.getBalloonOffset()), false, viewer);
                    //user.getBalloonManager().getModelEntity().teleport(npcLocation.add(Settings.getBalloonOffset()));
                    user.getBalloonManager().sendRemoveLeashPacket(outsideViewers);
                    if (user.getBalloonManager().getBalloonType() != UserBalloonManager.BalloonType.MODELENGINE) {
                        HMCCPacketManager.sendEntityDestroyPacket(user.getBalloonManager().getModelId(), outsideViewers);
                    }
                    user.getBalloonManager().sendLeashPacket(NPC_ID);
                }

                if (WardrobeSettings.isEquipPumpkin()) {
                    HMCCPacketManager.equipmentSlotUpdate(user.getPlayer().getEntityId(), EquipmentSlot.HEAD, new ItemStack(Material.CARVED_PUMPKIN), viewer);
                } else {
                    HMCCPacketManager.equipmentSlotUpdate(user.getPlayer(), true, viewer); // Optifine dumbassery
                }
            }
        };

        runnable.runTaskTimer(HMCCosmeticsPlugin.getInstance(), 0, 2);
    }

    public enum WardrobeStatus {
        SETUP,
        STARTING,
        RUNNING,
        STOPPING,
    }

}
