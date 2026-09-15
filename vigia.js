const config = require('./config');
const { execSync } = require('child_process');
const path = require('path');

const INTERVALO_CHECK_MS = 15 * 60 * 1000;
const INTERVALO_SYNC_FORCAR_MS = 6 * 60 * 60 * 1000;

let ultimoSync = null;

function log(msg) { console.log(`[${new Date().toLocaleTimeString('pt-BR')}] ${msg}`); }

async function verificarServidor() {
  const url = `${config.api.url}/api/sync/config?loja=${encodeURIComponent(config.codigoLoja)}`;

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 30000);

  try {
    const resp = await fetch(url, {
      headers: { Authorization: `Bearer ${config.api.token}` },
      signal: controller.signal,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return await resp.json();
  } catch (err) {
    log(`Erro ao consultar servidor: ${err.message}`);
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function executarSync() {
  log('Iniciando sincronizacao...');
  try {
    execSync(`node "${path.join(__dirname, 'sync.js')}"`, {
      cwd: __dirname,
      stdio: 'inherit',
      timeout: 10 * 60 * 1000,
    });
    ultimoSync = Date.now();
    log('Sincronizacao concluida com sucesso');
  } catch (err) {
    log(`Erro na sincronizacao: ${err.message}`);
  }
}

async function ciclo() {
  const cfg = await verificarServidor();

  if (!cfg) {
    log('Servidor inacessivel, tentando novamente em 15 min...');
    return;
  }

  const agora = Date.now();
  const tempoDesdeSync = ultimoSync ? agora - ultimoSync : Infinity;

  if (cfg.syncSolicitado) {
    log('>>> SYNC SOLICITADO PELO SERVIDOR <<<');
    executarSync();
  } else if (tempoDesdeSync >= INTERVALO_SYNC_FORCAR_MS) {
    log('6h sem sync, executando sync programado...');
    executarSync();
  } else {
    const proxSync = Math.round((INTERVALO_SYNC_FORCAR_MS - tempoDesdeSync) / 60000);
    log(`Nenhuma solicitacao pendente. Proximo sync em ~${proxSync} min`);
  }
}

async function main() {
  log('==============================================');
  log('  VR Sync Vigia v3.0');
  log('==============================================');
  log(`Loja: ${config.codigoLoja}`);
  log(`Servidor: ${config.api.url}`);
  log(`Check a cada: ${INTERVALO_CHECK_MS / 60000} min`);
  log(`Sync forcado a cada: ${INTERVALO_SYNC_FORCAR_MS / 3600000}h`);
  log('');

  log('Executando sync inicial...');
  executarSync();

  log('');
  log('Entrando em modo vigia...');
  log('');

  setInterval(ciclo, INTERVALO_CHECK_MS);
}

main().catch(err => {
  console.error('Erro fatal:', err.message);
  process.exit(1);
});
