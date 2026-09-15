const { Pool } = require('pg');
const config = require('./config');

const pool = new Pool(config.db);

async function query(sql, params) {
  const res = await pool.query(sql, params);
  return res.rows;
}

async function main() {
  const args = process.argv.slice(2);
  const comando = args[0];

  if (!comando) {
    console.log(`
  VR Explorador - Consultas rapidas ao banco VR

  Uso: node explorar.js <comando> [opcoes]

  Comandos:
    lojas                  Lista todas as lojas
    tabelas [schema]       Lista tabelas com dados (filtro por schema)
    colunas <tabela>       Mostra colunas (ex: public.produto)
    amostra <tabela> [N]   Mostra N registros (padrao: 5)
    contar <tabela>        Conta registros
    sql "<query>"          Executa SELECT livre
    produtos [termo]       Busca produtos por descricao
    estoque <id_produto>   Estoque atual de um produto
    vendas <id_produto>    Vendas recentes (30 dias)
    `);
    process.exit(0);
  }

  try {
    switch (comando) {
      case 'lojas': {
        const rows = await query('SELECT id, descricao FROM public.loja ORDER BY id');
        console.table(rows);
        break;
      }

      case 'tabelas': {
        const schema = args[1] || null;
        const where = schema ? `AND schemaname = $1` : '';
        const params = schema ? [schema] : [];
        const rows = await query(`
          SELECT schemaname AS schema, relname AS tabela, n_live_tup AS registros
          FROM pg_stat_user_tables
          WHERE n_live_tup > 0 ${where}
          ORDER BY n_live_tup DESC LIMIT 50
        `, params);
        console.table(rows);
        break;
      }

      case 'colunas': {
        const tabela = args[1];
        if (!tabela) { console.error('Uso: node explorar.js colunas schema.tabela'); break; }
        const [schema, nome] = tabela.includes('.') ? tabela.split('.') : ['public', tabela];
        const rows = await query(`
          SELECT column_name, data_type, is_nullable
          FROM information_schema.columns
          WHERE table_schema = $1 AND table_name = $2
          ORDER BY ordinal_position
        `, [schema, nome]);
        console.table(rows);
        break;
      }

      case 'amostra': {
        const tabela = args[1];
        const limit = parseInt(args[2]) || 5;
        if (!tabela) { console.error('Uso: node explorar.js amostra schema.tabela [N]'); break; }
        const rows = await query(`SELECT * FROM ${tabela} LIMIT ${limit}`);
        console.table(rows);
        break;
      }

      case 'contar': {
        const tabela = args[1];
        if (!tabela) { console.error('Uso: node explorar.js contar schema.tabela'); break; }
        const rows = await query(`SELECT count(*)::int AS total FROM ${tabela}`);
        console.log(`${tabela}: ${rows[0].total.toLocaleString('pt-BR')} registros`);
        break;
      }

      case 'sql': {
        const sql = args.slice(1).join(' ');
        if (!sql.trim().toLowerCase().startsWith('select')) {
          console.error('Apenas SELECT permitido');
          break;
        }
        const rows = await query(sql);
        if (rows.length <= 50) console.table(rows);
        else { console.table(rows.slice(0, 50)); console.log(`... e mais ${rows.length - 50} registros`); }
        break;
      }

      case 'produtos': {
        const termo = args.slice(1).join(' ').toUpperCase();
        const rows = await query(`
          SELECT DISTINCT ON (ni.id_produto)
            ni.id_produto AS id, ni.descricaoxml AS descricao
          FROM public.notaentradaitem ni
          WHERE ni.descricaoxml ILIKE $1
          ORDER BY ni.id_produto, ni.id DESC
          LIMIT 30
        `, [`%${termo}%`]);
        console.table(rows);
        break;
      }

      case 'estoque': {
        const idProduto = parseInt(args[1]);
        if (!idProduto) { console.error('Uso: node explorar.js estoque <id_produto>'); break; }
        const particoes = await query(`
          SELECT relname FROM pg_stat_user_tables
          WHERE schemaname='public' AND relname ~ '^estoque\\d{6}$' AND n_live_tup > 0
          ORDER BY relname DESC LIMIT 1
        `);
        if (!particoes.length) { console.log('Nenhuma particao encontrada'); break; }
        const p = particoes[0].relname;
        const rows = await query(`
          SELECT id_loja, data, estoque, quantidadevendamedia, custocomimposto
          FROM public.${p}
          WHERE id_produto = $1
          ORDER BY id_loja, data DESC
        `, [idProduto]);
        console.log(`Estoque em ${p}:`);
        console.table(rows.slice(0, 20));
        break;
      }

      case 'vendas': {
        const idProduto = parseInt(args[1]);
        if (!idProduto) { console.error('Uso: node explorar.js vendas <id_produto>'); break; }
        const rows = await query(`
          SELECT v.id_loja, v.data, SUM(vi.quantidade) AS qtd,
                 SUM(vi.valortotal) AS valor, BOOL_OR(vi.oferta) AS oferta
          FROM pdv.venda v
          JOIN pdv.vendaitem vi ON vi.id_venda = v.id
          WHERE vi.id_produto = $1 AND v.data >= CURRENT_DATE - 30
            AND vi.cancelado = false
          GROUP BY v.id_loja, v.data
          ORDER BY v.data DESC, v.id_loja
          LIMIT 30
        `, [idProduto]);
        console.table(rows);
        break;
      }

      default:
        console.error(`Comando desconhecido: ${comando}`);
    }
  } catch (err) {
    console.error('Erro:', err.message);
  }

  await pool.end();
}

main();
