/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiClient {
    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);
    private static final int BATCH_SIZE = 2000;
    private static final int BATCH_SIZE_LARGE = 1000;
    private static final Gson gson = new GsonBuilder().serializeNulls().create();
    private final String apiUrl;
    private final String apiToken;
    private volatile HttpClient httpClient;

    public ApiClient(String apiUrl, String apiToken) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.apiToken = apiToken;
        this.httpClient = criarHttpClient();
    }

    private static HttpClient criarHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30L)).build();
    }

    public JsonObject buscarConfig(String loja) {
        String url = this.apiUrl + "/api/sync/config?loja=" + loja;
        log.info("Buscando config: {}", (Object)url);
        HttpRequest req = this.newGetRequest(url);
        String body = this.enviarRequest(req, "buscarConfig");
        if (body == null) {
            return null;
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    public boolean verificarSolicitacao(String loja) {
        String url = this.apiUrl + "/api/sync/config?loja=" + loja + "&check=1";
        HttpRequest req = this.newGetRequest(url);
        String body = this.enviarRequest(req, "verificarSolicitacao");
        if (body == null) {
            return false;
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.has("syncSolicitado") && json.get("syncSolicitado").getAsBoolean();
        }
        catch (Exception e) {
            log.debug("Erro ao parsear resposta de solicitacao: {}", (Object)e.getMessage());
            return false;
        }
    }

    public int enviarDados(String loja, String modulo, List<Map<String, Object>> dados) {
        return this.enviarDados(loja, modulo, dados, null);
    }

    public int enviarDados(String loja, String modulo, List<Map<String, Object>> dados, BatchProgress progress) {
        String url = this.apiUrl + "/api/sync/" + modulo;
        int total = dados.size();
        int batchSize = total > 50000 ? 1000 : 2000;
        int lotes = (int)Math.ceil((double)total / (double)batchSize);
        int maxRetries = 3;
        int enviados = 0;
        log.info("Enviando {}: {} registros em {} lote(s) (batch={})", modulo, total, lotes, batchSize);
        for (int i = 0; i < lotes; ++i) {
            int from = i * batchSize;
            int to = Math.min(from + batchSize, total);
            List<Map<String, Object>> batch = dados.subList(from, to);
            if (progress != null) {
                progress.onBatch(modulo, total, i + 1, lotes);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("loja", loja);
            payload.add("dados", gson.toJsonTree(batch));
            boolean loteOk = false;
            for (int tentativa = 1; tentativa <= maxRetries; ++tentativa) {
                long t0 = System.currentTimeMillis();
                HttpRequest req = this.newPostRequest(url, payload.toString());
                String resp = this.enviarRequest(req, modulo + " lote " + (i + 1));
                long elapsed = System.currentTimeMillis() - t0;
                if (resp != null) {
                    log.info("{}: lote {}/{} enviado ({} registros, {}ms)", modulo, i + 1, lotes, batch.size(), elapsed);
                    loteOk = true;
                    break;
                }
                if (tentativa >= maxRetries) continue;
                long espera = (long)tentativa * 3000L;
                log.warn("{}: lote {}/{} falhou (tentativa {}/{}), aguardando {}s antes de retry", modulo, i + 1, lotes, tentativa, maxRetries, espera / 1000L);
                try {
                    Thread.sleep(espera);
                    continue;
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return enviados;
                }
            }
            if (loteOk) {
                enviados += batch.size();
                continue;
            }
            log.error("Falha definitiva no envio de {} lote {}/{} apos {} tentativas, continuando", modulo, i + 1, lotes, maxRetries);
        }
        return enviados;
    }

    public boolean enviarBulk(String loja, Map<String, List<Map<String, Object>>> modulos) {
        String url = this.apiUrl + "/api/sync/dados";
        JsonObject payload = new JsonObject();
        payload.addProperty("loja", loja);
        payload.add("modulos", gson.toJsonTree(modulos));
        int totalRegistros = modulos.values().stream().mapToInt(List::size).sum();
        log.info("Enviando bulk: {} modulos, {} registros total", (Object)modulos.size(), (Object)totalRegistros);
        HttpRequest req = this.newPostRequest(url, payload.toString());
        String resp = this.enviarRequest(req, "bulk");
        return resp != null;
    }

    public void reportarStatus(String loja, Map<String, Object> status) {
        String url = this.apiUrl + "/api/sync/status";
        JsonObject payload = new JsonObject();
        payload.addProperty("loja", loja);
        payload.add("status", gson.toJsonTree(status));
        HttpRequest req = this.newPostRequest(url, payload.toString());
        this.enviarRequest(req, "status");
    }

    public JsonObject verificarAtualizacao(String versaoLocal) {
        String url = this.apiUrl + "/api/sync/update?version=" + versaoLocal;
        log.info("Verificando atualizacao: versao local {}", (Object)versaoLocal);
        HttpRequest req = this.newGetRequest(url);
        String body = this.enviarRequest(req, "verificarAtualizacao");
        if (body == null) {
            return null;
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    public JsonObject baixarAtualizacao() {
        String url = this.apiUrl + "/api/sync/update?download=1";
        log.info("Baixando atualizacao...");
        HttpRequest req = this.newGetRequest(url);
        String body = this.enviarRequest(req, "baixarAtualizacao");
        if (body == null) {
            return null;
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private HttpRequest newGetRequest(String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60L)).header("Authorization", "Bearer " + this.apiToken).header("x-app-key", this.apiToken).header("Content-Type", "application/json").GET().build();
    }

    private HttpRequest newPostRequest(String url, String json) {
        return HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMinutes(2L)).header("Authorization", "Bearer " + this.apiToken).header("x-app-key", this.apiToken).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private String enviarRequest(HttpRequest request, String label) {
        try {
            HttpResponse<String> resp = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                log.debug("{}: HTTP {} OK", (Object)label, (Object)status);
                return resp.body();
            }
            log.error("{}: HTTP {} - {}", label, status, resp.body());
            return null;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{}: requisicao interrompida", (Object)label);
            return null;
        }
        catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("selector manager") || msg.contains("channel closed")) {
                log.warn("{}: HttpClient morreu ({}), recriando...", (Object)label, (Object)msg);
                this.httpClient = criarHttpClient();
            }
            log.error("{}: erro na requisicao - {}", (Object)label, (Object)msg);
            return null;
        }
    }

    @FunctionalInterface
    public static interface BatchProgress {
        public void onBatch(String var1, int var2, int var3, int var4);
    }
}

