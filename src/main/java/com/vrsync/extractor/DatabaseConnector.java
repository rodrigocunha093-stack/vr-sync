/*
 * Decompiled with CFR 0.152.
 */
package com.vrsync.extractor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseConnector
implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnector.class);
    private Connection conn;
    private String tipo;

    public void conectar(String server, int port, String database, String user, String password, String tipo) throws SQLException {
        this.tipo = tipo;
        String url = "postgres".equalsIgnoreCase(tipo) ? String.format("jdbc:postgresql://%s:%d/%s", server, port, database) : String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true", server, port, database);
        log.info("Conectando: {} ({})", (Object)url, (Object)tipo);
        try {
            if ("postgres".equalsIgnoreCase(tipo)) {
                Class.forName("org.postgresql.Driver");
            } else {
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            }
        }
        catch (ClassNotFoundException e) {
            throw new SQLException("Driver JDBC nao encontrado: " + e.getMessage());
        }
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        if ("postgres".equalsIgnoreCase(tipo)) {
            props.setProperty("connectTimeout", "15");
            props.setProperty("socketTimeout", "120");
        } else {
            props.setProperty("loginTimeout", "15");
        }
        this.conn = DriverManager.getConnection(url, props);
        this.conn.setAutoCommit(true);
        log.info("Conectado ao banco {} com sucesso", (Object)tipo);
    }

    public List<Map<String, Object>> executar(String sql, String label) throws SQLException {
        if (this.conn == null || this.conn.isClosed()) {
            throw new SQLException("Conexao nao estabelecida");
        }
        long inicio = System.currentTimeMillis();
        try (Statement stmt = this.conn.createStatement();){
            stmt.setQueryTimeout(120);
            if ("postgres".equalsIgnoreCase(this.tipo) && this.conn.getAutoCommit()) {
                this.conn.setAutoCommit(false);
            }
            stmt.setFetchSize(5000);
            ResultSet rs = stmt.executeQuery(sql);
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            ArrayList<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            while (rs.next()) {
                LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= cols; ++i) {
                    String colName = meta.getColumnLabel(i).toLowerCase();
                    Object val = rs.getObject(i);
                    if (val instanceof Date) {
                        Date d = (Date)val;
                        val = d.toString();
                    } else if (val instanceof Timestamp) {
                        Timestamp ts = (Timestamp)val;
                        val = ts.toInstant().toString();
                    } else if (val instanceof BigDecimal) {
                        BigDecimal bd = (BigDecimal)val;
                        val = bd.doubleValue();
                    }
                    row.put(colName, val);
                }
                rows.add(row);
            }
            long ms = System.currentTimeMillis() - inicio;
            log.info("{}: {} registros em {}ms", label, rows.size(), ms);
            if ("postgres".equalsIgnoreCase(this.tipo)) {
                this.conn.setAutoCommit(true);
            }
            ArrayList<Map<String, Object>> arrayList = rows;
            return arrayList;
        }
    }

    public boolean testarConexao() {
        try {
            if (this.conn == null || this.conn.isClosed()) {
                return false;
            }
            try (Statement stmt = this.conn.createStatement()) {
                stmt.executeQuery("SELECT 1");
                return true;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public String getTipo() {
        return this.tipo;
    }

    @Override
    public void close() {
        if (this.conn != null) {
            try {
                this.conn.close();
                log.info("Conexao encerrada");
            }
            catch (SQLException e) {
                log.warn("Erro ao fechar conexao: {}", (Object)e.getMessage());
            }
            this.conn = null;
        }
    }
}

