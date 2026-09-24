/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.service;

import com.vrsync.api.ApiClient;
import com.vrsync.config.AppConfig;
import com.vrsync.ui.SyncListener;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Scheduler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> tarefaAgendada;
    private ScheduledFuture<?> tarefaPolling;
    private volatile Instant proximoSync;
    private int intervaloMinutos;

    public void iniciar(AppConfig config, Runnable tarefa) {
        this.parar();
        this.intervaloMinutos = config.getIntervaloMinutos();
        this.executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t2 = new Thread(r, "vr-sync-scheduler");
            t2.setDaemon(true);
            return t2;
        });
        Runnable wrapper = () -> {
            try {
                tarefa.run();
            }
            catch (Exception e) {
                log.error("Erro na tarefa agendada: {}", (Object)e.getMessage());
            }
            finally {
                this.proximoSync = Instant.now().plus((long)this.intervaloMinutos, ChronoUnit.MINUTES);
            }
        };
        this.tarefaAgendada = this.executor.scheduleAtFixedRate(wrapper, this.intervaloMinutos, this.intervaloMinutos, TimeUnit.MINUTES);
        this.proximoSync = Instant.now().plus((long)this.intervaloMinutos, ChronoUnit.MINUTES);
        log.info("Scheduler iniciado: intervalo de {} minutos", (Object)this.intervaloMinutos);
    }

    public void iniciarPolling(ApiClient api, String loja, Runnable onSyncSolicitado, SyncListener listener) {
        if (this.executor == null || this.executor.isShutdown()) {
            return;
        }
        Runnable polling = () -> {
            block5: {
                try {
                    boolean solicitado = api.verificarSolicitacao(loja);
                    if (!solicitado) break block5;
                    log.info("Sync solicitado remotamente pelo servidor");
                    if (listener != null) {
                        try {
                            listener.onLog("Sync solicitado remotamente pelo servidor");
                        }
                        catch (Exception exception) {
                            // empty catch block
                        }
                    }
                    onSyncSolicitado.run();
                }
                catch (Exception e) {
                    log.debug("Erro ao verificar solicitacao: {}", (Object)e.getMessage());
                }
            }
        };
        this.tarefaPolling = this.executor.scheduleAtFixedRate(polling, 1L, 2L, TimeUnit.MINUTES);
        log.info("Polling de solicitacao remota iniciado: intervalo de 2 minutos");
    }

    public void parar() {
        if (this.tarefaPolling != null) {
            this.tarefaPolling.cancel(false);
            this.tarefaPolling = null;
        }
        if (this.tarefaAgendada != null) {
            this.tarefaAgendada.cancel(false);
            this.tarefaAgendada = null;
        }
        if (this.executor != null) {
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    this.executor.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                this.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            this.executor = null;
        }
        this.proximoSync = null;
        log.info("Scheduler parado");
    }

    public void executarAgora(Runnable tarefa) {
        if (this.executor != null && !this.executor.isShutdown()) {
            this.executor.submit(() -> {
                try {
                    tarefa.run();
                }
                catch (Exception e) {
                    log.error("Erro na execucao imediata: {}", (Object)e.getMessage());
                }
            });
            log.info("Execucao imediata disparada");
        } else {
            log.warn("Scheduler nao iniciado, executando na thread atual");
            tarefa.run();
        }
    }

    public Instant getProximoSync() {
        return this.proximoSync;
    }

    public boolean isAtivo() {
        return this.executor != null && !this.executor.isShutdown();
    }

    public int getIntervaloMinutos() {
        return this.intervaloMinutos;
    }
}

