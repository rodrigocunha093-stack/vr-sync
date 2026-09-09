const { Pool } = require('pg');
const config = require('./config');

const pool = new Pool({
  ...config.db,
  max: 1,
  connectionTimeoutMillis: 10000,
});

async function verificarBanco() {
  console.log(`Banco: testando ${config.db.host}:${config.db.port}/${config.db.database} (usuario ${config.db.user})...`);
  await pool.query('SELECT 1');
  const { rows } = await pool.query(
    'SELECT id, descricao FROM public.loja WHERE id = $1',
    [config.lojaVrId]
  );
  if (rows.length !== 1) {
    throw new Error(`Loja VR ${config.lojaVrId} nao encontrada em public.loja`);
  }
  console.log(`Banco: OK - loja ${rows[0].id} (${rows[0].descricao || 'sem descricao'})`);
}

async function verificarApi() {
  const url = `${config.api.url}/api/sync/__vr_sync_probe__`;
  console.log(`API: testando ${config.api.url}...`);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);

  try {
    const resposta = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.api.token}`,
        'X-Loja': config.codigoLoja,
      },
      body: JSON.stringify({ loja: config.codigoLoja, dados: [] }),
      signal: controller.signal,
    });

    const texto = await resposta.text();
    let body = {};
    try { body = texto ? JSON.parse(texto) : {}; } catch {}

    if (resposta.status === 401) throw new Error('token da API invalido');
    if (resposta.status !== 400 || !String(body.erro || '').toLowerCase().includes('modulo desconhecido')) {
      throw new Error(`resposta inesperada da API (HTTP ${resposta.status})`);
    }
    if (!Array.isArray(body.modulos) || !body.modulos.includes('lojas')) {
      throw new Error('contrato da API incompativel');
    }

    console.log('API: OK - endpoint acessivel e token aceito');
  } catch (err) {
    if (err.name === 'AbortError') throw new Error('API indisponivel: tempo limite excedido');
    if (err instanceof TypeError) throw new Error(`API indisponivel: ${err.message}`);
    throw err;
  } finally {
    clearTimeout(timeout);
  }
}

async function main() {
  console.log('==============================================');
  console.log('  VR Sync - Verificacao de configuracao');
  console.log('==============================================');
  console.log(`Loja central: ${config.codigoLoja}`);
  await verificarBanco();
  await verificarApi();
  console.log('Verificacao concluida com sucesso.');
}

main()
  .catch(err => {
    console.error(`ERRO: ${err.message}`);
    process.exitCode = 1;
  })
  .finally(async () => {
    await pool.end().catch(() => {});
  });
