/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public class LoginDialog
extends JDialog {
    private JPasswordField campoSenha;
    private JLabel lblErro;
    private boolean autenticado = false;
    private final String senhaHash;
    private int tentativas = 0;
    private static final int MAX_TENTATIVAS = 5;

    public LoginDialog(String senhaHash) {
        super((Frame)null, "VR Sync - Login", true);
        this.senhaHash = senhaHash;
        this.inicializarUI();
    }

    private void inicializarUI() {
        this.setDefaultCloseOperation(2);
        this.setSize(380, 220);
        this.setLocationRelativeTo(null);
        this.setResizable(false);
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception exception) {
            // empty catch block
        }
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(20, 28, 20, 28));
        JLabel titulo = new JLabel("VR Sync 4.4", 0);
        titulo.setFont(titulo.getFont().deriveFont(1, 18.0f));
        titulo.setForeground(new Color(34, 139, 34));
        JPanel centro = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = 2;
        JLabel lblSenha = new JLabel("Senha:");
        lblSenha.setFont(lblSenha.getFont().deriveFont(13.0f));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        centro.add((Component)lblSenha, gbc);
        this.campoSenha = new JPasswordField(18);
        this.campoSenha.setFont(this.campoSenha.getFont().deriveFont(14.0f));
        this.campoSenha.addKeyListener(new KeyAdapter(){

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == 10) {
                    LoginDialog.this.validar();
                }
            }
        });
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        centro.add((Component)this.campoSenha, gbc);
        this.lblErro = new JLabel(" ");
        this.lblErro.setForeground(Color.RED);
        this.lblErro.setFont(this.lblErro.getFont().deriveFont(11.0f));
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        centro.add((Component)this.lblErro, gbc);
        JPanel rodape = new JPanel(new FlowLayout(1, 8, 0));
        JButton btnEntrar = new JButton("Entrar");
        btnEntrar.setPreferredSize(new Dimension(100, 32));
        btnEntrar.addActionListener(e -> this.validar());
        rodape.add(btnEntrar);
        root.add((Component)titulo, "North");
        root.add((Component)centro, "Center");
        root.add((Component)rodape, "South");
        this.setContentPane(root);
        this.getRootPane().setDefaultButton(btnEntrar);
    }

    private void validar() {
        ++this.tentativas;
        String digitada = new String(this.campoSenha.getPassword());
        if (digitada.isEmpty()) {
            this.lblErro.setText("Digite a senha.");
            return;
        }
        if (LoginDialog.hashSha256(digitada).equals(this.senhaHash)) {
            this.autenticado = true;
            this.dispose();
        } else {
            int restantes = 5 - this.tentativas;
            if (restantes <= 0) {
                this.lblErro.setText("Limite de tentativas excedido.");
                JOptionPane.showMessageDialog(this, "Limite de tentativas excedido.\nO aplicativo sera encerrado.", "Acesso Bloqueado", 0);
                System.exit(1);
            } else {
                this.lblErro.setText("Senha incorreta. " + restantes + " tentativa(s) restante(s).");
                this.campoSenha.setText("");
                this.campoSenha.requestFocus();
            }
        }
    }

    public boolean isAutenticado() {
        return this.autenticado;
    }

    public static String hashSha256(String texto) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (Exception e) {
            throw new RuntimeException("SHA-256 nao disponivel", e);
        }
    }

    public static String pedirNovaSenha(Component parent) {
        JPanel painel = new JPanel(new GridLayout(2, 2, 6, 8));
        JLabel lbl1 = new JLabel("Nova senha:");
        JPasswordField campo1 = new JPasswordField(16);
        JLabel lbl2 = new JLabel("Confirmar:");
        JPasswordField campo2 = new JPasswordField(16);
        painel.add(lbl1);
        painel.add(campo1);
        painel.add(lbl2);
        painel.add(campo2);
        int opt = JOptionPane.showConfirmDialog(parent, painel, "Definir Senha de Acesso", 2, -1);
        if (opt != 0) {
            return null;
        }
        String s1 = new String(campo1.getPassword());
        String s2 = new String(campo2.getPassword());
        if (s1.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "A senha nao pode ser vazia.", "Erro", 0);
            return null;
        }
        if (s1.length() < 4) {
            JOptionPane.showMessageDialog(parent, "A senha deve ter pelo menos 4 caracteres.", "Erro", 0);
            return null;
        }
        if (!s1.equals(s2)) {
            JOptionPane.showMessageDialog(parent, "As senhas nao conferem.", "Erro", 0);
            return null;
        }
        return LoginDialog.hashSha256(s1);
    }

    public static boolean pedirRemocaoSenha(Component parent, String senhaHashAtual) {
        JPasswordField campo = new JPasswordField(16);
        JPanel painel = new JPanel(new GridLayout(1, 2, 6, 8));
        painel.add(new JLabel("Senha atual:"));
        painel.add(campo);
        int opt = JOptionPane.showConfirmDialog(parent, painel, "Remover Senha de Acesso", 2, -1);
        if (opt != 0) {
            return false;
        }
        String digitada = new String(campo.getPassword());
        if (!LoginDialog.hashSha256(digitada).equals(senhaHashAtual)) {
            JOptionPane.showMessageDialog(parent, "Senha incorreta.", "Erro", 0);
            return false;
        }
        return true;
    }
}

