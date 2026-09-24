/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.ui;

import com.vrsync.config.AppConfig;
import com.vrsync.ui.LoginDialog;
import com.vrsync.ui.SyncListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public class SyncWindow
extends JFrame
implements SyncListener {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String[] MODULOS_ORDEM = new String[]{"lojas", "mercadologico", "fornecedores", "produtos", "vendas", "estoque", "ofertas", "compras", "vendas_promocao", "cupom_itens", "margem", "precos"};
    private final AppConfig config;
    private Runnable onSyncAgora;
    private Runnable onSair;
    private JLabel lblStatus;
    private JLabel lblUltimoSync;
    private JLabel lblProximoSync;
    private JLabel lblConexao;
    private JTextArea logArea;
    private DefaultTableModel tabelaModel;
    private JButton btnSync;
    private JProgressBar progressBar;
    private final Map<String, Integer> moduloRow = new LinkedHashMap<String, Integer>();
    private int modulosProcessados = 0;
    private int totalModulos = 0;

    public SyncWindow(AppConfig config) {
        this.config = config;
        this.inicializarUI();
    }

    public void setCallbacks(Runnable onSyncAgora, Runnable onSair) {
        this.onSyncAgora = onSyncAgora;
        this.onSair = onSair;
    }

    private void inicializarUI() {
        this.setTitle("VR Sync 4.4");
        this.setDefaultCloseOperation(1);
        this.setSize(720, 620);
        this.setLocationRelativeTo(null);
        this.setMinimumSize(new Dimension(600, 500));
        this.addWindowListener(new WindowAdapter(){

            @Override
            public void windowClosing(WindowEvent e) {
                SyncWindow.this.setVisible(false);
            }
        });
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception exception) {
            // empty catch block
        }
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBorder(new EmptyBorder(8, 12, 8, 12));
        root.add((Component)this.criarPainelTopo(), "North");
        root.add((Component)this.criarPainelCentral(), "Center");
        root.add((Component)this.criarPainelRodape(), "South");
        this.setContentPane(root);
        this.preencherModulos();
    }

    private JPanel criarPainelTopo() {
        JPanel topo = new JPanel(new BorderLayout(8, 4));
        topo.setBorder(new EmptyBorder(0, 0, 8, 0));
        JPanel info = new JPanel(new GridLayout(3, 1, 0, 2));
        this.lblStatus = new JLabel("\u25cf Aguardando");
        this.lblStatus.setFont(this.lblStatus.getFont().deriveFont(1, 14.0f));
        this.lblStatus.setForeground(new Color(34, 139, 34));
        this.lblConexao = new JLabel(String.format("Loja: %s  |  Banco: %s:%d/%s (%s)", this.config.getLoja(), this.config.getDbServer(), this.config.getDbPort(), this.config.getDbName(), this.config.getDbTipo()));
        this.lblConexao.setFont(this.lblConexao.getFont().deriveFont(11.0f));
        JPanel syncInfo = new JPanel(new FlowLayout(0, 0, 0));
        this.lblUltimoSync = new JLabel("Ultimo sync: --");
        this.lblUltimoSync.setFont(this.lblUltimoSync.getFont().deriveFont(11.0f));
        this.lblProximoSync = new JLabel("  |  Proximo: --");
        this.lblProximoSync.setFont(this.lblProximoSync.getFont().deriveFont(11.0f));
        syncInfo.add(this.lblUltimoSync);
        syncInfo.add(this.lblProximoSync);
        info.add(this.lblStatus);
        info.add(this.lblConexao);
        info.add(syncInfo);
        topo.add((Component)info, "Center");
        this.btnSync = new JButton("Sincronizar Agora");
        this.btnSync.setPreferredSize(new Dimension(150, 36));
        this.btnSync.addActionListener(e -> {
            if (this.onSyncAgora != null) {
                this.btnSync.setEnabled(false);
                new Thread(() -> this.onSyncAgora.run(), "sync-manual-ui").start();
            }
        });
        topo.add((Component)this.btnSync, "East");
        return topo;
    }

    private JPanel criarPainelCentral() {
        JPanel central = new JPanel(new BorderLayout(0, 6));
        Object[] colunas = new String[]{"Modulo", "Registros", "Status", "Tempo"};
        this.tabelaModel = new DefaultTableModel(colunas, 0){

            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        JTable tabela = new JTable(this.tabelaModel);
        tabela.setRowHeight(22);
        tabela.setShowGrid(true);
        tabela.setGridColor(new Color(220, 220, 220));
        tabela.getTableHeader().setReorderingAllowed(false);
        tabela.setSelectionMode(0);
        tabela.getColumnModel().getColumn(0).setPreferredWidth(140);
        tabela.getColumnModel().getColumn(1).setPreferredWidth(90);
        tabela.getColumnModel().getColumn(2).setPreferredWidth(100);
        tabela.getColumnModel().getColumn(3).setPreferredWidth(80);
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(4);
        tabela.getColumnModel().getColumn(1).setCellRenderer(rightRenderer);
        tabela.getColumnModel().getColumn(3).setCellRenderer(rightRenderer);
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(0);
        tabela.getColumnModel().getColumn(2).setCellRenderer(centerRenderer);
        JScrollPane tabelaScroll = new JScrollPane(tabela);
        tabelaScroll.setPreferredSize(new Dimension(0, 200));
        JPanel progressPanel = new JPanel(new BorderLayout(6, 0));
        progressPanel.setBorder(new EmptyBorder(2, 0, 2, 0));
        this.progressBar = new JProgressBar(0, 12);
        this.progressBar.setStringPainted(true);
        this.progressBar.setString("Aguardando...");
        progressPanel.add((Component)this.progressBar, "Center");
        this.logArea = new JTextArea();
        this.logArea.setEditable(false);
        this.logArea.setFont(new Font("Consolas", 0, 11));
        this.logArea.setLineWrap(true);
        this.logArea.setWrapStyleWord(true);
        this.logArea.setBackground(new Color(30, 30, 30));
        this.logArea.setForeground(new Color(200, 200, 200));
        this.logArea.setCaretColor(new Color(200, 200, 200));
        JScrollPane logScroll = new JScrollPane(this.logArea);
        logScroll.setPreferredSize(new Dimension(0, 160));
        JLabel logLabel = new JLabel("Log:");
        logLabel.setFont(logLabel.getFont().deriveFont(1, 11.0f));
        JPanel logPanel = new JPanel(new BorderLayout(0, 2));
        logPanel.add((Component)logLabel, "North");
        logPanel.add((Component)logScroll, "Center");
        JSplitPane split = new JSplitPane(0, tabelaScroll, logPanel);
        split.setResizeWeight(0.5);
        split.setDividerSize(5);
        central.add((Component)progressPanel, "North");
        central.add((Component)split, "Center");
        return central;
    }

    private JPanel criarPainelRodape() {
        JPanel rodape = new JPanel(new BorderLayout());
        rodape.setBorder(new EmptyBorder(4, 0, 0, 0));
        JPanel botoes = new JPanel(new FlowLayout(0, 8, 0));
        JButton btnLogs = new JButton("Abrir Logs");
        btnLogs.addActionListener(e -> {
            try {
                String appdata = System.getenv("APPDATA");
                File logsDir = new File(appdata, "VR-Sync/logs");
                if (logsDir.exists()) {
                    Desktop.getDesktop().open(logsDir);
                } else {
                    this.adicionarLog("Pasta de logs nao encontrada: " + logsDir.getPath());
                }
            }
            catch (Exception ex) {
                this.adicionarLog("Erro ao abrir pasta: " + ex.getMessage());
            }
        });
        JButton btnConfig = new JButton("Config");
        btnConfig.addActionListener(e -> {
            try {
                if (this.config.getConfigPath() != null) {
                    Desktop.getDesktop().open(this.config.getConfigPath().toFile());
                }
            }
            catch (Exception ex) {
                this.adicionarLog("Erro ao abrir config: " + ex.getMessage());
            }
        });
        JButton btnSenha = new JButton(this.config.temSenha() ? "Alterar Senha" : "Definir Senha");
        btnSenha.addActionListener(e -> {
            if (this.config.temSenha()) {
                Object[] opcoes = new String[]{"Alterar", "Remover", "Cancelar"};
                int escolha = JOptionPane.showOptionDialog(this, "A senha de acesso esta ativa.\nO que deseja fazer?", "Senha de Acesso", -1, 3, null, opcoes, opcoes[0]);
                if (escolha == 0) {
                    String novoHash = LoginDialog.pedirNovaSenha(this);
                    if (novoHash != null) {
                        this.config.salvarSenha(novoHash);
                        btnSenha.setText("Alterar Senha");
                        this.adicionarLog("Senha de acesso alterada");
                        JOptionPane.showMessageDialog(this, "Senha alterada com sucesso!", "Senha", 1);
                    }
                } else if (escolha == 1 && LoginDialog.pedirRemocaoSenha(this, this.config.getSenha())) {
                    this.config.salvarSenha(null);
                    btnSenha.setText("Definir Senha");
                    this.adicionarLog("Senha de acesso removida");
                    JOptionPane.showMessageDialog(this, "Senha removida com sucesso!", "Senha", 1);
                }
            } else {
                String novoHash = LoginDialog.pedirNovaSenha(this);
                if (novoHash != null) {
                    this.config.salvarSenha(novoHash);
                    btnSenha.setText("Alterar Senha");
                    this.adicionarLog("Senha de acesso definida");
                    JOptionPane.showMessageDialog(this, "Senha definida com sucesso!\nSera exigida no proximo inicio.", "Senha", 1);
                }
            }
        });
        botoes.add(btnLogs);
        botoes.add(btnConfig);
        botoes.add(btnSenha);
        JButton btnSair = new JButton("Encerrar");
        btnSair.addActionListener(e -> {
            int resp = JOptionPane.showConfirmDialog(this, "Encerrar VR Sync?\nA sincronizacao automatica sera interrompida.", "Confirmar", 0);
            if (resp == 0 && this.onSair != null) {
                this.onSair.run();
            }
        });
        JPanel direitaPanel = new JPanel(new FlowLayout(2, 0, 0));
        direitaPanel.add(btnSair);
        rodape.add((Component)botoes, "West");
        rodape.add((Component)direitaPanel, "East");
        return rodape;
    }

    private void preencherModulos() {
        this.moduloRow.clear();
        this.tabelaModel.setRowCount(0);
        for (int i = 0; i < MODULOS_ORDEM.length; ++i) {
            this.tabelaModel.addRow(new Object[]{MODULOS_ORDEM[i], "--", "\u25cb Pendente", "--"});
            this.moduloRow.put(MODULOS_ORDEM[i], i);
        }
    }

    public void mostrar() {
        SwingUtilities.invokeLater(() -> {
            this.setVisible(true);
            this.toFront();
            this.requestFocus();
        });
    }

    public void adicionarLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            this.logArea.append(ts + "  " + msg + "\n");
            this.logArea.setCaretPosition(this.logArea.getDocument().getLength());
        });
    }

    public void atualizarUltimoSync(Instant quando) {
        SwingUtilities.invokeLater(() -> {
            if (quando != null) {
                String fmt = LocalDateTime.ofInstant(quando, ZoneId.systemDefault()).format(FMT);
                this.lblUltimoSync.setText("Ultimo sync: " + fmt);
            }
        });
    }

    public void atualizarProximoSync(Instant quando) {
        SwingUtilities.invokeLater(() -> {
            if (quando != null) {
                String fmt = LocalDateTime.ofInstant(quando, ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"));
                this.lblProximoSync.setText("  |  Proximo: " + fmt);
            } else {
                this.lblProximoSync.setText("");
            }
        });
    }

    private void setStatusLabel(String texto, Color cor) {
        SwingUtilities.invokeLater(() -> {
            this.lblStatus.setText(texto);
            this.lblStatus.setForeground(cor);
        });
    }

    private String formatarTempo(long ms) {
        if (ms < 1000L) {
            return ms + "ms";
        }
        return String.format("%.1fs", (double)ms / 1000.0);
    }

    private String formatarNumero(int n) {
        if (n < 1000) {
            return String.valueOf(n);
        }
        return String.format("%,d", n);
    }

    @Override
    public void onSyncInicio(int totalModulos) {
        this.totalModulos = totalModulos;
        this.modulosProcessados = 0;
        SwingUtilities.invokeLater(() -> {
            this.preencherModulos();
            this.progressBar.setMaximum(totalModulos * 2);
            this.progressBar.setValue(0);
            this.progressBar.setString("Iniciando...");
            this.btnSync.setEnabled(false);
        });
        this.setStatusLabel("\u25cf Sincronizando...", new Color(0, 100, 200));
        this.adicionarLog("Sincronizacao iniciada \u2014 " + totalModulos + " modulos");
    }

    @Override
    public void onSyncFim(int totalRegistros, long duracaoMs, boolean sucesso, String erro) {
        SwingUtilities.invokeLater(() -> {
            this.btnSync.setEnabled(true);
            if (sucesso) {
                this.progressBar.setValue(this.progressBar.getMaximum());
                this.progressBar.setString("Concluido: " + this.formatarNumero(totalRegistros) + " registros em " + this.formatarTempo(duracaoMs));
                this.setStatusLabel("\u25cf Concluido", new Color(34, 139, 34));
                this.adicionarLog("Sync concluida: " + this.formatarNumero(totalRegistros) + " registros em " + this.formatarTempo(duracaoMs));
            } else {
                this.progressBar.setString("Erro: " + (erro != null ? erro : "desconhecido"));
                this.setStatusLabel("\u25cf Erro", Color.RED);
                this.adicionarLog("ERRO: " + erro);
            }
            this.atualizarUltimoSync(Instant.now());
        });
    }

    @Override
    public void onModuloExtraindo(String modulo) {
        SwingUtilities.invokeLater(() -> {
            Integer row = this.moduloRow.get(modulo);
            if (row != null) {
                this.tabelaModel.setValueAt("\u23f3 Extraindo...", row, 2);
            }
            this.progressBar.setString("Extraindo: " + modulo);
        });
        this.adicionarLog("Extraindo " + modulo + "...");
    }

    @Override
    public void onModuloExtraido(String modulo, int registros, long tempoMs) {
        ++this.modulosProcessados;
        SwingUtilities.invokeLater(() -> {
            Integer row = this.moduloRow.get(modulo);
            if (row != null) {
                this.tabelaModel.setValueAt(this.formatarNumero(registros), row, 1);
                this.tabelaModel.setValueAt("\u2705 OK", row, 2);
                this.tabelaModel.setValueAt(this.formatarTempo(tempoMs), row, 3);
            }
            this.progressBar.setValue(this.modulosProcessados);
        });
        this.adicionarLog("  " + modulo + ": " + this.formatarNumero(registros) + " registros (" + this.formatarTempo(tempoMs) + ")");
    }

    @Override
    public void onModuloErro(String modulo, String erro) {
        ++this.modulosProcessados;
        SwingUtilities.invokeLater(() -> {
            Integer row = this.moduloRow.get(modulo);
            if (row != null) {
                this.tabelaModel.setValueAt("0", row, 1);
                this.tabelaModel.setValueAt("\u274c Erro", row, 2);
                this.tabelaModel.setValueAt("--", row, 3);
            }
            this.progressBar.setValue(this.modulosProcessados);
        });
        this.adicionarLog("  ERRO " + modulo + ": " + erro);
    }

    @Override
    public void onEnviando(String modulo, int registros, int loteAtual, int totalLotes) {
        SwingUtilities.invokeLater(() -> {
            Integer row = this.moduloRow.get(modulo);
            if (row != null) {
                this.tabelaModel.setValueAt(String.format("\u2b06 Enviando %d/%d", loteAtual, totalLotes), row, 2);
            }
            this.progressBar.setString("Enviando: " + modulo + " (lote " + loteAtual + "/" + totalLotes + ")");
        });
    }

    @Override
    public void onEnviado(String modulo, boolean sucesso) {
        SwingUtilities.invokeLater(() -> {
            Integer row = this.moduloRow.get(modulo);
            if (row != null) {
                this.tabelaModel.setValueAt(sucesso ? "\u2705 Enviado" : "\u26a0 Parcial", row, 2);
            }
        });
        this.adicionarLog("  " + modulo + ": " + (sucesso ? "enviado" : "envio parcial"));
    }

    @Override
    public void onLog(String mensagem) {
        this.adicionarLog(mensagem);
    }
}

