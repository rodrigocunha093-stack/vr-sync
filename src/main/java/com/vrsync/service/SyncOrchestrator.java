/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vrsync.api.ApiClient;
import com.vrsync.config.AppConfig;
import com.vrsync.extractor.DatabaseConnector;
import com.vrsync.extractor.SqlQueries;
import com.vrsync.ui.SyncListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SyncOrchestrator.class);
    private static final int MAX_REGISTROS_MODULO = 200000;
    private volatile boolean rodando = false;
    private String status = "Aguardando";
    private Instant ultimoSync;
    private SyncListener listener;

    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    private void notificar(Runnable r) {
        if (this.listener != null) {
            try {
                r.run();
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void executarSync(AppConfig config) {
        if (this.rodando) {
            log.warn("Sync ja em execucao, ignorando...");
            return;
        }
        this.rodando = true;
        this.status = "Sincronizando...";
        Instant inicio = Instant.now();
        ApiClient api = new ApiClient(config.getApiUrl(), config.getApiToken());
        try {
            this.status = "Buscando configuracao...";
            JsonObject serverConfig = api.buscarConfig(config.getLoja());
            List<String> modulos = this.getModulos(serverConfig);
            String desde = this.getDesde(serverConfig);
            log.info("Modulos a sincronizar: {}", (Object)modulos);
            log.info("Data de corte: {}", (Object)desde);
            this.notificar(() -> this.listener.onSyncInicio(modulos.size()));
            this.notificar(() -> this.listener.onLog("Conectando ao banco " + config.getDbServer() + "..."));
            LinkedHashMap<String, List<Map<String, Object>>> todosModulos = new LinkedHashMap<String, List<Map<String, Object>>>();
            int totalRegistros = 0;
            try (DatabaseConnector db = new DatabaseConnector();){
                this.status = "Conectando ao banco...";
                db.conectar(config.getDbServer(), config.getDbPort(), config.getDbName(), config.getDbUser(), config.getDbPassword(), config.getDbTipo());
                String particaoEstoque = null;
                if (config.isPostgres()) {
                    try {
                        String detectSql = SqlQueries.getPartitionDetectSql();
                        List<Map<String, Object>> list = db.executar(detectSql, "detectar_particao");
                        if (!list.isEmpty()) {
                            particaoEstoque = String.valueOf(list.get(0).values().iterator().next());
                            log.info("Particao estoque detectada: {}", (Object)particaoEstoque);
                        }
                    }
                    catch (Exception e) {
                        log.warn("Falha detectando particao estoque: {}", (Object)e.getMessage());
                    }
                }
                for (String string : modulos) {
                    this.status = "Extraindo: " + string;
                    this.notificar(() -> this.listener.onModuloExtraindo(string));
                    try {
                        long t0 = System.currentTimeMillis();
                        String sql = "estoque".equals(string) && config.isPostgres() && particaoEstoque != null ? SqlQueries.getEstoquePgSql(particaoEstoque, config.getLojaVrId()) : SqlQueries.getQuery(string, desde, config.getDbTipo(), config.getLojaVrId());
                        List<Map<String, Object>> dados = db.executar(sql, string);
                        if (dados.size() > 200000) {
                            log.warn("{}: truncando de {} para {} registros", string, dados.size(), 200000);
                            dados = new ArrayList<Map<String, Object>>(dados.subList(0, 200000));
                        }
                        todosModulos.put(string, dados);
                        totalRegistros += dados.size();
                        long elapsed = System.currentTimeMillis() - t0;
                        int count = dados.size();
                        this.notificar(() -> this.listener.onModuloExtraido(string, count, elapsed));
                    }
                    catch (Throwable t) {
                        log.error("Erro extraindo {}: {}", (Object)string, (Object)t.getMessage(), (Object)t);
                        String erroMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                        this.notificar(() -> this.listener.onModuloErro(string, erroMsg));
                    }
                }
            }
            log.info("Extracao concluida: {} registros em {} modulos", (Object)totalRegistros, (Object)todosModulos.size());
            int totalEnviados = 0;
            if (totalRegistros > 0) {
                this.status = "Enviando dados...";
                this.notificar(() -> this.listener.onLog("Enviando dados ao servidor..."));
                ApiClient.BatchProgress batchProgress = (mod, registros, loteAtual, totalLotes) -> this.notificar(() -> this.listener.onEnviando(mod, registros, loteAtual, totalLotes));
                ArrayList<String> modulosChaves = new ArrayList<>(todosModulos.keySet());
                for (String modulo : modulosChaves) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> dados = (List<Map<String, Object>>)todosModulos.get(modulo);
                    if (dados != null && !dados.isEmpty()) {
                        this.status = "Enviando: " + modulo + " (" + dados.size() + ")";
                        int enviados = api.enviarDados(config.getLoja(), modulo, dados, batchProgress);
                        boolean ok = enviados == dados.size();
                        this.notificar(() -> this.listener.onEnviado(modulo, ok));
                        totalEnviados += enviados;
                    }
                    todosModulos.put(modulo, null);
                }
                log.info("Envio concluido: {} registros enviados com sucesso", (Object)totalEnviados);
            } else {
                log.info("Nenhum registro para enviar");
            }
            long duracaoMs = Instant.now().toEpochMilli() - inicio.toEpochMilli();
            LinkedHashMap<String, Object> linkedHashMap = new LinkedHashMap<String, Object>();
            linkedHashMap.put("sucesso", true);
            linkedHashMap.put("registros", totalRegistros);
            linkedHashMap.put("modulos", todosModulos.size());
            linkedHashMap.put("duracao_ms", duracaoMs);
            linkedHashMap.put("timestamp", Instant.now().toString());
            api.reportarStatus(config.getLoja(), linkedHashMap);
            this.ultimoSync = Instant.now();
            this.status = totalEnviados == totalRegistros ? String.format("Concluido: %d registros em %.1fs", totalRegistros, (double)duracaoMs / 1000.0) : String.format("Concluido: %d/%d registros em %.1fs", totalEnviados, totalRegistros, (double)duracaoMs / 1000.0);
            log.info("Sync concluida: {}", (Object)this.status);
            int tr = totalRegistros;
            long dm = duracaoMs;
            this.notificar(() -> this.listener.onSyncFim(tr, dm, true, null));
        }
        catch (Exception e) {
            this.status = "Erro: " + e.getMessage();
            log.error("Erro na sincronizacao: {}", (Object)e.getMessage(), (Object)e);
            long duracaoErro = Instant.now().toEpochMilli() - inicio.toEpochMilli();
            this.notificar(() -> this.listener.onSyncFim(0, duracaoErro, false, e.getMessage()));
            try {
                LinkedHashMap<String, Object> statusReport = new LinkedHashMap<String, Object>();
                statusReport.put("sucesso", false);
                statusReport.put("erro", e.getMessage());
                statusReport.put("timestamp", Instant.now().toString());
                api.reportarStatus(config.getLoja(), statusReport);
            }
            catch (Exception ex) {
                log.error("Erro ao reportar falha: {}", (Object)ex.getMessage());
            }
        }
        finally {
            this.rodando = false;
        }
    }

    private List<String> getModulos(JsonObject serverConfig) {
        if (serverConfig != null && serverConfig.has("modulos")) {
            JsonArray arr = serverConfig.getAsJsonArray("modulos");
            ArrayList<String> list = new ArrayList<String>();
            arr.forEach(e -> list.add(e.getAsString()));
            return list;
        }
        return List.of("lojas", "mercadologico", "fornecedores", "produtos", "vendas", "estoque", "ofertas", "compras", "vendas_promocao", "cupom_itens", "margem", "precos");
    }

    private String getDesde(JsonObject serverConfig) {
        JsonObject filtros;
        if (serverConfig != null && serverConfig.has("filtros") && (filtros = serverConfig.getAsJsonObject("filtros")).has("vendas_desde")) {
            return filtros.get("vendas_desde").getAsString();
        }
        return LocalDate.now().minusDays(90L).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public String getStatus() {
        return this.status;
    }

    public boolean isRodando() {
        return this.rodando;
    }

    public Instant getUltimoSync() {
        return this.ultimoSync;
    }
}

