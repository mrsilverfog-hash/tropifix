package com.tropimon.tropifix;

import com.tropimon.tropifix.timer.EventTimer;
import net.fabricmc.api.ClientModInitializer;

/** Point d'entrée client : fonctionnalités actives en continu (les correctifs restent des mixins). */
public class TropiFixClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EventTimer.register();
    }
}
