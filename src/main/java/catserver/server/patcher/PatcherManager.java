package catserver.server.patcher;

import catserver.server.patcher.plugins.EssentialsPatcher;
import catserver.server.patcher.plugins.WorldEditPatcher;

import java.util.HashMap;
import java.util.Map;

public class PatcherManager {
    private static Map<String, IPatcher> pluginPatcher = new HashMap<>();

    static {
        registerPluginPatcher("Essentials", new EssentialsPatcher());
        registerPluginPatcher("WorldEdit", new WorldEditPatcher());
    }

    public static IPatcher getPluginPatcher(String pluginName) {
        return pluginPatcher.get(pluginName);
    }

    public static boolean registerPluginPatcher(String pluginName, IPatcher patcher) {
        if (!pluginPatcher.containsKey(pluginName) && patcher != null) {
            pluginPatcher.put(pluginName, patcher);
            return true;
        }
        return false;
    }
}
