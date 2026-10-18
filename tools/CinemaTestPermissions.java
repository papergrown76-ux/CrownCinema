package de.crown.test;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.proxy.Player;

/** Installed only in the isolated real-Paper test proxy; never packaged in releases. */
public final class CinemaTestPermissions {
    @Subscribe public void setup(PermissionsSetupEvent event) {
        if (event.getSubject() instanceof Player player && (player.getUsername().startsWith("BridgeNew") || player.getUsername().startsWith("BridgeOld")))
            event.setProvider(subject -> PermissionFunction.ALWAYS_TRUE);
    }
}
