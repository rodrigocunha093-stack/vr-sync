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

// ── BUSCAR CONFIG DO SERVIDOR ───────────────────────────────────────

async function buscarConfig() {
  const url = `${config.api.url}/api/sync/config?loja=${encodeURIComponent(config.codigoLoja)}`;
  log(`Solicitando configuracao ao servidor: ${url}`);

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.api.timeoutMs);

  try {
    const resp = await fetch(url, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${config.api.token}`,
      },
      signal: controller.signal,
    });

    const body = await resp.text();
    if (!resp.ok) {
      throw new Error(`Servidor retornou ${resp.status}: ${body.slice(0, 200)}`);
    }

    const cfg = JSON.parse(body);
    log(`Configuracao recebida: ${cfg.modulos.length} modulos, vendas_desde=${cfg.filtros.vendas_desde}`);
    return cfg;
  } finally {
    clearTimeout(timeout);
  }
}

// ── ENVIAR DADOS AO SERVIDOR ────────────────────────────────────────

async function enviarDados(modulosData) {
  const url = `${config.api.url}/api/sync/dados`;
  const payload = { loja: config.codigoLoja, modulos: modulosData };
  const json = JSON.stringify(payload);

  log(`Enviando ${(json.length / 1024 / 1024).toFixed(1)} MB para ${url}...`);

  let ultimoErro;
  for (let tentativa = 1; tentativa <= config.api.maxTentativas; tentativa++) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.api.timeoutMs * 3);

    try {
      const resp = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${config.api.token}`,
        },
        body: json,
        signal: controller.signal,
      });

      const body = await resp.text();
      let result = {};
      try { result = body ? JSON.parse(body) : {}; } catch {}

      if (resp.ok || resp.status === 207) return result;

      const mensagem = result.erro || body.slice(0, 200) || resp.statusText;
      const erro = new Error(`API ${resp.status}: ${mensagem}`);
      erro.transitorio = resp.status === 408 || resp.status === 429 || resp.status >= 500;
      throw erro;
    } catch (err) {
      if (err.name === 'AbortError') {
        ultimoErro = new Error(`API excedeu o tempo limite`);
        ultimoErro.transitorio = true;
      } else {
        ultimoErro = err;
        if (err.transitorio === undefined) err.transitorio = err instanceof TypeError;
      }

      if (!ultimoErro.transitorio || tentativa === config.api.maxTentativas) break;
      const atraso = 1000 * (2 ** (tentativa - 1));
      log(`  -> Falha temporaria; nova tentativa em ${atraso / 1000}s...`);
      await esperar(atraso);
    } finally {
      clearTimeout(timeout);
    }
  }

  throw ultimoErro;
}

// ── ENVIAR EM LOTES (para payloads grandes) ─────────────────────────

async function enviarEmLotes(modulosData) {
  const json = JSON.stringify({ loja: config.codigoLoja, modulos: modulosData });
  const MAX_PAYLOAD = config.api.loteBytes;

  if (json.length <= MAX_PAYLOAD) {
    return enviarDados(modulosData);
  }

  log(`Payload de ${(json.length / 1024 / 1024).toFixed(1)} MB excede limite; enviando em lotes por modulo...`);
  const resultadoFinal = { ok: true, loja: config.codigoLoja, totalRegistros: 0, resultados: {} };

  for (const [modulo, dados] of Object.entries(modulosData)) {
    if (!dados.length) {
      resultadoFinal.resultados[modulo] = 0;
      continue;
    }

    const loteSize = config.api.loteRegistros;
    let processados = 0;
    const totalLotes = Math.ceil(dados.length / loteSize);

    for (let i = 0; i < dados.length; i += loteSize) {
      const lote = dados.slice(i, i + loteSize);
      const loteNum = Math.floor(i / loteSize) + 1;
      log(`  -> ${modulo}: lote ${loteNum}/${totalLotes} (${lote.length} registros)`);

      const result = await enviarDados({ [modulo]: lote });
      processados += result.resultados?.[modulo] || lote.length;
    }

    resultadoFinal.resultados[modulo] = processados;
    resultadoFinal.totalRegistros += processados;
  }

  return resultadoFinal;
}

// ── EXTRATORES ──────────────────────────────────────────────────────

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

  const tabela = await query(`
    SELECT c.relname FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = 'estoque' AND c.relkind = 'r'
  `);
  if (tabela.length > 0) {
    const check = await query(`SELECT EXISTS(SELECT 1 FROM public.estoque WHERE id_loja = $1 LIMIT 1) AS ok`, [config.lojaVrId]);
    if (check[0].ok) return 'estoque';
  }

  return null;
}

async function extrairLojas() {
  log('Extraindo loja...');
  const dados = await query(`
    SELECT l.id, l.descricao, l.id_fornecedor, l.id_regiao,
           l.servidorcentral, l.lojavirtual, l.atacado,
           f.cnpj, f.razaosocial, f.nomefantasia
    FROM public.loja l
    LEFT JOIN public.fornecedor f ON f.id = l.id_fornecedor
    WHERE l.id = $1
  `, [config.lojaVrId]);
  if (dados.length !== 1) throw new Error(`Loja VR ${config.lojaVrId} nao encontrada`);
  return dados;
}

async function extrairMercadologico() {
  log('Extraindo mercadologico...');
  return query(`
    SELECT id, mercadologico1, mercadologico2, mercadologico3,
           mercadologico4, mercadologico5, nivel, descricao
    FROM public.mercadologico
    ORDER BY mercadologico1, mercadologico2, mercadologico3
  `);
}

async function extrairFornecedores() {
  log('Extraindo fornecedores...');
  return query(`
    SELECT id, razaosocial, nomefantasia, cnpj, id_estado,
           id_situacaocadastro, id_tipopagamento
    FROM public.fornecedor
    WHERE id_situacaocadastro = 1
    ORDER BY id
  `);
}

async function extrairProdutos() {
  log('Extraindo produtos...');
  const descricoes = await query(`
    SELECT DISTINCT ON (ni.id_produto)
      ni.id_produto,
      ni.descricaoxml AS descricao,
      ne.id_fornecedor,
      ni.qtdembalagem,
      ni.cfop,
      p.mercadologico1,
      p.descricaocompleta AS descricao_cadastro,
      p.id AS codigo_interno
    FROM public.notaentradaitem ni
    JOIN public.notaentrada ne ON ne.id = ni.id_notaentrada
    LEFT JOIN public.produto p ON p.id = ni.id_produto
    WHERE ne.id_loja = $1
      AND ni.descricaoxml IS NOT NULL AND ni.descricaoxml != ''
    ORDER BY ni.id_produto, ne.id DESC
  `, [config.lojaVrId]);

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

  const particao = await detectarParticaoEstoque();
  let produtosAtivos = new Set();
  if (particao) {
    const ativos = await query(`
      SELECT DISTINCT id_produto FROM public.${particao}
      WHERE id_loja = $1 AND (estoque > 0 OR quantidadevendamedia > 0)
    `, [config.lojaVrId]);
    produtosAtivos = new Set(ativos.map(a => a.id_produto));
  }

  return descricoes.map(d => ({
    id: d.id_produto,
    descricao: d.descricao,
    descricao_cadastro: d.descricao_cadastro || null,
    codigo_interno: d.codigo_interno || d.id_produto,
    ean: barcodeMap.get(d.id_produto) || null,
    id_fornecedor: d.id_fornecedor,
    mercadologico1: d.mercadologico1 || null,
    qtdembalagem: d.qtdembalagem,
    ativo: produtosAtivos.has(d.id_produto),
  }));
}

async function extrairEstoque() {
  log('Extraindo estoque atual...');
  const particao = await detectarParticaoEstoque();
  if (!particao) {
    log('  AVISO: nenhuma particao de estoque encontrada');
    return [];
  }
  log(`  Usando particao: ${particao}`);
  return query(`
    SELECT DISTINCT ON (id_produto)
           id_produto, id_loja, data,
           estoque, quantidadevendamedia,
           custocomimposto, customediocomimposto,
           custosemimposto, customediosemimposto
    FROM public.${particao}
    WHERE id_loja = $1 AND (estoque > 0 OR quantidadevendamedia > 0)
    ORDER BY id_produto, data DESC
  `, [config.lojaVrId]);
}

async function extrairVendas(filtros) {
  const desde = filtros?.vendas_desde || new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  log(`Extraindo vendas desde ${desde}...`);
  return query(`
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
  `, [desde, config.lojaVrId]);
}

async function extrairOfertas() {
  log('Extraindo ofertas...');
  return query(`
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
}

async function extrairCompras(filtros) {
  const desde = filtros?.vendas_desde || new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  log(`Extraindo compras desde ${desde}...`);
  return query(`
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
  `, [desde, config.lojaVrId]);
}

async function extrairVendasPromocao(filtros) {
  const desde = filtros?.vendas_desde || new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  log(`Extraindo vendas em promocao desde ${desde}...`);
  return query(`
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
  `, [config.lojaVrId, desde]);
}

async function extrairMargem(filtros) {
  const desde = filtros?.vendas_desde || new Date(Date.now() - config.diasVendas * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  log(`Extraindo margem realizada desde ${desde}...`);
  return query(`
    SELECT
      vi.id_produto,
      v.data,
      p.mercadologico1,
      SUM(vi.quantidade) AS quantidade,
      SUM(vi.valortotal) AS receita,
      SUM(vi.valordesconto) AS desconto,
      SUM(vi.quantidade * vi.custocomimposto) AS custo_total,
      SUM(vi.valortotal) - SUM(vi.quantidade * vi.custocomimposto) AS margem_bruta,
      CASE WHEN SUM(vi.valortotal) > 0
        THEN ROUND(((SUM(vi.valortotal) - SUM(vi.quantidade * vi.custocomimposto)) / SUM(vi.valortotal) * 100)::numeric, 2)
        ELSE 0
      END AS margem_bruta_pct,
      SUM(vi.quantidade * vi.custosemimposto) AS custo_liquido,
      SUM(vi.valortotal) - SUM(vi.quantidade * vi.custosemimposto) AS margem_liquida,
      CASE WHEN SUM(vi.valortotal) > 0
        THEN ROUND(((SUM(vi.valortotal) - SUM(vi.quantidade * vi.custosemimposto)) / SUM(vi.valortotal) * 100)::numeric, 2)
        ELSE 0
      END AS margem_liquida_pct,
      BOOL_OR(vi.oferta) AS em_oferta,
      COUNT(*) AS num_cupons
    FROM pdv.venda v
    JOIN pdv.vendaitem vi ON vi.id_venda = v.id
    LEFT JOIN public.produto p ON p.id = vi.id_produto
    WHERE v.data >= $1::date
      AND v.id_loja = $2
      AND vi.cancelado = false
      AND vi.quantidade > 0
    GROUP BY vi.id_produto, v.data, p.mercadologico1
    ORDER BY v.data DESC, vi.id_produto
  `, [desde, config.lojaVrId]);
}

async function extrairPrecos() {
  log('Extraindo precos e margem teorica...');
  const particao = await detectarParticaoEstoque();
  if (!particao) {
    log('  AVISO: sem particao de estoque para cruzar custo');
    return [];
  }
  log(`  Usando particao: ${particao}`);
  return query(`
    SELECT
      p.id AS id_produto,
      p.descricaocompleta AS descricao,
      p.precovenda,
      p.mercadologico1,
      e.custocomimposto,
      e.customediocomimposto,
      e.custosemimposto,
      e.customediosemimposto,
      CASE WHEN COALESCE(p.precovenda, 0) > 0 AND COALESCE(e.custocomimposto, 0) > 0
        THEN ROUND(((p.precovenda - e.custocomimposto) / p.precovenda * 100)::numeric, 2)
        ELSE NULL
      END AS margem_teorica_pct,
      CASE WHEN COALESCE(p.precovenda, 0) > 0 AND COALESCE(e.custocomimposto, 0) > 0
        THEN ROUND((p.precovenda - e.custocomimposto)::numeric, 2)
        ELSE NULL
      END AS margem_teorica_rs,
      e.estoque,
      e.quantidadevendamedia AS venda_media_diaria,
      e.data AS data_estoque
    FROM public.produto p
    JOIN (
      SELECT DISTINCT ON (id_produto)
        id_produto, custocomimposto, customediocomimposto,
        custosemimposto, customediosemimposto,
        estoque, quantidadevendamedia, data
      FROM public.${particao}
      WHERE id_loja = $1
      ORDER BY id_produto, data DESC
    ) e ON e.id_produto = p.id
    WHERE COALESCE(p.precovenda, 0) > 0 OR COALESCE(e.estoque, 0) > 0
    ORDER BY p.id
  `, [config.lojaVrId]);
}

async function extrairEstoqueHistorico() {
  log('Extraindo historico de estoque...');
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

  if (existentes.length === 0) {
    const tabela = await query(`
      SELECT c.relname FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relname = 'estoque' AND c.relkind = 'r'
    `);
    if (tabela.length > 0) {
      log('  Usando tabela estoque unica...');
      const rows = await query(`
        SELECT id_loja, id_produto,
               MIN(data) AS data_inicio, MAX(data) AS data_fim,
               AVG(estoque) AS estoque_medio,
               MAX(estoque) AS estoque_max,
               MIN(estoque) AS estoque_min,
               AVG(quantidadevendamedia) AS venda_media,
               AVG(custocomimposto) AS custo_medio
        FROM public.estoque
        WHERE id_loja = $1
        GROUP BY id_loja, id_produto
        HAVING AVG(estoque) > 0 OR AVG(quantidadevendamedia) > 0
      `, [config.lojaVrId]);
      return rows;
    }
    return [];
  }

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
  return dados;
}

async function extrairCupomItens(filtros) {
  const desde = filtros?.cupom_itens_desde || filtros?.vendas_desde || new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  log(`Extraindo cupom_itens desde ${desde}...`);
  return query(`
    SELECT v.id AS id_venda, vi.id AS id_vendaitem,
           v.numerocupom,
           v.data AS datavenda,
           vi.id_produto, vi.quantidade,
           vi.precovenda, vi.valortotal AS valoritem
    FROM pdv.venda v
    JOIN pdv.vendaitem vi ON vi.id_venda = v.id
    WHERE v.data >= $1::date
      AND v.id_loja = $2
      AND vi.cancelado = false
    ORDER BY v.id
  `, [desde, config.lojaVrId]);
}

async function extrairPrecificados() {
  log('Extraindo precificados (snapshot diario precos/margens)...');
  return query(`
    SELECT pc.id_produto,
           pc.id_loja,
           CURRENT_DATE AS data,
           pc.precovenda,
           pc.custocomimposto,
           pc.customediocomimposto,
           pc.custosemimposto,
           pc.customediosemimposto,
           CASE WHEN pc.custocomimposto > 0 AND pc.precovenda > 0
                THEN ROUND(((pc.precovenda - pc.custocomimposto) / pc.precovenda * 100)::numeric, 2)
                ELSE 0 END AS margem_bruta_pct,
           CASE WHEN pc.custocomimposto > 0
                THEN ROUND((pc.precovenda - pc.custocomimposto)::numeric, 2)
                ELSE 0 END AS margem_bruta_rs,
           CASE WHEN pc.custosemimposto > 0 AND pc.precovenda > 0
                THEN ROUND(((pc.precovenda - pc.custosemimposto) / pc.precovenda * 100)::numeric, 2)
                ELSE 0 END AS margem_liquida_pct,
           CASE WHEN pc.custosemimposto > 0
                THEN ROUND((pc.precovenda - pc.custosemimposto)::numeric, 2)
                ELSE 0 END AS margem_liquida_rs,
           pc.estoque
    FROM public.produtocomplemento pc
    WHERE pc.id_loja = $1
      AND pc.precovenda > 0
      AND pc.custocomimposto > 0
  `, [config.lojaVrId]);
}

const EXTRATORES = {
  lojas: (f) => extrairLojas(),
  mercadologico: (f) => extrairMercadologico(),
  fornecedores: (f) => extrairFornecedores(),
  produtos: (f) => extrairProdutos(),
  estoque: (f) => extrairEstoque(),
  vendas: (f) => extrairVendas(f),
  ofertas: (f) => extrairOfertas(),
  compras: (f) => extrairCompras(f),
  vendas_promocao: (f) => extrairVendasPromocao(f),
  cupom_itens: (f) => extrairCupomItens(f),
  estoque_historico: (f) => extrairEstoqueHistorico(),
  margem: (f) => extrairMargem(f),
  precos: (f) => extrairPrecos(),
  precificados: (f) => extrairPrecificados(),
};

// ── MAIN ────────────────────────────────────────────────────────────

async function main() {
  log('======================================================');
  log('  VR Sync v3.0 - Pull-Push Centralizado');
  log('======================================================');
  log(`Banco: ${config.db.host}:${config.db.port}/${config.db.database}`);
  log(`Loja VR: ${config.lojaVrId}`);
  log(`Loja central: ${config.codigoLoja}`);
  log(`API: ${config.api.url}`);
  log('');

  await pool.query('SELECT 1');
  log('Conexao DB OK');
  log('');

  const serverConfig = await buscarConfig();
  const modulos = serverConfig.modulos || [];
  const filtros = serverConfig.filtros || {};

  log(`Servidor solicitou ${modulos.length} modulos: ${modulos.join(', ')}`);
  if (serverConfig.ultimoSync) {
    log(`Ultimo sync registrado: ${serverConfig.ultimoSync}`);
  }
  log('');

  const inicio = Date.now();
  const modulosData = {};
  const resumo = {};
  let houveErro = false;

  for (const modulo of modulos) {
    const extrator = EXTRATORES[modulo];
    if (!extrator) {
      log(`AVISO: modulo '${modulo}' solicitado pelo servidor nao tem extrator local`);
      resumo[modulo] = 'SEM EXTRATOR';
      continue;
    }

    try {
      const dados = await extrator(filtros);
      modulosData[modulo] = dados;
      resumo[modulo] = dados.length;
      salvarLocal(modulo, dados);
      log(`  ${modulo}: ${dados.length.toLocaleString('pt-BR')} registros`);
    } catch (err) {
      log(`ERRO em ${modulo}: ${err.message}`);
      resumo[modulo] = `ERRO: ${err.message}`;
      houveErro = true;
    }
    log('');
  }

  const modulosComDados = Object.fromEntries(
    Object.entries(modulosData).filter(([, v]) => v.length > 0)
  );

  if (Object.keys(modulosComDados).length > 0) {
    log('------------------------------------------------------');
    log('Enviando dados ao servidor central...');
    try {
      const resultado = await enviarEmLotes(modulosComDados);
      log(`Servidor recebeu ${resultado.totalRegistros?.toLocaleString('pt-BR') || '?'} registros`);
      if (resultado.erros) {
        for (const [mod, erro] of Object.entries(resultado.erros)) {
          log(`  ERRO no servidor em ${mod}: ${erro}`);
          resumo[mod] = `ERRO SERVIDOR: ${erro}`;
          houveErro = true;
        }
      }
    } catch (err) {
      log(`ERRO ao enviar dados: ${err.message}`);
      houveErro = true;
    }
  } else {
    log('Nenhum dado extraido para enviar.');
  }

  const duracao = ((Date.now() - inicio) / 1000).toFixed(1);
  log('');
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
