package com.huidu.musicboxplus.common.stats.config;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MetricsConfig {
    private final File file;
    private final boolean defaultEnabled;
    private String serverUUID;
    private boolean enabled;
    private boolean logErrors;
    private boolean logSentData;
    private boolean logResponseStatusText;
    private boolean didExistBefore = true;

    public MetricsConfig(File file, boolean defaultEnabled) throws IOException {
        this.file = file;
        this.defaultEnabled = defaultEnabled;
        this.setupConfig();
    }

    public String getServerUUID() {
        return this.serverUUID;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public boolean isLogErrorsEnabled() {
        return this.logErrors;
    }

    public boolean isLogSentDataEnabled() {
        return this.logSentData;
    }

    public boolean isLogResponseStatusTextEnabled() {
        return this.logResponseStatusText;
    }

    public boolean didExistBefore() {
        return this.didExistBefore;
    }

    private void setupConfig() throws IOException {
        if (!this.file.exists()) {
            this.didExistBefore = false;
            this.writeConfig();
        }
        this.readConfig();
        if (this.serverUUID == null) {
            this.writeConfig();
            this.readConfig();
        }
    }

    private void writeConfig() throws IOException {
        ArrayList<String> configContent = new ArrayList<String>();
        configContent.add("# bStats (https://bStats.org) collects some basic information for plugin authors, like");
        configContent.add("# how many people use their plugin and their total player count. It's recommended to keep");
        configContent.add("# bStats enabled, but if you're not comfortable with this, you can turn this setting off.");
        configContent.add("# There is no performance penalty associated with having metrics enabled, and data sent to");
        configContent.add("# bStats is fully anonymous.");
        configContent.add("enabled=" + this.defaultEnabled);
        configContent.add("server-uuid=" + UUID.randomUUID().toString());
        configContent.add("log-errors=false");
        configContent.add("log-sent-data=false");
        configContent.add("log-response-status-text=false");
        this.writeFile(this.file, configContent);
    }

    private void readConfig() throws IOException {
        List<String> lines = this.readFile(this.file);
        if (lines == null) {
            throw new AssertionError("Content of newly created file is null");
        }
        this.enabled = this.getConfigValue("enabled", lines).map("true"::equals).orElse(true);
        this.serverUUID = this.getConfigValue("server-uuid", lines).orElse(null);
        this.logErrors = this.getConfigValue("log-errors", lines).map("true"::equals).orElse(false);
        this.logSentData = this.getConfigValue("log-sent-data", lines).map("true"::equals).orElse(false);
        this.logResponseStatusText = this.getConfigValue("log-response-status-text", lines).map("true"::equals).orElse(false);
    }

    private Optional<String> getConfigValue(String key, List<String> lines) {
        return lines.stream().filter(line -> line.startsWith(key + "=")).map(line -> line.replaceFirst(Pattern.quote(key + "="), "")).findFirst();
    }

    private List<String> readFile(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        try (FileReader fileReader = new FileReader(file);){
            List<String> list;
            try (BufferedReader bufferedReader = new BufferedReader(fileReader);){
                list = bufferedReader.lines().collect(Collectors.toList());
            }
            return list;
        }
    }

    private void writeFile(File file, List<String> lines) throws IOException {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            file.createNewFile();
        }
        try (FileWriter fileWriter = new FileWriter(file);
             BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);){
            for (String line : lines) {
                bufferedWriter.write(line);
                bufferedWriter.newLine();
            }
        }
    }
}

