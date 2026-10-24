package io.github.vinterus.halite.client;

import io.github.vinterus.halite.client.config.HaliteOptions;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HaliteClientMod implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Halite");

    private static HaliteOptions options;

    @Override
    public void onInitializeClient() {
        options = HaliteOptions.load();
        LOGGER.info("Halite initialized");
    }

    public static HaliteOptions options() {
        if (options == null) {
            options = HaliteOptions.load();
        }
        return options;
    }

    public static Logger logger() {
        return LOGGER;
    }
}
