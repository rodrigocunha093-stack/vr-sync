package com.vrsync.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.*;
import java.util.List;

public class ModuloSelector {

    private static final String[][] GRUPOS = {
        {"Precos", "precos", "precificados", "precificados_log"},
        {"Vendas", "vendas", "cupom_itens", "margem", "vendas_promocao"},
        {"Cadastro", "produtos", "mercadologico", "fornecedores", "lojas"},
        {"Outros", "estoque", "ofertas", "compras"},
    };

    private static final Set<String> PRE_SELECIONADOS = Set.of(
        "precos", "precificados", "precificados_log"
    );

    public static List<String> mostrar(Component parent) {
        Map<String, JCheckBox> checkboxes = new LinkedHashMap<>();

        JPanel painelPrincipal = new JPanel();
        painelPrincipal.setLayout(new BoxLayout(painelPrincipal, BoxLayout.Y_AXIS));
        painelPrincipal.setBorder(new EmptyBorder(4, 4, 4, 4));

        for (String[] grupo : GRUPOS) {
            String nomeGrupo = grupo[0];
            JPanel painelGrupo = new JPanel(new GridLayout(0, 2, 8, 2));
            painelGrupo.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), nomeGrupo,
                TitledBorder.LEFT, TitledBorder.TOP));

            for (int i = 1; i < grupo.length; i++) {
                String modulo = grupo[i];
                JCheckBox cb = new JCheckBox(modulo, PRE_SELECIONADOS.contains(modulo));
                cb.setFont(cb.getFont().deriveFont(12.0f));
                checkboxes.put(modulo, cb);
                painelGrupo.add(cb);
            }

            painelPrincipal.add(painelGrupo);
            painelPrincipal.add(Box.createVerticalStrut(4));
        }

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton btnTodos = new JButton("Todos");
        btnTodos.setMargin(new Insets(2, 8, 2, 8));
        btnTodos.addActionListener(e -> checkboxes.values().forEach(cb -> cb.setSelected(true)));
        JButton btnNenhum = new JButton("Nenhum");
        btnNenhum.setMargin(new Insets(2, 8, 2, 8));
        btnNenhum.addActionListener(e -> checkboxes.values().forEach(cb -> cb.setSelected(false)));
        JButton btnPrecos = new JButton("So Precos");
        btnPrecos.setMargin(new Insets(2, 8, 2, 8));
        btnPrecos.addActionListener(e -> {
            checkboxes.values().forEach(cb -> cb.setSelected(false));
            PRE_SELECIONADOS.forEach(m -> {
                JCheckBox cb = checkboxes.get(m);
                if (cb != null) cb.setSelected(true);
            });
        });
        btnPanel.add(btnTodos);
        btnPanel.add(btnNenhum);
        btnPanel.add(btnPrecos);
        painelPrincipal.add(btnPanel);

        int result = JOptionPane.showConfirmDialog(
            parent, painelPrincipal,
            "Selecionar Modulos para Sincronizar",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );

        if (result != JOptionPane.OK_OPTION) return null;

        List<String> selecionados = new ArrayList<>();
        for (Map.Entry<String, JCheckBox> entry : checkboxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                selecionados.add(entry.getKey());
            }
        }

        return selecionados.isEmpty() ? null : selecionados;
    }
}
