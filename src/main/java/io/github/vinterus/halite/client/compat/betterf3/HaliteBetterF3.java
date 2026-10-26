package io.github.vinterus.halite.client.compat.betterf3;

import net.mine_diver.unsafeevents.listener.EventListener;
import net.modificationstation.stationapi.api.util.Namespace;
import ralf2oo2.betterf3.registry.ModuleRegisterEvent;
import ralf2oo2.betterf3.utils.ModulePosition;

/**
 * Registers the "Halite" module with BetterF3. Loaded through BetterF3's own entrypoint, so this class (and with
 * it every BetterF3 reference) is only touched when BetterF3 is installed.
 */
public class HaliteBetterF3 {
    @EventListener
    public void registerModules(ModuleRegisterEvent event) {
        event.register(Namespace.of("halite").id("halite_module"), HaliteModule::new, ModulePosition.RIGHT, "halite");
    }
}
