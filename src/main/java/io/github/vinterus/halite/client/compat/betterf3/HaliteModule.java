package io.github.vinterus.halite.client.compat.betterf3;

import io.github.vinterus.halite.client.HaliteStats;
import net.minecraft.client.Minecraft;
import net.modificationstation.stationapi.api.util.Identifier;
import ralf2oo2.betterf3.modules.BaseModule;
import ralf2oo2.betterf3.utils.DebugLine;

import java.util.List;

/** BetterF3 module showing Halite's renderer statistics and frame timings. */
public class HaliteModule extends BaseModule {
    public HaliteModule(Identifier id) {
        super(id);
        this.defaultNameColor = 0xE0A0C0;
        this.defaultValueColor = 0xFFFFFF;
        this.nameColor = this.defaultNameColor;
        this.valueColor = this.defaultValueColor;

        for (HaliteStats.Entry entry : HaliteStats.entries()) {
            this.lines.add(new DebugLine(entry.id()).withNamespace("halite"));
        }
    }

    @Override
    public void update(Minecraft minecraft) {
        List<HaliteStats.Entry> entries = HaliteStats.entries();
        for (int i = 0; i < entries.size() && i < this.lines.size(); i++) {
            this.lines.get(i).setValue(entries.get(i).value());
        }
    }
}
