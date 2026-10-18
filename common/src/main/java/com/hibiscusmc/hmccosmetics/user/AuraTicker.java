package com.hibiscusmc.hmccosmetics.user;

/**
 * Watches every aura wearer's entity flag byte and re-sends the glow the tick it changes.
 *
 * <p>This is the fallback for a server without PacketEvents, and it cannot be seamless: the earliest
 * a reply can land is the tick after the server rewrote the byte, and that one frame reads as the
 * outline blinking. Where PacketEvents is present the bit is held on the packet itself instead (see
 * {@code HookPacketEvents}) and this never runs.</p>
 *
 * <p>The glowing bit lives in metadata index 0, which the server owns and rewrites whole whenever the
 * player starts or stops sneaking, sprinting, burning, swimming or gliding. Each of those rewrites
 * drops the bit, and waiting for the once-a-second user tick to restore it is long enough to read as
 * the aura blinking out. Watching the byte instead of listening for a handful of events covers every
 * cause, including the ones that have no event at all (fire burning out is the common one).</p>
 *
 * <p>Each user runs it on their own scheduler (see {@link CosmeticUser#startTicking()}), every tick, but
 * sends nothing while a wearer's state is unchanged, so a standing player costs a byte comparison and
 * no packets.</p>
 */
public final class AuraTicker {

    static final long PERIOD_TICKS = 1;

    private static volatile boolean enabled;

    private AuraTicker() {
    }

    public static void start() {
        enabled = true;
    }

    static boolean isEnabled() {
        return enabled;
    }
}
