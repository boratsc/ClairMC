package app.clair.mcbridge.fabric;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.advancements.Advancement;
import net.minecraft.server.level.ServerPlayer;

public final class FabricAdvancementEvents {
    public static final Event<Awarded> AWARDED = EventFactory.createArrayBacked(
            Awarded.class,
            listeners -> (player, advancement) -> {
                for (Awarded listener : listeners) {
                    listener.onAwarded(player, advancement);
                }
            }
    );

    private FabricAdvancementEvents() {
    }

    @FunctionalInterface
    public interface Awarded {
        void onAwarded(ServerPlayer player, Advancement advancement);
    }
}
