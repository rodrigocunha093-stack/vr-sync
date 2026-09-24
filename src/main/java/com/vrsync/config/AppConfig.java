/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private String loja;
    private String apiUrl;
    private String apiToken;
    private int intervaloMinutos = 60;
    private String dbServer = "localhost";
    private int dbPort = 1433;
    private String dbName = "VR";
    private String dbUser = "sa";
    private String dbPassword = "";
    private String dbTipo = "mssql";
    private int lojaVrId = 0;
    private String senha = null;
    private Path configPath;
    private static final List<Path> CONFIG_LOCATIONS = List.of(Path.of(System.getenv("APPDATA") != null ? System.getenv("APPDATA") : ".", "VR-Sync", "config.json"), Path.of(System.getProperty("user.dir"), "config.json"), Path.of(System.getProperty("user.home"), "VR-Sync", "config.json"));

    public static AppConfig carregar() {
        for (Path p2 : CONFIG_LOCATIONS) {
            if (!Files.exists(p2, new LinkOption[0])) continue;
            log.info("Config encontrada: {}", (Object)p2);
            return AppConfig.carregarDe(p2);
        }
        log.error("config.json nao encontrado. Locais verificados:");
        CONFIG_LOCATIONS.forEach(p -> log.error("  {}", p));
        throw new RuntimeException("config.json nao encontrado");
    }

    public static AppConfig carregarDe(Path path) {
        try {
            String json = Files.readString(path);
            JsonObject root = new Gson().fromJson(json, JsonObject.class);
            AppConfig cfg = new AppConfig();
            cfg.configPath = path;
            cfg.loja = root.get("loja").getAsString();
            cfg.apiUrl = root.get("apiUrl").getAsString();
            cfg.apiToken = root.get("apiToken").getAsString();
            if (root.has("intervaloMinutos")) {
                cfg.intervaloMinutos = root.get("intervaloMinutos").getAsInt();
            }
            if (root.has("sqlServer")) {
                JsonObject db = root.getAsJsonObject("sqlServer");
                if (db.has("server")) {
                    cfg.dbServer = db.get("server").getAsString();
                }
                if (db.has("port")) {
                    cfg.dbPort = db.get("port").getAsInt();
                }
                if (db.has("database")) {
                    cfg.dbName = db.get("database").getAsString();
                }
                if (db.has("user")) {
                    cfg.dbUser = db.get("user").getAsString();
                }
                if (db.has("password")) {
                    cfg.dbPassword = db.get("password").getAsString();
                }
                if (db.has("tipo")) {
                    cfg.dbTipo = db.get("tipo").getAsString();
                }
            }
            if (root.has("lojaVrId")) {
                cfg.lojaVrId = root.get("lojaVrId").getAsInt();
            }
            if (root.has("senha") && !root.get("senha").isJsonNull()) {
                cfg.senha = root.get("senha").getAsString();
            }
            if (cfg.dbUser.equals("postgres") || cfg.dbPort == 5432) {
                cfg.dbTipo = "postgres";
            }
            log.info("Loja: {} (VR ID: {})", (Object)cfg.loja, (Object)cfg.lojaVrId);
            log.info("API: {}", (Object)cfg.apiUrl);
            log.info("DB: {}:{}/{} ({})", cfg.dbServer, cfg.dbPort, cfg.dbName, cfg.dbTipo);
            return cfg;
        }
        catch (IOException e) {
            throw new RuntimeException("Erro lendo config: " + e.getMessage(), e);
        }
    }

    public String getLoja() {
        return this.loja;
    }

    public String getApiUrl() {
        return this.apiUrl;
    }

    public String getApiToken() {
        return this.apiToken;
    }

    public int getIntervaloMinutos() {
        return this.intervaloMinutos;
    }

    public String getDbServer() {
        return this.dbServer;
    }

    public int getDbPort() {
        return this.dbPort;
    }

    public String getDbName() {
        return this.dbName;
    }

    public String getDbUser() {
        return this.dbUser;
    }

    public String getDbPassword() {
        return this.dbPassword;
    }

    public String getDbTipo() {
        return this.dbTipo;
    }

    public Path getConfigPath() {
        return this.configPath;
    }

    public int getLojaVrId() {
        return this.lojaVrId;
    }

    public String getSenha() {
        return this.senha;
    }

    public boolean temSenha() {
        return this.senha != null && !this.senha.isBlank();
    }

    public boolean isMssql() {
        return "mssql".equalsIgnoreCase(this.dbTipo);
    }

    public boolean isPostgres() {
        return "postgres".equalsIgnoreCase(this.dbTipo);
    }

    public void salvarSenha(String novoHash) {
        try {
            String json = Files.readString(this.configPath);
            JsonObject root = new Gson().fromJson(json, JsonObject.class);
            if (novoHash != null) {
                root.addProperty("senha", novoHash);
            } else {
                root.remove("senha");
            }
            Files.writeString(this.configPath, (CharSequence)new GsonBuilder().setPrettyPrinting().create().toJson(root), new OpenOption[0]);
            this.senha = novoHash;
            log.info("Senha {} com sucesso", (Object)(novoHash != null ? "definida" : "removida"));
        }
        catch (IOException e) {
            log.error("Erro ao salvar senha no config: {}", (Object)e.getMessage());
            throw new RuntimeException("Erro ao salvar config: " + e.getMessage(), e);
        }
    }
}

