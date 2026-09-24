/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.service;

import com.google.gson.JsonObject;
import com.vrsync.api.ApiClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoUpdater {
    private static final Logger log = LoggerFactory.getLogger(AutoUpdater.class);
    public static final String VERSAO_ATUAL = "4.4.0";
    private static final String VERSION_FILE = ".vr-sync-version";

    public boolean verificar(String apiUrl, String apiToken) {
        try {
            boolean atualizar;
            ApiClient api = new ApiClient(apiUrl, apiToken);
            String versaoLocal = this.lerVersaoLocal();
            log.info("Verificando atualizacao: versao local {}", (Object)versaoLocal);
            JsonObject resp = api.verificarAtualizacao(versaoLocal);
            if (resp == null) {
                log.warn("Nao foi possivel verificar atualizacao");
                return false;
            }
            boolean bl = atualizar = resp.has("atualizar") && resp.get("atualizar").getAsBoolean();
            if (!atualizar) {
                log.info("Nenhuma atualizacao disponivel");
                return false;
            }
            String novaVersao = resp.has("versao") ? resp.get("versao").getAsString() : "desconhecida";
            log.info("Atualizacao disponivel: {} -> {}", (Object)versaoLocal, (Object)novaVersao);
            return this.baixarEAplicar(api, novaVersao);
        }
        catch (Exception e) {
            log.error("Erro ao verificar atualizacao: {}", (Object)e.getMessage());
            return false;
        }
    }

    private boolean baixarEAplicar(ApiClient api, String novaVersao) {
        try {
            JsonObject bundle = api.baixarAtualizacao();
            if (bundle == null) {
                log.error("Falha ao baixar atualizacao");
                return false;
            }
            if (!bundle.has("jar")) {
                log.error("Bundle de atualizacao nao contem JAR");
                return false;
            }
            String jarBase64 = bundle.get("jar").getAsString();
            byte[] jarBytes = Base64.getDecoder().decode(jarBase64);
            Path jarAtual = this.getJarPath();
            if (jarAtual == null) {
                log.error("Nao foi possivel determinar caminho do JAR atual");
                return false;
            }
            Path appDir = jarAtual.getParent();
            Path tempJar = appDir.resolve("vr-sync.jar.update");
            Files.write(tempJar, jarBytes, new OpenOption[0]);
            Path exePath = appDir.getParent().resolve("VR-Sync.exe");
            Path updateScript = appDir.resolve("update.bat");
            Path logFile = appDir.resolve("update.log");
            String script = "@echo off\r\n"
                + "echo [%date% %time%] Update iniciando >> \"" + logFile.toAbsolutePath() + "\"\r\n"
                + "timeout /t 3 /nobreak >nul\r\n"
                + "set TENTATIVAS=0\r\n"
                + ":RETRY\r\n"
                + "set /a TENTATIVAS+=1\r\n"
                + "echo [%date% %time%] Tentativa %TENTATIVAS% de copiar JAR >> \"" + logFile.toAbsolutePath() + "\"\r\n"
                + "copy /Y \"" + tempJar.toAbsolutePath() + "\" \"" + jarAtual.toAbsolutePath() + "\" >nul 2>&1\r\n"
                + "if errorlevel 1 (\r\n"
                + "  echo [%date% %time%] FALHA na copia, tentativa %TENTATIVAS% >> \"" + logFile.toAbsolutePath() + "\"\r\n"
                + "  if %TENTATIVAS% lss 5 (\r\n"
                + "    timeout /t 3 /nobreak >nul\r\n"
                + "    goto RETRY\r\n"
                + "  )\r\n"
                + "  echo [%date% %time%] ERRO: todas as tentativas falharam >> \"" + logFile.toAbsolutePath() + "\"\r\n"
                + "  goto FIM\r\n"
                + ")\r\n"
                + "echo [%date% %time%] JAR copiado com sucesso >> \"" + logFile.toAbsolutePath() + "\"\r\n"
                + "del \"" + tempJar.toAbsolutePath() + "\" >nul 2>&1\r\n"
                + ":FIM\r\n"
                + "start \"\" \"" + exePath.toAbsolutePath() + "\"\r\n"
                + "del \"%~f0\"\r\n";
            Files.writeString(updateScript, (CharSequence)script, StandardCharsets.UTF_8, new OpenOption[0]);
            this.salvarVersao(novaVersao);
            log.info("Atualizacao {} preparada em {}", (Object)novaVersao, (Object)tempJar);
            return true;
        }
        catch (Exception e) {
            log.error("Erro ao aplicar atualizacao: {}", (Object)e.getMessage());
            return false;
        }
    }

    public static boolean temAtualizacaoPendente() {
        try {
            Path jarPath = new AutoUpdater().getJarPath();
            if (jarPath == null) {
                log.warn("temAtualizacaoPendente: jarPath is null, tentando fallback por user.dir");
                Path userDir = Path.of(System.getProperty("user.dir"));
                Path appDir = userDir.resolve("app");
                if (!Files.isDirectory(appDir, new LinkOption[0])) {
                    appDir = userDir;
                }
                Path updateScript = appDir.resolve("update.bat");
                Path updateJar = appDir.resolve("vr-sync.jar.update");
                boolean found = Files.exists(updateScript, new LinkOption[0]) && Files.exists(updateJar, new LinkOption[0]);
                log.info("temAtualizacaoPendente fallback: dir={}, bat={}, jar={}, result={}", appDir, Files.exists(updateScript, new LinkOption[0]), Files.exists(updateJar, new LinkOption[0]), found);
                return found;
            }
            Path updateScript = jarPath.getParent().resolve("update.bat");
            Path updateJar = jarPath.getParent().resolve("vr-sync.jar.update");
            boolean found = Files.exists(updateScript, new LinkOption[0]) && Files.exists(updateJar, new LinkOption[0]);
            log.info("temAtualizacaoPendente: jarPath={}, bat={}, jar={}, result={}", jarPath, Files.exists(updateScript, new LinkOption[0]), Files.exists(updateJar, new LinkOption[0]), found);
            return found;
        }
        catch (Exception e) {
            log.error("temAtualizacaoPendente: erro - {}", (Object)e.getMessage());
            return false;
        }
    }

    public static void aplicarPendente() {
        try {
            Path appDir = null;
            Path jarPath = new AutoUpdater().getJarPath();
            if (jarPath != null) {
                appDir = jarPath.getParent();
            } else {
                log.warn("aplicarPendente: jarPath null, usando fallback");
                Path userDir = Path.of(System.getProperty("user.dir"));
                Path candidate = userDir.resolve("app");
                appDir = Files.isDirectory(candidate, new LinkOption[0]) ? candidate : userDir;
            }
            Path updateScript = appDir.resolve("update.bat");
            if (!Files.exists(updateScript, new LinkOption[0])) {
                log.warn("aplicarPendente: update.bat nao encontrado em {}", (Object)appDir);
                return;
            }
            log.info("Aplicando atualizacao pendente de {}...", (Object)updateScript);
            new ProcessBuilder("cmd", "/c", "start", "/min", "", updateScript.toAbsolutePath().toString()).directory(appDir.toFile()).start();
            System.exit(0);
        }
        catch (Exception e) {
            log.error("Erro ao aplicar atualizacao pendente: {}", (Object)e.getMessage());
        }
    }

    public String lerVersaoLocal() {
        Path versionFile = this.getVersionFilePath();
        try {
            if (Files.exists(versionFile, new LinkOption[0])) {
                return Files.readString(versionFile).trim();
            }
        }
        catch (IOException e) {
            log.warn("Erro lendo versao local: {}", (Object)e.getMessage());
        }
        return VERSAO_ATUAL;
    }

    private void salvarVersao(String versao) {
        try {
            Path versionFile = this.getVersionFilePath();
            Files.writeString(versionFile, (CharSequence)versao, StandardCharsets.UTF_8, new OpenOption[0]);
        }
        catch (IOException e) {
            log.error("Erro salvando versao: {}", (Object)e.getMessage());
        }
    }

    private Path getVersionFilePath() {
        Path jarPath = this.getJarPath();
        if (jarPath != null) {
            return jarPath.resolveSibling(VERSION_FILE);
        }
        return Path.of(System.getProperty("user.dir"), VERSION_FILE);
    }

    private Path getJarPath() {
        try {
            Path p;
            String path = AutoUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            if (path.startsWith("/") && path.contains(":")) {
                path = path.substring(1);
            }
            if (Files.isRegularFile(p = Path.of(path, new String[0]), new LinkOption[0]) && p.toString().endsWith(".jar")) {
                log.debug("getJarPath via CodeSource: {}", (Object)p);
                return p;
            }
            log.debug("CodeSource nao e JAR: {}", (Object)path);
        }
        catch (Exception e) {
            log.debug("Nao executando de JAR: {}", (Object)e.getMessage());
        }
        try {
            Path userDir = Path.of(System.getProperty("user.dir"));
            Path candidate = userDir.resolve("app").resolve("vr-sync.jar");
            if (Files.isRegularFile(candidate, new LinkOption[0])) {
                log.debug("getJarPath via fallback (user.dir/app): {}", (Object)candidate);
                return candidate;
            }
            candidate = userDir.resolve("vr-sync.jar");
            if (Files.isRegularFile(candidate, new LinkOption[0])) {
                log.debug("getJarPath via fallback (user.dir): {}", (Object)candidate);
                return candidate;
            }
        }
        catch (Exception e) {
            log.debug("Fallback getJarPath falhou: {}", (Object)e.getMessage());
        }
        return null;
    }
}

