/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.extractor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class SqlQueries {
    private static final Map<String, Function<String, String>> MSSQL = new HashMap<String, Function<String, String>>();
    private static final Map<String, Function<String, String>> POSTGRES = new HashMap<String, Function<String, String>>();

    private SqlQueries() {
    }

    public static String getQuery(String modulo, String desde, String dbTipo, int lojaVrId) {
        Map<String, Function<String, String>> queries = "postgres".equalsIgnoreCase(dbTipo) ? POSTGRES : MSSQL;
        Function<String, String> fn = queries.get(modulo.toLowerCase());
        if (fn == null) {
            throw new IllegalArgumentException("Modulo desconhecido: " + modulo);
        }
        String sql = fn.apply(desde != null ? desde : "2000-01-01");
        if ("postgres".equalsIgnoreCase(dbTipo)) {
            sql = sql.replace("{LOJA_ID}", String.valueOf(lojaVrId));
        }
        return sql;
    }

    public static String getPartitionDetectSql() {
        return "SELECT c.relname\nFROM pg_class c\nJOIN pg_namespace n ON n.oid = c.relnamespace\nWHERE n.nspname = 'public'\n  AND c.relname ~ '^estoque[0-9]{6,}$'\n  AND c.relkind = 'r'\n  AND c.reltuples > 0\nORDER BY c.relname DESC\nLIMIT 1\n";
    }

    public static String getEstoquePgSql(String particao, int lojaVrId) {
        return "SELECT id_produto, id_loja, data, estoque,\n       quantidadevendamedia,\n       custocomimposto, customediocomimposto,\n       custosemimposto, customediosemimposto\nFROM public.%s\nWHERE id_loja = %d\n  AND (estoque > 0 OR quantidadevendamedia > 0)\nLIMIT 200000\n".formatted(particao, lojaVrId);
    }

    public static boolean requerDesde(String modulo) {
        return switch (modulo.toLowerCase()) {
            case "vendas", "compras", "vendas_promocao", "cupom_itens", "margem" -> true;
            default -> false;
        };
    }

    public static Set<String> getModulos() {
        return MSSQL.keySet();
    }

    static {
        MSSQL.put("lojas", desde -> "SELECT TOP 1\n    id, descricao, cnpj, razaosocial, nomefantasia,\n    CASE WHEN tipo = 'A' THEN 1 ELSE 0 END AS atacado\nFROM loja\nWHERE ativo = 1\n");
        MSSQL.put("mercadologico", desde -> "SELECT id, mercadologico1, mercadologico2, mercadologico3,\n       nivel, descricao\nFROM mercadologico\nWHERE descricao IS NOT NULL\n");
        MSSQL.put("fornecedores", desde -> "SELECT id, razaosocial, nomefantasia, cnpj, id_estado\nFROM fornecedor\nWHERE ativo = 1\n");
        MSSQL.put("produtos", desde -> "SELECT p.id, p.descricao, p.descricao AS descricao_cadastro,\n       p.id AS codigo_interno,\n       CAST(p.codigobarras AS BIGINT) AS ean,\n       p.id_fornecedor, p.mercadologico1,\n       p.qtdembalagem, p.ativo\nFROM produto p\nWHERE p.ativo = 1\n");
        MSSQL.put("vendas", desde -> "SELECT vi.id_produto,\n       CAST(v.datavenda AS DATE) AS data,\n       SUM(vi.quantidade) AS quantidade,\n       SUM(vi.valoritem) AS valortotal,\n       SUM(ISNULL(vi.desconto, 0)) AS desconto,\n       0 AS desconto_promo,\n       MAX(vi.custocomimposto) AS custocomimposto,\n       MAX(vi.custosemimposto) AS custosemimposto,\n       COUNT(DISTINCT v.id_venda) AS num_cupons,\n       0 AS teve_oferta\nFROM venda v\nINNER JOIN vendaitem vi ON vi.id_venda = v.id_venda\nWHERE CAST(v.datavenda AS DATE) >= '%s'\n  AND v.cancelada = 0\nGROUP BY vi.id_produto, CAST(v.datavenda AS DATE)\n".formatted(desde));
        MSSQL.put("estoque", desde -> "SELECT e.id_produto,\n       CAST(GETDATE() AS DATE) AS data,\n       e.estoque,\n       e.quantidadevendamedia,\n       e.custocomimposto,\n       e.customediocomimposto,\n       e.custosemimposto,\n       e.customediosemimposto\nFROM estoque e\nWHERE e.estoque <> 0 OR e.quantidadevendamedia > 0\n");
        MSSQL.put("ofertas", desde -> "SELECT id, id_oferta, id_produto, descricao,\n       precoconnect, prioridade, id_campanha\nFROM oferta\nWHERE ativo = 1\n");
        MSSQL.put("compras", desde -> "SELECT ni.id_nota, n.id_loja, ni.id_fornecedor,\n       n.dataemissao, n.dataentrada,\n       n.valortotal AS valor_nota,\n       ni.id_produto, p.descricao,\n       ni.quantidade, ni.qtdembalagem,\n       ni.custocomimposto, ni.valoritem AS valor_item,\n       ISNULL(ni.valorbonificacao, 0) AS valorbonificacao,\n       ISNULL(ni.valorverba, 0) AS valorverba\nFROM notaentradaitem ni\nINNER JOIN notaentrada n ON n.id = ni.id_nota\nINNER JOIN produto p ON p.id = ni.id_produto\nWHERE n.dataentrada >= '%s'\n".formatted(desde));
        MSSQL.put("vendas_promocao", desde -> "SELECT vi.id AS id, v.id_venda AS id_venda,\n       vi.id_produto,\n       vi.preconormal,\n       vi.precovenda AS precopromocao,\n       vi.quantidade,\n       (vi.preconormal - vi.precovenda) * vi.quantidade AS valordesconto\nFROM venda v\nINNER JOIN vendaitem vi ON vi.id_venda = v.id_venda\nWHERE CAST(v.datavenda AS DATE) >= '%s'\n  AND v.cancelada = 0\n  AND vi.preconormal > vi.precovenda\n".formatted(desde));
        MSSQL.put("cupom_itens", desde -> "SELECT v.id_venda, vi.id_vendaitem, v.numerocupom,\n       CAST(v.datavenda AS DATE) AS datavenda,\n       vi.id_produto, vi.quantidade,\n       vi.precovenda, vi.valoritem\nFROM venda v\nINNER JOIN vendaitem vi ON vi.id_venda = v.id_venda\nWHERE CAST(v.datavenda AS DATE) >= '%s'\n  AND v.cancelada = 0\nORDER BY v.id_venda\n".formatted(desde));
        MSSQL.put("margem", desde -> "SELECT vi.id_produto,\n       CAST(v.datavenda AS DATE) AS data,\n       p.mercadologico1,\n       SUM(vi.quantidade) AS quantidade,\n       SUM(vi.valoritem) AS receita,\n       SUM(ISNULL(vi.desconto, 0)) AS desconto,\n       SUM(vi.quantidade * vi.custocomimposto) AS custo_total,\n       SUM(vi.valoritem) - SUM(vi.quantidade * vi.custocomimposto) AS margem_bruta,\n       CASE WHEN SUM(vi.valoritem) > 0\n            THEN ((SUM(vi.valoritem) - SUM(vi.quantidade * vi.custocomimposto)) / SUM(vi.valoritem)) * 100\n            ELSE 0 END AS margem_bruta_pct,\n       SUM(vi.quantidade * vi.custosemimposto) AS custo_liquido,\n       SUM(vi.valoritem) - SUM(vi.quantidade * vi.custosemimposto) AS margem_liquida,\n       CASE WHEN SUM(vi.valoritem) > 0\n            THEN ((SUM(vi.valoritem) - SUM(vi.quantidade * vi.custosemimposto)) / SUM(vi.valoritem)) * 100\n            ELSE 0 END AS margem_liquida_pct,\n       0 AS em_oferta,\n       COUNT(DISTINCT v.id_venda) AS num_cupons\nFROM venda v\nINNER JOIN vendaitem vi ON vi.id_venda = v.id_venda\nINNER JOIN produto p ON p.id = vi.id_produto\nWHERE CAST(v.datavenda AS DATE) >= '%s'\n  AND v.cancelada = 0\nGROUP BY vi.id_produto, CAST(v.datavenda AS DATE), p.mercadologico1\n".formatted(desde));
        MSSQL.put("precos", desde -> "SELECT p.id AS id_produto, p.descricao,\n       p.precovenda, p.mercadologico1,\n       e.custocomimposto, e.customediocomimposto,\n       e.custosemimposto, e.customediosemimposto,\n       CASE WHEN e.custocomimposto > 0 AND p.precovenda > 0\n            THEN ((p.precovenda - e.custocomimposto) / p.precovenda) * 100\n            ELSE 0 END AS margem_teorica_pct,\n       CASE WHEN e.custocomimposto > 0\n            THEN p.precovenda - e.custocomimposto\n            ELSE 0 END AS margem_teorica_rs,\n       e.estoque,\n       e.quantidadevendamedia AS venda_media_diaria,\n       CAST(GETDATE() AS DATE) AS data_estoque\nFROM produto p\nLEFT JOIN estoque e ON e.id_produto = p.id\nWHERE p.ativo = 1 AND p.precovenda > 0\n");
        POSTGRES.put("lojas", desde -> "SELECT l.id, l.descricao, l.id_fornecedor, l.id_regiao,\n       l.atacado, f.cnpj, f.razaosocial, f.nomefantasia\nFROM public.loja l\nLEFT JOIN public.fornecedor f ON f.id = l.id_fornecedor\nWHERE l.id = {LOJA_ID}\n");
        POSTGRES.put("mercadologico", desde -> "SELECT id, mercadologico1, mercadologico2, mercadologico3,\n       nivel, descricao\nFROM public.mercadologico\nWHERE descricao IS NOT NULL\n");
        POSTGRES.put("fornecedores", desde -> "SELECT id, razaosocial, nomefantasia, cnpj, id_estado,\n       id_situacaocadastro, id_tipopagamento\nFROM public.fornecedor\nWHERE id_situacaocadastro = 1\n");
        POSTGRES.put("produtos", desde -> "SELECT DISTINCT ON (ni.id_produto)\n    ni.id_produto AS id,\n    ni.descricaoxml AS descricao,\n    ni.descricaoxml AS descricao_cadastro,\n    ne.id_fornecedor,\n    0 AS mercadologico1,\n    ni.qtdembalagem,\n    ni.cfop,\n    pa.codigobarras::BIGINT AS ean\nFROM public.notaentradaitem ni\nJOIN public.notaentrada ne ON ne.id = ni.id_notaentrada\nLEFT JOIN public.produtoautomacao pa ON pa.id_produto = ni.id_produto\nWHERE ne.id_loja = {LOJA_ID}\nORDER BY ni.id_produto, ne.dataentrada DESC\n");
        POSTGRES.put("vendas", desde -> "SELECT v.id_loja, vi.id_produto, v.data,\n       SUM(vi.quantidade) AS quantidade,\n       SUM(vi.valortotal) AS valortotal,\n       SUM(vi.valordesconto) AS desconto,\n       SUM(vi.valordescontopromocao) AS desconto_promo,\n       AVG(vi.custocomimposto) AS custocomimposto,\n       AVG(vi.custosemimposto) AS custosemimposto,\n       COUNT(*) AS num_cupons,\n       BOOL_OR(vi.oferta)::int AS teve_oferta\nFROM pdv.venda v\nJOIN pdv.vendaitem vi ON vi.id_venda = v.id\nWHERE v.data >= '%s'::date\n  AND v.id_loja = {LOJA_ID}\n  AND vi.cancelado = false\nGROUP BY v.id_loja, vi.id_produto, v.data\n".formatted(desde));
        POSTGRES.put("estoque", desde -> "SELECT id_produto, id_loja, data, estoque,\n       quantidadevendamedia,\n       custocomimposto, customediocomimposto,\n       custosemimposto, customediosemimposto\nFROM public.estoque\nWHERE id_loja = {LOJA_ID}\n  AND (estoque > 0 OR quantidadevendamedia > 0)\nLIMIT 200000\n");
        POSTGRES.put("ofertas", desde -> "SELECT pc.id_produto AS id, pc.id_produto, pc.id_loja,\n       '' AS descricao,\n       pc.precovenda AS precoconnect,\n       0 AS prioridade, 0 AS id_campanha\nFROM public.produtocomplemento pc\nWHERE pc.id_loja = {LOJA_ID}\n  AND pc.precovenda > 0\n  AND pc.estoque > 0\n");
        POSTGRES.put("compras", desde -> "SELECT ne.id AS id_nota, ne.id_loja, ne.id_fornecedor,\n       ne.dataemissao, ne.dataentrada,\n       ne.valortotal AS valor_nota,\n       ni.id_produto, ni.descricaoxml AS descricao,\n       ni.quantidade, ni.qtdembalagem,\n       ni.custocomimposto, ni.valortotal AS valor_item,\n       COALESCE(ni.valorbonificacao, 0) AS valorbonificacao,\n       COALESCE(ni.valorverba, 0) AS valorverba\nFROM public.notaentrada ne\nJOIN public.notaentradaitem ni ON ni.id_notaentrada = ne.id\nWHERE ne.dataentrada >= '%s'::date\n  AND ne.id_loja = {LOJA_ID}\n".formatted(desde));
        POSTGRES.put("vendas_promocao", desde -> "SELECT vpp.id, vpp.id_venda, vpp.id_produto,\n       vpp.id_promocao, vpp.quantidade,\n       vpp.valortotal, vpp.valordesconto,\n       vpp.id_vrpromocao\nFROM pdv.vendapromocaoproduto vpp\nJOIN pdv.venda v ON v.id = vpp.id_venda\nWHERE v.id_loja = {LOJA_ID}\n  AND v.data >= '%s'::date\n".formatted(desde));
        POSTGRES.put("cupom_itens", desde -> "SELECT v.id AS id_venda, vi.id AS id_vendaitem,\n       v.numerocupom,\n       v.data AS datavenda,\n       vi.id_produto, vi.quantidade,\n       vi.precovenda, vi.valortotal AS valoritem\nFROM pdv.venda v\nJOIN pdv.vendaitem vi ON vi.id_venda = v.id\nWHERE v.data >= '%s'::date\n  AND v.id_loja = {LOJA_ID}\n  AND vi.cancelado = false\nORDER BY v.id\n".formatted(desde));
        POSTGRES.put("margem", desde -> "SELECT vi.id_produto,\n       v.data,\n       0 AS mercadologico1,\n       SUM(vi.quantidade) AS quantidade,\n       SUM(vi.valortotal) AS receita,\n       SUM(vi.valordesconto) AS desconto,\n       SUM(vi.quantidade * vi.custocomimposto) AS custo_total,\n       SUM(vi.valortotal) - SUM(vi.quantidade * vi.custocomimposto) AS margem_bruta,\n       CASE WHEN SUM(vi.valortotal) > 0\n            THEN ((SUM(vi.valortotal) - SUM(vi.quantidade * vi.custocomimposto)) / SUM(vi.valortotal)) * 100\n            ELSE 0 END AS margem_bruta_pct,\n       SUM(vi.quantidade * vi.custosemimposto) AS custo_liquido,\n       SUM(vi.valortotal) - SUM(vi.quantidade * vi.custosemimposto) AS margem_liquida,\n       CASE WHEN SUM(vi.valortotal) > 0\n            THEN ((SUM(vi.valortotal) - SUM(vi.quantidade * vi.custosemimposto)) / SUM(vi.valortotal)) * 100\n            ELSE 0 END AS margem_liquida_pct,\n       BOOL_OR(vi.oferta)::int AS em_oferta,\n       COUNT(DISTINCT v.id) AS num_cupons\nFROM pdv.venda v\nJOIN pdv.vendaitem vi ON vi.id_venda = v.id\nWHERE v.data >= '%s'::date\n  AND v.id_loja = {LOJA_ID}\n  AND vi.cancelado = false\nGROUP BY vi.id_produto, v.data\n".formatted(desde));
        POSTGRES.put("precos", desde -> "SELECT pc.id_produto, '' AS descricao,\n       pc.precovenda, 0 AS mercadologico1,\n       pc.custocomimposto, pc.customediocomimposto,\n       pc.custosemimposto, pc.customediosemimposto,\n       CASE WHEN pc.custocomimposto > 0 AND pc.precovenda > 0\n            THEN ((pc.precovenda - pc.custocomimposto) / pc.precovenda) * 100\n            ELSE 0 END AS margem_teorica_pct,\n       CASE WHEN pc.custocomimposto > 0\n            THEN pc.precovenda - pc.custocomimposto\n            ELSE 0 END AS margem_teorica_rs,\n       pc.estoque,\n       0 AS venda_media_diaria,\n       CURRENT_DATE AS data_estoque\nFROM public.produtocomplemento pc\nWHERE pc.id_loja = {LOJA_ID}\n  AND pc.precovenda > 0\n");
    }
}

