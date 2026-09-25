const fs = require('fs');
const path = require('path');
const config = require('./config');

function log(msg) { console.log(`[${new Date().toLocaleTimeString('pt-BR')}] ${msg}`); }

async function main() {
  log('=== VR-Sync Atualizador ===');
  log(`API: ${config.api.url}`);
  log('');

  const url = `${config.api.url}/api/sync/update?tipo=node&download=1`;
  log('Baixando bundle atualizado...');

  const resp = await fetch(url, {
    headers: { Authorization: `Bearer ${config.api.token}` },
  });

  if (!resp.ok) {
    log(`ERRO: servidor retornou ${resp.status}`);
    process.exit(1);
  }

  const bundle = JSON.parse(await resp.text());
  log(`Versao no servidor: v${bundle.versao}`);

  if (!bundle.arquivos) {
    log('ERRO: bundle sem arquivos');
    process.exit(1);
  }

  for (const [nome, conteudoB64] of Object.entries(bundle.arquivos)) {
    if (!/^[a-zA-Z0-9_.-]+\.js$/.test(nome)) continue;
    const destino = path.join(__dirname, nome);
    const backup = `${destino}.bak`;
    const conteudo = Buffer.from(conteudoB64, 'base64').toString('utf8');

    if (fs.existsSync(destino)) {
      fs.copyFileSync(destino, backup);
      log(`  Backup: ${nome} -> ${nome}.bak`);
    }

    fs.writeFileSync(destino, conteudo, 'utf8');
    log(`  Atualizado: ${nome} (${(conteudo.length / 1024).toFixed(1)} KB)`);
  }

  log('');
  log(`Atualizacao para v${bundle.versao} concluida!`);
  log('Reinicie o vigia para aplicar.');
}

main().catch(err => {
  console.error('Erro fatal:', err.message);
  process.exit(1);
});
