/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync;

import com.vrsync.api.ApiClient;
import com.vrsync.config.AppConfig;
import com.vrsync.service.AutoUpdater;
import com.vrsync.service.Scheduler;
import com.vrsync.service.SyncOrchestrator;
import com.vrsync.ui.LoginDialog;
import com.vrsync.ui.SyncWindow;
import com.vrsync.ui.TrayManager;
import java.awt.TrayIcon;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VRSyncApp {
    private static final Logger log = LoggerFactory.getLogger(VRSyncApp.class);
    private final AppConfig config;
    private final SyncOrchestrator orchestrator;
    private final Scheduler scheduler;
    private final TrayManager trayManager;
    private final boolean isService;
    private SyncWindow syncWindow;

    public VRSyncApp(AppConfig config) {
        this.config = config;
        this.orchestrator = new SyncOrchestrator();
        this.scheduler = new Scheduler();
        this.trayManager = new TrayManager();
        this.isService = System.getenv("VR_SYNC_SERVICE") != null;
    }

    public void iniciar() {
        log.info("=== VR Sync v{} iniciando ===", (Object)"4.4.0");
        log.info("Modo: {}", (Object)(this.isService ? "Servico Windows" : "Aplicacao"));
        try {
            AutoUpdater updater = new AutoUpdater();
            boolean atualizado = updater.verificar(this.config.getApiUrl(), this.config.getApiToken());
            if (atualizado) {
                log.info("Atualizacao preparada. Aplicando automaticamente...");
                AutoUpdater.aplicarPendente();
                return;
            }
        }
        catch (Exception e) {
            log.warn("Erro ao verificar atualizacoes: {}", (Object)e.getMessage());
        }
        if (!this.isService) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    this.syncWindow = new SyncWindow(this.config);
                    this.syncWindow.setCallbacks(() -> this.executarSync(), () -> this.encerrar());
                    this.orchestrator.setListener(this.syncWindow);
                    this.syncWindow.mostrar();
                });
            }
            catch (Exception e) {
                log.error("Erro ao criar janela: {}", (Object)e.getMessage());
            }
            this.trayManager.setSyncWindow(() -> {
                if (this.syncWindow != null) {
                    this.syncWindow.mostrar();
                }
            });
            this.trayManager.inicializar(() -> this.executarSync(), () -> this.encerrar());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook executado");
            this.scheduler.parar();
            this.trayManager.remover();
        }, "shutdown-hook"));
        Runnable syncTask = this::executarSync;
        this.scheduler.iniciar(this.config, syncTask);
        ApiClient pollingApi = new ApiClient(this.config.getApiUrl(), this.config.getApiToken());
        this.scheduler.iniciarPolling(pollingApi, this.config.getLoja(), syncTask, this.syncWindow);
        log.info("VR Sync operacional. Proxima sync em {} minutos.", (Object)this.config.getIntervaloMinutos());
        log.info("Disparando primeira sincronizacao...");
        this.scheduler.executarAgora(syncTask);
    }

    private void executarSync() {
        block3: {
            try {
                this.orchestrator.executarSync(this.config);
                String status = this.orchestrator.getStatus();
                if (!this.isService) {
                    this.trayManager.atualizarStatus(status);
                    this.trayManager.notificarSucesso(status);
                }
            }
            catch (Exception e) {
                log.error("Erro na sincronizacao: {}", (Object)e.getMessage());
                if (this.isService) break block3;
                this.trayManager.atualizarStatus("Erro: " + e.getMessage());
                this.trayManager.notificarErro(e.getMessage());
            }
        }
    }

    private void encerrar() {
        log.info("Encerrando VR Sync...");
        this.scheduler.parar();
        this.trayManager.remover();
        System.exit(0);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public static void main(String[] args) {
        log.info("VR Sync - Cliente de Sincronizacao");
        if (AutoUpdater.temAtualizacaoPendente()) {
            log.info("Atualizacao pendente detectada, aplicando...");
            AutoUpdater.aplicarPendente();
            return;
        }
        try {
            AppConfig config = AppConfig.carregar();
            if (config.temSenha() && System.getenv("VR_SYNC_SERVICE") == null) {
                LoginDialog login = new LoginDialog(config.getSenha());
                login.setVisible(true);
                if (!login.isAutenticado()) {
                    log.info("Autenticacao cancelada pelo usuario");
                    System.exit(0);
                }
                log.info("Autenticacao bem-sucedida");
            }
            VRSyncApp app = new VRSyncApp(config);
            app.iniciar();
            if (System.getenv("VR_SYNC_SERVICE") == null) return;
            Class<VRSyncApp> clazz = VRSyncApp.class;
            synchronized (VRSyncApp.class) {
                VRSyncApp.class.wait();
                // ** MonitorExit[var3_4] (shouldn't be in output)
                return;
            }
        }
        catch (Exception e) {
            log.error("Erro fatal: {}", (Object)e.getMessage(), (Object)e);
            System.exit(1);
        }
    }
}

