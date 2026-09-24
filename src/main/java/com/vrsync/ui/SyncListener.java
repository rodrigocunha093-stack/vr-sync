/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.ui;

public interface SyncListener {
    public void onSyncInicio(int var1);

    public void onSyncFim(int var1, long var2, boolean var4, String var5);

    public void onModuloExtraindo(String var1);

    public void onModuloExtraido(String var1, int var2, long var3);

    public void onModuloErro(String var1, String var2);

    public void onEnviando(String var1, int var2, int var3, int var4);

    public void onEnviado(String var1, boolean var2);

    public void onLog(String var1);
}

