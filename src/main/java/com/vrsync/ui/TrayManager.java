/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.ui;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrayManager {
    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);
    private TrayIcon trayIcon;
    private Runnable onSyncAgora;
    private Runnable onSair;
    private Runnable onAbrir;

    public void setSyncWindow(Runnable onAbrir) {
        this.onAbrir = onAbrir;
    }

    public void inicializar(Runnable onSyncAgora, Runnable onSair) {
        this.onSyncAgora = onSyncAgora;
        this.onSair = onSair;
        if (!SystemTray.isSupported()) {
            log.warn("System tray nao suportado neste sistema");
            return;
        }
        try {
            Image icon = this.criarIcone();
            PopupMenu menu = this.criarMenu();
            this.trayIcon = new TrayIcon(icon, "VR Sync 4.4 - Aguardando", menu);
            this.trayIcon.setImageAutoSize(true);
            this.trayIcon.addActionListener(e -> {
                if (this.onAbrir != null) {
                    this.onAbrir.run();
                }
            });
            SystemTray.getSystemTray().add(this.trayIcon);
            log.info("Icone da bandeja inicializado");
        }
        catch (AWTException e2) {
            log.error("Erro ao adicionar icone na bandeja: {}", (Object)e2.getMessage());
        }
    }

    public void atualizarStatus(String msg) {
        if (this.trayIcon != null) {
            this.trayIcon.setToolTip("VR Sync - " + msg);
        }
    }

    public void notificar(String titulo, String mensagem, TrayIcon.MessageType tipo) {
        if (this.trayIcon != null) {
            this.trayIcon.displayMessage(titulo, mensagem, tipo);
        }
    }

    public void notificarSucesso(String mensagem) {
        this.notificar("VR Sync", mensagem, TrayIcon.MessageType.INFO);
    }

    public void notificarErro(String mensagem) {
        this.notificar("VR Sync - Erro", mensagem, TrayIcon.MessageType.ERROR);
    }

    public void remover() {
        if (this.trayIcon != null) {
            SystemTray.getSystemTray().remove(this.trayIcon);
            this.trayIcon = null;
            log.info("Icone da bandeja removido");
        }
    }

    private PopupMenu criarMenu() {
        PopupMenu menu = new PopupMenu();
        MenuItem syncItem = new MenuItem("Sincronizar Agora");
        syncItem.addActionListener(e -> {
            if (this.onSyncAgora != null) {
                new Thread(() -> this.onSyncAgora.run(), "sync-manual").start();
            }
        });
        MenuItem statusItem = new MenuItem("Status");
        statusItem.addActionListener(e -> {
            if (this.trayIcon != null) {
                this.trayIcon.displayMessage("VR Sync - Status", this.trayIcon.getToolTip(), TrayIcon.MessageType.INFO);
            }
        });
        MenuItem sobreItem = new MenuItem("Sobre");
        sobreItem.addActionListener(e -> {
            if (this.trayIcon != null) {
                this.trayIcon.displayMessage("VR Sync", "VR Sync v4.4.0\nCliente de sincronizacao\nEncarte Inteligente", TrayIcon.MessageType.INFO);
            }
        });
        MenuItem sairItem = new MenuItem("Sair");
        sairItem.addActionListener(e -> {
            if (this.onSair != null) {
                this.onSair.run();
            }
        });
        MenuItem abrirItem = new MenuItem("Abrir Painel");
        abrirItem.addActionListener(e -> {
            if (this.onAbrir != null) {
                this.onAbrir.run();
            }
        });
        menu.add(abrirItem);
        menu.addSeparator();
        menu.add(syncItem);
        menu.add(statusItem);
        menu.addSeparator();
        menu.add(sobreItem);
        menu.add(sairItem);
        return menu;
    }

    private Image criarIcone() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, 2);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(34, 139, 34));
        g2.fillOval(0, 0, size, size);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", 1, 9));
        FontMetrics fm = g2.getFontMetrics();
        String text = "VR";
        int textX = (size - fm.stringWidth(text)) / 2;
        int textY = (size - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(text, textX, textY);
        g2.dispose();
        return img;
    }
}

