package dev.jetoptimizer;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value = JETOptimizer.MOD_ID, dist = Dist.CLIENT)
public final class JETOptimizer {
    public static final String MOD_ID = "jetoptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JETOptimizer(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, JETOptimizerConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(JETOptimizerProfiler::onRecipesUpdated);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> JETOptimizerProfiler.onLoggingIn());
        NeoForge.EVENT_BUS.addListener(JETOptimizerProfiler::onLoggingOut);
        LOGGER.info("JETOptimizer {} loaded", modContainer.getModInfo().getVersion());
    }
}
