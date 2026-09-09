const { Pool } = require('pg');
const fs = require('fs');
const path = require('path');
const config = require('./config');

const pool = new Pool(config.db);

function log(msg) { console.log(`[${new Date().toLocaleTimeString('pt-BR')}] ${msg}`); }

async function query(sql, params) {
  const res = await pool.query(sql, params);
  return res.rows;
}

function salvarLocal(nome, dados) {
  const dir = config.outputDir;
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  const arquivo = path.join(dir, `${nome}.json`);
  const temporario = `${arquivo}.tmp`;
  fs.writeFileSync(temporario, JSON.stringify(dados, null, 2), 'utf8');
  fs.renameSync(temporario, arquivo);
  log(`  -> ${arquivo} (${dados.length} registros, ${(fs.statSync(arquivo).size / 1024).toFixed(0)} KB)`);
}

function esperar(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function criarLotes(dados) {
  if (!dados.length) return [[]];

  const lotes = [];
  let lote = [];
  let bytes = Buffer.byteLength(JSON.stringify({ loja: config.codigoLoja, dados: [] }), 'utf8');

  for (const item of dados) {
    const itemBytes = Buffer.byteLength(JSON.stringify(item), 'utf8') + 1;
    if (itemBytes > config.api.loteBytes) {
      throw new Error(`Um registro excede o limite de ${config.api.loteBytes} bytes`);
    }
    if (lote.length && (lote.length >= config.api.loteRegistros || bytes + itemBytes > config.api.loteBytes)) {
      lotes.push(lote);
      lote = [];
      bytes = Buffer.byteLength(JSON.stringify({ loja: config.codigoLoja, dados: [] }), 'utf8');
    }
    lote.push(item);
    bytes += itemBytes;
  }

  if (lote.length) lotes.push(lote);
  return lotes;
}

async function postarLote(url, modulo, dados) {
  const payload = JSON.stringify({ loja: config.codigoLoja, dados });
  let ultimoErro;

  for (let tentativa = 1; tentativa <= config.api.maxTentativas; tentativa++) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.api.timeoutMs);

    try {
      const resp = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${config.api.token}`,
          'X-Loja': config.codigoLoja,
        },
        body: payload,
        signal: controller.signal,
      });

      const body = await resp.text();
      let result = {};
      try { result = body ? JSON.parse(body) : {}; } catch {}

      if (resp.ok) return result;

      const mensagem = result.erro || body.slice(0, 200) || resp.statusText;
      const erro = new Error(`API ${resp.status}: ${mensagem}`);
      erro.transitorio = resp.status === 408 || resp.status === 429 || resp.status >= 500;
      throw erro;
    } catch (err) {
      if (err.name === 'AbortError') {
        ultimoErro = new Error(`API excedeu o tempo limite de ${config.api.timeoutMs / 1000}s`);
        ultimoErro.transitorio = true;
      } else {
        ultimoErro = err;
        if (err.transitorio === undefined) err.transitorio = err instanceof TypeError;
      }

      if (!ultimoErro.transitorio || tentativa === config.api.maxTentativas) break;
      const atraso = 1000 * (2 ** (tentativa - 1));
      log(`  -> Falha temporaria em ${modulo}; nova tentativa em ${atraso / 1000}s...`);
      await esperar(atraso);
    } finally {
      clearTimeout(timeout);
    }
  }

  throw ultimoErro;
}

async function enviarAPI(modulo, dados) {
  const url = `${config.api.url}/api/sync/${encodeURIComponent(modulo)}`;
  const lotes = criarLotes(dados);
  let processados = 0;

  log(`  -> Enviando ${dados.length} registros para ${url} em ${lotes.length} lote(s)...`);
  for (let i = 0; i < lotes.length; i++) {
    const result = await postarLote(url, modulo, lotes[i]);
    processados += Number(result.inseridos ?? result.total ?? lotes[i].length);
    log(`  -> Lote ${i + 1}/${lotes.length} OK (${lotes[i].length} registros)`);
  }

  log(`  -> API OK: ${processados} registros processados`);
  return true;
}

async function salvar(nome, dados) {
  salvarLocal(nome, dados);
  await enviarAPI(nome, dados);
}

async function detectarParticaoEstoque() {
  const rows = await query(`
    SELECT c.relname,
           substring(c.relname from 8 for 2) AS mm,
           substring(c.relname from 10 for 4) AS yyyy
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relname ~ '^estoque[0-9]{6}$'
    ORDER BY substring(c.relname from 10 for 4) DESC,
             substring(c.relname from 8 for 2) DESC
    LIMIT 12
  `);
  for (const { relname } of rows) {
    const check = await query(`SELECT EXISTS(SELECT 1 FROM public.${relname} WHERE id_loja = $1 LIMIT 1) AS ok`, [config.lojaVrId]);
    if (check[0].ok) return relname;
  }
  return null;
}

// ── LOJAS ────────────────────────────────────────────────────────────

async function syncLojas() {
  log('Sincronizando loja selecionada...');
  const dados = await query(`
    SELECT l.id, l.descricao, l.id_fornecedor, l.id_regiao,
           l.servidorcentral, l.lojavirtual, l.atacado,
           f.cnpj, f.razaosocial, f.nomefantasia
    FROM public.loja l
    LEFT JOIN public.fornecedor f ON f.id = l.id_fornecedor
    WHERE l.id = $1
  `, [config.lojaVrId]);
  if (dados.length !== 1) throw new Error(`Loja VR ${config.lojaVrId} nao encontrada`);
  await salvar('lojas', dados);
  return dados;
}

// ── MERCADOLOGICO ────────────────────────────────────────────────────

async function syncMercadologico() {
  log('Sincronizando mercadologico (categorias)...');
  const dados = await query(`
    SELECT id, mercadologico1, mercadologico2, mercadologico3,
           mercadologico4, mercadologico5, nivel, descricao
    FROM public.mercadologico
    ORDER BY mercadologico1, mercadologico2, mercadologico3
  `);
  await salvar('mercadologico', dados);
  return dados;
}

// ── FORNECEDORES ─────────────────────────────────────────────────────

async function syncFornecedores() {
  log('Sincronizando fornecedores...');
  const dados = await query(`
    SELECT id, razaosocial, nomefantasia, cnpj, id_estado,
           id_situacaocadastro, id_tipopagamento
    FROM public.fornecedor
    WHERE id_situacaocadastro = 1
    ORDER BY id
  `);
  await salvar('fornecedores', dados);
  return dados;
}

// ── PRODUTOS ─────────────────────────────────────────────────────────

async function syncProdutos() {
  log('Sincronizando catalogo de produtos...');
  log('  (reconstruindo a partir de notas de entrada + PDV da loja selecionada)');

  const descricoes = await query(`
    SELECT DISTINCT ON (ni.id_produto)
      ni.id_produto,
      ni.descricaoxml AS descricao,
      ne.id_fornecedor,
      ni.qtdembalagem,
      ni.cfop
    FROM public.notaentradaitem ni
    JOIN public.notaentrada ne ON ne.id = ni.id_notaentrada
    WHERE ne.id_loja = $1
      AND ni.descricaoxml IS NOT NULL AND ni.descricaoxml != ''
    ORDER BY ni.id_produto, ne.id DESC
  `, [config.lojaVrId]);
  log(`  ${descricoes.length} produtos com descricao`);

  const barcodes = await query(`
    SELECT DISTINCT ON (vi.id_produto)
      vi.id_produto, vi.codigobarras
    FROM pdv.vendaitem vi
    JOIN pdv.venda v ON v.id = vi.id_venda
    WHERE v.id_loja = $1
      AND vi.codigobarras IS NOT NULL AND vi.codigobarras > 0
    ORDER BY vi.id_produto, vi.id DESC
  `, [config.lojaVrId]);
  const barcodeMap = new Map(barcodes.map(b => [b.id_produto, b.codigobarras]));
  log(`  ${barcodes.length} produtos com EAN`);

  const particao = await detectarParticaoEstoque();
  let produtosAtivos = new Set();
  if (particao) {
    const ativos = await query(`
      SELECT DISTINCT id_produto FROM public.${particao}
      WHERE id_loja = $1 AND (estoque > 0 OR quantidadevendamedia > 0)
    `, [config.lojaVrId]);
    produtosAtivos = new Set(ativos.map(a => a.id_produto));
    log(`  ${produtosAtivos.size} produtos ativos`);
  }

  const catalogo = descricoes.map(d => ({
    id: d.id_produto,
    descricao: d.descricao,
    ean: barcodeMap.get(d.id_produto) || null,
    id_fornecedor: d.id_fornecedor,
    qtdembalagem: d.qtdembalagem,
    ativo: produtosAtivos.has(d.id_produto),
  }));

  await salvar('produtos', catalogo);
  return catalogo;
}

// ── ESTOQUE ──────────────────────────────────────────────────────────

async function syncEstoque() {
  log('Sincronizando estoque atual...');

  const particao = await detectarParticaoEstoque();
  if (!particao) {
    log('  AVISO: nenhuma particao de estoque encontrada para esta loja');
    await salvar('estoque', []);
    return [];
  }
  log(`  Usando particao: ${particao}`);

  const dados = await query(`
    SELECT id_produto, id_loja, data,
           estoque, quantidadevendamedia,
           custocomimposto, customediocomimposto,
           custosemimposto, customediosemimposto
    FROM public.${particao}
    WHERE id_loja = $1 AND (estoque > 0 OR quantidadevendamedia > 0)
    ORDER BY id_produto
  `, [config.lojaVrId]);

  await salvar('estoque', dados);
  return dados;
}

// ── VENDAS (PDV) ─────────────────────────────────────────────────────

async function syncVendas() {
  log('Sincronizando vendas...');

  const dataInicio = new Date();
  dataInicio.setDate(dataInicio.getDate() - config.diasVendas);
  const dataInicioStr = dataInicio.toISOString().slice(0, 10);
  log(`  Periodo: ${dataInicioStr} ate hoje (${config.diasVendas} dias)`);

  const dados = await query(`
    SELECT v.id_loja, vi.id_produto, v.data,
           SUM(vi.quantidade) AS quantidade,
           SUM(vi.valortotal) AS valortotal,
           SUM(vi.valordesconto) AS desconto,
           SUM(vi.valordescontopromocao) AS desconto_promo,
           AVG(vi.custocomimposto) AS custocomimposto,
           AVG(vi.custosemimposto) AS custosemimposto,
           COUNT(*) AS num_cupons,
           BOOL_OR(vi.oferta) AS teve_oferta
    FROM pdv.venda v
    JOIN pdv.vendaitem vi ON vi.id_venda = v.id
    WHERE v.data >= $1::date
      AND v.id_loja = $2
      AND vi.cancelado = false
    GROUP BY v.id_loja, vi.id_produto, v.data
    ORDER BY v.data DESC, vi.id_produto
  `, [dataInicioStr, config.lojaVrId]);

  await salvar('vendas', dados);
  return dados;
}

// ── OFERTAS ──────────────────────────────────────────────────────────

async function syncOfertas() {
  log('Sincronizando ofertas da loja...');

  const dados = await query(`
    SELECT o.id, o.id AS id_oferta,
           NULL::text AS descricao,
           o.precooferta AS precoconnect,
           NULL::integer AS prioridade,
           NULL::integer AS id_campanha,
           (o.id_situacaooferta <> 1) AS cancelamento
    FROM public.oferta o
    WHERE o.id_loja = $1
    ORDER BY o.id DESC
    LIMIT 10000
  `, [config.lojaVrId]);

  await salvar('ofertas', dados);
  return dados;
}

// ── COMPRAS (NOTAS DE ENTRADA) ───────────────────────────────────────

async function syncCompras() {
  log('Sincronizando notas de entrada (compras)...');

  const dataInicio = new Date();
  dataInicio.setDate(dataInicio.getDate() - config.diasVendas);
  const dataInicioStr = dataInicio.toISOString().slice(0, 10);
  log(`  Periodo: ${dataInicioStr} ate hoje`);

  const dados = await query(`
    SELECT ne.id AS id_nota, ne.id_loja, ne.id_fornecedor,
           ne.dataemissao, ne.dataentrada,
           ne.valortotal AS valor_nota,
           ni.id_produto, ni.descricaoxml AS descricao,
           ni.quantidade, ni.qtdembalagem,
           ni.custocomimposto, ni.valortotal AS valor_item,
           ni.valorbonificacao, ni.valorverba
    FROM public.notaentrada ne
    JOIN public.notaentradaitem ni ON ni.id_notaentrada = ne.id
    WHERE ne.dataentrada >= $1::date AND ne.id_loja = $2
    ORDER BY ne.dataentrada DESC, ne.id
  `, [dataInicioStr, config.lojaVrId]);

  await salvar('compras', dados);
  return dados;
}

// ── VENDAS PROMOCAO ──────────────────────────────────────────────────

async function syncVendasPromocao() {
  log('Sincronizando vendas em promocao...');

  const dataInicio = new Date();
  dataInicio.setDate(dataInicio.getDate() - config.diasVendas);
  const dataInicioStr = dataInicio.toISOString().slice(0, 10);

  const dados = await query(`
    SELECT vpp.id, vpp.id_venda, vpp.id_produto,
           CASE WHEN vpp.quantidade <> 0
             THEN (vpp.valortotal + vpp.valordesconto) / vpp.quantidade
             ELSE NULL END AS preconormal,
           CASE WHEN vpp.quantidade <> 0
             THEN vpp.valortotal / vpp.quantidade
             ELSE NULL END AS precopromocao,
           vpp.quantidade, vpp.valordesconto
    FROM pdv.vendapromocaoproduto vpp
    JOIN pdv.venda v ON v.id = vpp.id_venda
    WHERE v.id_loja = $1 AND v.data >= $2::date
    ORDER BY vpp.id DESC
    LIMIT 50000
  `, [config.lojaVrId, dataInicioStr]);

  await salvar('vendas_promocao', dados);
  return dados;
}

// ── ESTOQUE HISTORICO ────────────────────────────────────────────────

async function syncEstoqueHistorico() {
  log('Sincronizando historico de estoque (ultimos 3 meses, agregado)...');

  const agora = new Date();
  const particoes = [];
  for (let i = 0; i < 3; i++) {
    const d = new Date(agora.getFullYear(), agora.getMonth() - i, 1);
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const yyyy = d.getFullYear();
    particoes.push(`estoque${mm}${yyyy}`);
  }

  const existentes = await query(`
    SELECT c.relname FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = ANY($1)
  `, [particoes]);

  const dados = [];
  for (const { relname } of existentes) {
    log(`  Processando ${relname}...`);
    const rows = await query(`
      SELECT id_loja, id_produto,
             MIN(data) AS data_inicio, MAX(data) AS data_fim,
             AVG(estoque) AS estoque_medio,
             MAX(estoque) AS estoque_max,
             MIN(estoque) AS estoque_min,
             AVG(quantidadevendamedia) AS venda_media,
             AVG(custocomimposto) AS custo_medio
      FROM public.${relname}
      WHERE id_loja = $1
      GROUP BY id_loja, id_produto
      HAVING AVG(estoque) > 0 OR AVG(quantidadevendamedia) > 0
    `, [config.lojaVrId]);
    dados.push(...rows);
  }

  await salvar('estoque_historico', dados);
  return dados;
}

// ── MAIN ─────────────────────────────────────────────────────────────

const MODULOS = {
  lojas: syncLojas,
  mercadologico: syncMercadologico,
  fornecedores: syncFornecedores,
  produtos: syncProdutos,
  estoque: syncEstoque,
  vendas: syncVendas,
  ofertas: syncOfertas,
  compras: syncCompras,
  vendas_promocao: syncVendasPromocao,
  estoque_historico: syncEstoqueHistorico,
};

async function main() {
  const args = process.argv.slice(2);
  const onlyIdx = args.indexOf('--only');
  const allFlag = args.includes('--all');

  let modulos;
  if (onlyIdx >= 0 && args[onlyIdx + 1]) {
    const nomes = args[onlyIdx + 1].split(',');
    modulos = nomes.filter(n => MODULOS[n]);
    if (!modulos.length) {
      throw new Error(`Modulos validos: ${Object.keys(MODULOS).join(', ')}`);
    }
  } else if (allFlag) {
    modulos = Object.keys(MODULOS);
  } else {
    modulos = ['lojas', 'mercadologico', 'fornecedores', 'produtos', 'estoque', 'vendas', 'ofertas', 'compras'];
  }

  log('======================================================');
  log('  VR Sync - Sincronizador Encarte Inteligente');
  log('======================================================');
  log(`Banco: ${config.db.host}:${config.db.port}/${config.db.database}`);
  log(`Loja VR: ${config.lojaVrId}`);
  log(`Loja central: ${config.codigoLoja}`);
  log(`API: ${config.api.url}`);
  log(`Modulos: ${modulos.join(', ')}`);
  log('');

  await pool.query('SELECT 1');
  log('Conexao DB OK');

  const inicio = Date.now();
  const resumo = {};
  let houveErro = false;

  for (const nome of modulos) {
    try {
      const resultado = await MODULOS[nome]();
      resumo[nome] = Array.isArray(resultado) ? resultado.length : 'OK';
    } catch (err) {
      log(`ERRO em ${nome}: ${err.message}`);
      resumo[nome] = `ERRO: ${err.message}`;
      houveErro = true;
    }
    log('');
  }

  const duracao = ((Date.now() - inicio) / 1000).toFixed(1);
  log('======================================================');
  log('  RESUMO');
  log('======================================================');
  for (const [mod, qtd] of Object.entries(resumo)) {
    const label = typeof qtd === 'number' ? `${qtd.toLocaleString('pt-BR')} registros` : qtd;
    log(`  ${mod}: ${label}`);
  }
  log(`  Tempo total: ${duracao}s`);
  log(`  Arquivos em: ${config.outputDir}`);
  log(`  API: ${config.api.url}`);

  if (houveErro) process.exitCode = 1;
}

main()
  .catch(err => {
    console.error('Erro fatal:', err.message);
    process.exitCode = 1;
  })
  .finally(async () => {
    await pool.end().catch(() => {});
  });
