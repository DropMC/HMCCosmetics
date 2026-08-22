package com.hibiscusmc.hmccosmetics.hooks.misc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import me.lojosho.hibiscuscommons.hooks.Hook;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two things the aura needs from the wire: the color of an outline on an entity whose team is
 * ours to make, and the glowing bit surviving on the server's own packets.
 *
 * <p>On a real player the aura color goes through TAB instead, because TAB owns that player's team
 * and its anti-override cancels anyone who tries to move them. The wardrobe mannequin and a
 * backpack's armor stand are not real players: they are packet-only entities whose teams are ours,
 * so TAB neither knows about them nor guards them, and those can simply be painted. PacketEvents
 * carries those packets because HibiscusCommons' team wrappers are hardcoded for hiding an NPC
 * nametag and have no color to give.</p>
 */
public class HookPacketEvents extends Hook {

    private static final String ID = "packetevents";

    private static final int FLAGS_INDEX = 0;
    private static final byte GLOWING_BIT = 0x40;

    /** Entity ids whose outgoing metadata has to keep the glowing bit. Read from netty threads. */
    private static final Set<Integer> GLOWING_ENTITIES = ConcurrentHashMap.newKeySet();

    private static PacketListenerCommon glowListener;

    public HookPacketEvents() {
        super(ID);
        setActive(true);
    }

    /**
     * Starts holding the aura's glowing bit into the server's own entity metadata packets.
     *
     * <p>The bit shares its byte with sneaking, sprinting and the rest, so the server rewrites it away
     * on every one of those transitions. Answering that after the fact cannot be seamless: the
     * scheduler runs before the entity tracker flushes, so the earliest a reply can land is the next
     * tick, and that one frame is visible as a blink. Editing the byte on its way out is the only
     * version of this that never shows a gap.</p>
     */
    public static void startGlowInterception() {
        if (!Hooks.isActiveHook(ID) || glowListener != null) return;

        glowListener = PacketEvents.getAPI().getEventManager().registerListener(new GlowKeeper());
    }

    public static void stopGlowInterception() {
        if (glowListener == null) return;

        PacketEvents.getAPI().getEventManager().unregisterListener(glowListener);
        glowListener = null;
        GLOWING_ENTITIES.clear();
    }

    /** Whether the glow is being held on outgoing packets, which is what makes the aura seamless. */
    public static boolean isGlowInterceptionActive() {
        return glowListener != null;
    }

    public static void trackGlow(int entityId) {
        if (glowListener == null) return;
        GLOWING_ENTITIES.add(entityId);
    }

    public static void untrackGlow(int entityId) {
        GLOWING_ENTITIES.remove(entityId);
    }

    /**
     * Repaints an existing client side team for one viewer, leaving its members untouched.
     *
     * @param teamName the team to repaint, which for the wardrobe is the mannequin's own name
     * @param color    the outline color, or null to fall back to white
     */
    public static void setTeamColor(@NotNull Player viewer, @NotNull String teamName, @Nullable ChatColor color) {
        if (!Hooks.isActiveHook(ID)) return;

        // TeamMode.UPDATE is the mode that carries team info without a member list, so repainting
        // never has to restate who is in the team.
        ScoreBoardTeamInfo info = new ScoreBoardTeamInfo(
                Component.text(teamName),
                Component.empty(),
                Component.empty(),
                NameTagVisibility.NEVER,
                CollisionRule.ALWAYS,
                namedColor(color),
                OptionData.NONE);

        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(viewer, new WrapperPlayServerTeams(teamName, TeamMode.UPDATE, info));
    }

    /**
     * (Re)creates a colored client side team holding {@code entries}, for each of {@code viewers}.
     *
     * <p>Used for the aura on a backpack, whose armor stand is a packet-only entity that belongs to no
     * team of its own. A team entry for anything that is not a player is its UUID.</p>
     *
     * <p>The remove that precedes the create is not optional: a create naming a team the client
     * already has throws inside the client's scoreboard, while a remove naming one it does not have is
     * a logged warning and nothing more. That asymmetry is also why this resends the pair wholesale
     * instead of tracking which viewer has already been told about the team.</p>
     */
    public static void createTeam(@NotNull List<Player> viewers, @NotNull String teamName,
                                  @Nullable ChatColor color, @NotNull Collection<String> entries) {
        if (!Hooks.isActiveHook(ID) || viewers.isEmpty()) return;

        ScoreBoardTeamInfo info = new ScoreBoardTeamInfo(
                Component.text(teamName),
                Component.empty(),
                Component.empty(),
                NameTagVisibility.NEVER,
                CollisionRule.NEVER,
                namedColor(color),
                OptionData.NONE);

        WrapperPlayServerTeams remove = new WrapperPlayServerTeams(teamName, TeamMode.REMOVE, (ScoreBoardTeamInfo) null);
        WrapperPlayServerTeams create = new WrapperPlayServerTeams(teamName, TeamMode.CREATE, info, entries);

        for (Player viewer : viewers) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, remove);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, create);
        }
    }

    public static void removeTeam(@NotNull List<Player> viewers, @NotNull String teamName) {
        if (!Hooks.isActiveHook(ID) || viewers.isEmpty()) return;

        WrapperPlayServerTeams remove = new WrapperPlayServerTeams(teamName, TeamMode.REMOVE, (ScoreBoardTeamInfo) null);
        for (Player viewer : viewers) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, remove);
        }
    }

    /**
     * The sixteen legacy colors are named identically on both sides, so the enum name is the whole
     * conversion.
     */
    private static @NotNull NamedTextColor namedColor(@Nullable ChatColor color) {
        if (color == null) return NamedTextColor.WHITE;

        NamedTextColor named = NamedTextColor.NAMES.value(color.name().toLowerCase(Locale.ROOT));
        return named != null ? named : NamedTextColor.WHITE;
    }

    /**
     * Puts the glowing bit back into the flag byte on its way out, so a wearer's viewers never receive
     * one without it.
     */
    private static final class GlowKeeper extends PacketListenerAbstract {

        private GlowKeeper() {
            super(PacketListenerPriority.HIGHEST);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            if (GLOWING_ENTITIES.isEmpty()) return;
            if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) return;

            WrapperPlayServerEntityMetadata wrapper = new WrapperPlayServerEntityMetadata(event);
            if (!GLOWING_ENTITIES.contains(wrapper.getEntityId())) return;

            for (EntityData<?> data : wrapper.getEntityMetadata()) {
                if (data.getIndex() != FLAGS_INDEX || !(data.getValue() instanceof Byte flags)) continue;
                if ((flags & GLOWING_BIT) != 0) continue;

                @SuppressWarnings("unchecked")
                EntityData<Byte> flagsData = (EntityData<Byte>) data;
                flagsData.setValue((byte) (flags | GLOWING_BIT));
                event.markForReEncode(true);
            }
        }
    }
}
