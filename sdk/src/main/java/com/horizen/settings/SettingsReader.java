package com.horizen.settings;

import com.horizen.SidechainSettings;
import com.horizen.SidechainSettingsReader;
import com.typesafe.config.Config;

import java.util.Optional;

public class SettingsReader {

    private SidechainSettings sidechainSettings;
    private Config config;

    public SettingsReader (String userConfigPath, Optional<String> applicationConfigPath) {
        this.config = SidechainSettingsReader.readConfigFromPath(userConfigPath, applicationConfigPath);
        this.sidechainSettings = SidechainSettingsReader.fromConfig(this.config);
        // init log4j logging system as soon as possible after having read the settings
        LogInitializer.initLogManager(this.sidechainSettings);
    }

    public SidechainSettings getSidechainSettings() {
        return this.sidechainSettings;
    }

    public Config getConfig() {
        return this.config;
    }

}
