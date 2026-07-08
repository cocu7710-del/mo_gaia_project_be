package com.gaiaproject.mo_gaia_project_be.application;

import com.gaiaproject.mo_gaia_project_be.engine.GameEngine;
import com.gaiaproject.mo_gaia_project_be.engine.rules.GameData;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {

    @Bean
    public GameData gameData() {
        return GameData.load();
    }

    @Bean
    public GameEngine gameEngine(GameData gameData) {
        return new GameEngine(gameData);
    }
}
