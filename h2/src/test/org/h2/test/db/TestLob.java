/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.h2.api.ErrorCode;
import org.h2.engine.Constants;
import org.h2.engine.SysProperties;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.store.FileLister;
import org.h2.store.fs.FileUtils;
import org.h2.test.TestAll;
import org.h2.test.TestBase;
import org.h2.test.TestDb;
import org.h2.tools.Recover;
import org.h2.tools.SimpleResultSet;
import org.h2.util.IOUtils;
import org.h2.util.JdbcUtils;
import org.h2.util.Task;
import org.h2.value.ValueBlob;
import org.h2.value.ValueClob;
import org.h2.value.ValueLob;

/**
 * Tests LOB and CLOB data types.
 */
public class TestLob extends TestDb {

    private static final String MORE_THAN_128_CHARS =
            "12345678901234567890123456789012345678901234567890" +
            "12345678901234567890123456789012345678901234567890" +
            "12345678901234567890123456789";

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase test = TestBase.createCaller().init();
        TestAll config = test.config;
//        config.memory = true;
//        test.config.big = true;
//        config.cipher = "AES";
//        config.cacheType = "SOFT_LRU";
//        config.diskUndo = true;
//        config.diskResult = true;
//        config.traceLevelFile = 1;
//        config.throttle = 1;

        test.println(config.toString());
        for (int i = 0; i < 10; i++) {
            test.testFromMain();
            test.println("Done pass #" + i);
        }
    }

    @Override
    public void test() throws Exception {
        testConcurrentSelectAndUpdate();
        testReclamationOnInDoubtRollback();
        testRemoveAfterDeleteAndClose();
        testRemovedAfterTimeout();
        testConcurrentRemoveRead();
        testCloseLobTwice();
        testClobWithRandomUnicodeChars();
        testCommitOnExclusiveConnection();
        testReadManyLobs();
        testLobSkip();
        testLobSkipPastEnd();
        testCreateIndexOnLob();
        testBlobInputStreamSeek(true);
        testBlobInputStreamSeek(false);
        testDeadlock();
        testCopyManyLobs();
        testCopyLob();
        testConcurrentCreate();
        testLobInLargeResult();
        testUniqueIndex();
        testConvert();
        testCreateAsSelect();
        testLobServerMemory();
        testUpdatingLobRow();
        testBufferedInputStreamBug();
        if (config.memory) {
            return;
        }
        testLargeClob();
        testLobUpdateMany();
        testLobVariable();
        testLobDrop();
        testLobNoClose();
        testLobTransactions(10);
        testLobTransactions(10000);
        testLobRollbackStop();
        testLobCopy();
        testLobHibernate();
        testLobCopy2();
        testManyLobs();
        testClob();
        testUpdateLob();
        testLobReconnect();
        testLob(false);
        testLob(true);
        testJavaObject();
        testLobInValueResultSet();
        // cannot run this on CI, will cause OOM
        // testLimits();
        deleteDb("lob");
    }

    private void testReclamationOnInDoubtRollback() throws Exception {
        if (config.memory || config.cipher != null) {
            return;
        }
        deleteDb("lob");
        try (Connection conn = getConnection("lob")) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS dataTable("
                        + "dataStamp BIGINT PRIMARY KEY, "
                        + "data BLOB)");
            }

            conn.setAutoCommit(false);
            Random rnd = new Random(0);
            try (PreparedStatement pstmt = conn.prepareStatement("INSERT INTO dataTable VALUES(?, ?)")) {
                for (int i = 0; i < 100; ++i) {
                    int numBytes = 1024 * 1024;
                    byte[] data = new byte[numBytes];
                    rnd.nextBytes(data);
                    pstmt.setLong(1, i);
                    pstmt.setBytes(2, data);
                    pstmt.executeUpdate();
                }
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("PREPARE COMMIT lobtx");
                st.execute("SHUTDOWN IMMEDIATELY");
            }
        }

        try (Connection conn = getConnection("lob")) {
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT * FROM INFORMATION_SCHEMA.IN_DOUBT")) {
                assertTrue("No in-doubt tx", rs.first());
                assertEquals("LOBTX", rs.getString("TRANSACTION_NAME"));
                assertFalse("more than one in-doubt tx", rs.next());
                st.executeUpdate("ROLLBACK TRANSACTION lobtx; CHECKPOINT SYNC");
            }
        }

        try (Connection conn = getConnection("lob")) {
            try (Statement st = conn.createStatement()) {
                st.execute("SHUTDOWN COMPACT");
            }
        }

        ArrayList<String> dbFiles = FileLister.getDatabaseFiles(getBaseDir(), "lob", false);
        assertEquals(1, dbFiles.size());
        File file = new File(dbFiles.get(0));
        assertTrue(file.exists());
        long fileSize = file.length();
        assertTrue("File size=" + fileSize, fileSize < 13000);
    }

    private void testRemoveAfterDeleteAndClose() throws Exception {
        if (config.memory || config.cipher != null) {
            return;
        }
        deleteDb("lob");
        Connection conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int primary key, data clob)");
        for (int i = 0; i < 10; i++) {
            stat.execute("insert into test values(1, space(100000))");
            if (i > 5) {
                ResultSet rs = stat.executeQuery("select * from test");
                rs.next();
                Clob c = rs.getClob(2);
                stat.execute("delete from test where id = 1");
                c.getSubString(1, 10);
            } else {
                stat.execute("delete from test where id = 1");
            }
        }
        // some clobs are removed only here (those that were queries for)
        conn.close();
        Recover.execute(getBaseDir(), "lob");
        long size = FileUtils.size(getBaseDir() + "/lob.h2.sql");
        assertTrue("size: " + size, size > 1000 && size < 10000);
    }

    private void testLargeClob() throws Exception {
        deleteDb("lob");
        Connection conn;
        conn = reconnect(null);
        conn.createStatement().execute(
                "CREATE TABLE TEST(ID IDENTITY, C CLOB)");
        PreparedStatement prep = conn.prepareStatement(
                "INSERT INTO TEST(C) VALUES(?)");
        int len = SysProperties.LOB_CLIENT_MAX_SIZE_MEMORY + 1;
        prep.setCharacterStream(1, getRandomReader(len, 2), -1);
        prep.execute();
        conn = reconnect(conn);
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT * FROM TEST ORDER BY ID");
        rs.next();
        assertEqualReaders(getRandomReader(len, 2),
                rs.getCharacterStream("C"), -1);
        assertFalse(rs.next());
        conn.close();
    }

    private void testRemovedAfterTimeout() throws Exception {
        if (config.lazy) {
            return;
        }
        deleteDb("lob");
        final String url = getURL("lob;lob_timeout=200", true);
        Connection conn = getConnection(url);
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int primary key, data clob)");
        PreparedStatement prep = conn.prepareStatement("insert into test values(?, ?)");
        prep.setInt(1, 1);
        prep.setString(2, "aaa" + new String(new char[1024 * 16]).replace((char) 0, 'x'));
        prep.execute();
        prep.setInt(1, 2);
        prep.setString(2, "bbb" + new String(new char[1024 * 16]).replace((char) 0, 'x'));
        prep.execute();
        ResultSet rs = stat.executeQuery("select * from test order by id");
        rs.next();
        Clob c1 = rs.getClob(2);
        assertEquals("aaa", c1.getSubString(1, 3));
        rs.next();
        assertEquals("aaa", c1.getSubString(1, 3));
        rs.close();
        assertEquals("aaa", c1.getSubString(1, 3));
        stat.execute("delete from test");
        c1.getSubString(1, 3);
        // wait until it times out
        Thread.sleep(250);
        // start a new transaction, to be sure
        stat.execute("delete from test");
        c1.getSubString(1, 3);
        conn.close();
        assertThrows(SQLException.class, c1).getSubString(1, 3);
    }

    private void testConcurrentRemoveRead() throws Exception {
        if (config.lazy) {
            return;
        }
        deleteDb("lob");
        final String url = getURL("lob", true);
        Connection conn = getConnection(url);
        Statement stat = conn.createStatement();
        stat.execute("set max_length_inplace_lob 5");
        stat.execute("create table lob(data clob)");
        stat.execute("insert into lob values(space(100))");
        Connection conn2 = getConnection(url);
        Statement stat2 = conn2.createStatement();
        ResultSet rs = stat2.executeQuery("select data from lob");
        rs.next();
        stat.execute("delete lob");
        InputStream in = rs.getBinaryStream(1);
        in.read();
        conn2.close();
        conn.close();
    }

    private void testCloseLobTwice() throws SQLException {
        deleteDb("lob");
        Connection conn = getConnection("lob");
        PreparedStatement prep = conn.prepareStatement("set @c = ?");
        prep.setCharacterStream(1, new StringReader(
                new String(new char[10000])), 10000);
        prep.execute();
        prep.setCharacterStream(1, new StringReader(
                new String(new char[10001])), 10001);
        prep.execute();
        conn.setAutoCommit(true);
        conn.close();
    }

    private void testReadManyLobs() throws Exception {
        deleteDb("lob");
        Connection conn;
        conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table test(id identity, data clob)");
        PreparedStatement prep = conn.prepareStatement(
                "insert into test(data) values ?");
        byte[] data = new byte[256];
        Random r = new Random(1);
        for (int i = 0; i < 1000; i++) {
            r.nextBytes(data);
            prep.setBinaryStream(1, new ByteArrayInputStream(data), -1);
            prep.execute();
        }
        ResultSet rs = stat.executeQuery("select * from test");
        while (rs.next()) {
            rs.getString(2);
        }
        conn.close();
    }

    private void testLobSkip() throws Exception {
        deleteDb("lob");
        Connection conn;
        conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.executeUpdate("create table test(x blob) as select secure_rand(1000)");
        ResultSet rs = stat.executeQuery("select * from test");
        rs.next();
        Blob b = rs.getBlob(1);
        byte[] test = b.getBytes(5 + 1, 1000 - 5);
        assertEquals(1000 - 5, test.length);
        stat.execute("drop table test");
        conn.close();
    }

    private void testLobSkipPastEnd() throws Exception {
        if (config.memory) {
            return;
        }
        deleteDb("lob");
        Connection conn;
        conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int, data blob)");
        byte[] data = new byte[150000];
        new Random(0).nextBytes(data);
        PreparedStatement prep = conn.prepareStatement("insert into test values(1, ?)");
        prep.setBytes(1, data);
        prep.execute();
        ResultSet rs = stat.executeQuery("select data from test");
        rs.next();
        for (int blockSize = 1; blockSize < 100000; blockSize *= 10) {
            for (int i = 0; i < data.length; i += 1000) {
                InputStream in = rs.getBinaryStream(1);
                in.skip(i);
                byte[] d2 = new byte[data.length];
                int l = Math.min(blockSize, d2.length - i);
                l = in.read(d2, i, l);
                if (i >= data.length) {
                    assertEquals(-1, l);
                } else if (i + blockSize >= data.length) {
                    assertEquals(data.length - i, l);
                }
                for (int j = i; j < blockSize && j < d2.length; j++) {
                    assertEquals(data[j], d2[j]);
                }
            }
        }
        stat.execute("drop table test");
        conn.close();
    }

    private void testCreateIndexOnLob() throws Exception {
        if (config.memory) {
            return;
        }
        deleteDb("lob");
        Connection conn;
        conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int, name clob)");
        assertThrows(ErrorCode.FEATURE_NOT_SUPPORTED_1, stat).
                execute("create index idx_n on test(name)");
        stat.execute("drop table test");
        conn.close();
    }

    private void testBlobInputStreamSeek(boolean upgraded) throws Exception {
        deleteDb("lob");
        Connection conn;
        conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int primary key, data blob)");
        PreparedStatement prep;
        Random random = new Random();
        byte[] buff = new byte[500000];
        for (int i = 0; i < 10; i++) {
            prep = conn.prepareStatement("insert into test values(?, ?)");
            prep.setInt(1, i);
            random.setSeed(i);
            random.nextBytes(buff);
            prep.setBinaryStream(2, new ByteArrayInputStream(buff), -1);
            prep.execute();
        }
        prep = conn.prepareStatement("select * from test where id = ?");
        for (int i = 0; i < 1; i++) {
            random.setSeed(i);
            random.nextBytes(buff);
            for (int j = 0; j < buff.length; j += 10000) {
                prep.setInt(1, i);
                ResultSet rs = prep.executeQuery();
                rs.next();
                InputStream in = rs.getBinaryStream(2);
                in.skip(j);
                int t = in.read();
                assertEquals(t, buff[j] & 0xff);
            }
        }
        conn.close();
    }

    /**
     * Test for issue 315: Java Level Deadlock on Database & Session Objects
     */
    private void testDeadlock() throws Exception {
        deleteDb("lob");
        Connection conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int primary key, name clob)");
        stat.execute("insert into test select x, space(10000) from system_range(1, 3)");
        final Connection conn2 = getConnection("lob");
        Task task = new Task() {

            @Override
            public void call() throws Exception {
                Statement stat = conn2.createStatement();
                stat.setFetchSize(1);
                for (int i = 0; !stop; i++) {
                    ResultSet rs = stat.executeQuery(
                            "select * from test where id > -" + i);
                    while (rs.next()) {
                        // ignore
                    }
                }
            }

        };
        task.execute();
        stat.execute("create table test2(id int primary key, name clob)");
        for (int i = 0; i < 100; i++) {
            stat.execute("delete from test2");
            stat.execute("insert into test2 values(1, space(10000 + " + i + "))");
        }
        task.get();
        conn.close();
        conn2.close();
    }

    Connection getDeadlock2Connection() throws SQLException {
        return getConnection("lob;LOCK_TIMEOUT=60000");
    }

    private void testCopyManyLobs() throws Exception {
        deleteDb("lob");
        Connection conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table test(id identity default on null, data clob) " +
                "as select null, space(10000)");
        stat.execute("insert into test(data) select data from test");
        stat.execute("insert into test(data) select data from test");
        stat.execute("insert into test(data) select data from test");
        stat.execute("insert into test(data) select data from test");
        stat.execute("delete from test where id < 10");
        stat.execute("shutdown compact");
        conn.close();
    }

    private void testCopyLob() throws Exception {
        if (config.memory) {
            return;
        }
        deleteDb("lob");
        Connection conn;
        Statement stat;
        ResultSet rs;
        conn = getConnection("lob");
        stat = conn.createStatement();
        stat.execute("create table test(id identity, data clob) " +
                "as select 1, space(10000)");
        stat.execute("insert into test(id, data) select 2, data from test");
        stat.execute("delete from test where id = 1");
        conn.close();
        conn = getConnection("lob");
        stat = conn.createStatement();
        rs = stat.executeQuery("select * from test");
        rs.next();
        assertEquals(10000, rs.getString(2).length());
        conn.close();
    }

    private void testConcurrentCreate() throws Exception {
        deleteDb("lob");
        final JdbcConnection conn1 = (JdbcConnection) getConnection("lob");
        final JdbcConnection conn2 = (JdbcConnection) getConnection("lob");
        conn1.setAutoCommit(false);
        conn2.setAutoCommit(false);

        final byte[] buffer = new byte[10000];

        Task task1 = new Task() {
            @Override
            public void call() throws Exception {
                while (!stop) {
                    Blob b = conn1.createBlob();
                    OutputStream out = b.setBinaryStream(1);
                    out.write(buffer);
                    out.close();
                }
            }
        };
        Task task2 = new Task() {
            @Override
            public void call() throws Exception {
                while (!stop) {
                    Blob b = conn2.createBlob();
                    OutputStream out = b.setBinaryStream(1);
                    out.write(buffer);
                    out.close();
                }
            }
        };
        task1.execute();
        task2.execute();
        Thread.sleep(1000);
        task1.get();
        task2.get();
        conn1.close();
        conn2.close();
    }

    private void testLobInLargeResult() throws Exception {
        deleteDb("lob");
        Connection conn;
        Statement stat;
        conn = getConnection("lob");
        stat = conn.createStatement();
        stat.execute("create table test(id int, data clob) as " +
                "select x, null from system_range(1, 1000)");
        stat.execute("insert into test values(0, space(10000))");
        stat.execute("set max_memory_rows 100");
        ResultSet rs = stat.executeQuery("select * from test order by id desc");
        while (rs.next()) {
            // this threw a NullPointerException because
            // the disk based result set didn't know the lob handler
        }
        conn.close();
    }

    private void testUniqueIndex() throws Exception {
        deleteDb("lob");
        Connection conn;
        Statement stat;
        conn = getConnection("lob");
        stat = conn.createStatement();
        assertThrows(ErrorCode.FEATURE_NOT_SUPPORTED_1, stat).execute("create memory table test(x clob unique)");
        conn.close();
    }

    private void testConvert() throws Exception {
        deleteDb("lob");
        Connection conn;
        Statement stat;
        conn = getConnection("lob");
        stat = conn.createStatement();
        stat.execute("create table test(id int, data blob)");
        stat.execute("insert into test values(1, '')");
        ResultSet rs;
        rs = stat.executeQuery("select cast(data as clob) from test");
        rs.next();
        assertEquals("", rs.getString(1));
        stat.execute("drop table test");

        stat.execute("create table test(id int, data clob)");
        stat.execute("insert into test values(1, '')");
        rs = stat.executeQuery("select cast(data as blob) from test");
        rs.next();
        assertEquals("", rs.getString(1));

        conn.close();
    }

    private void testCreateAsSelect() throws Exception {
        deleteDb("lob");
        Connection conn;
        Statement stat;
        conn = getConnection("lob");
        stat = conn.createStatement();
        stat.execute("create table test(id int, data clob) as select 1, space(10000)");
        conn.close();
    }

    private void testLobUpdateMany() throws SQLException {
        deleteDb("lob");
        Connection conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table post(id int primary key, text clob) as " +
                "select x, space(96) from system_range(1, 329)");
        PreparedStatement prep = conn.prepareStatement("update post set text = ?");
        prep.setCharacterStream(1, new StringReader(new String(new char[1025])), -1);
        prep.executeUpdate();
        conn.close();
    }

    private void testLobServerMemory() throws SQLException {
        deleteDb("lob");
        Connection conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE TEST(ID INT, DATA CLOB)");
        PreparedStatement prep = conn.prepareStatement("INSERT INTO TEST VALUES(1, ?)");
        StringReader reader = new StringReader(new String(new char[100000]));
        prep.setCharacterStream(1, reader, -1);
        prep.execute();
        conn.close();
    }

    private void testLobVariable() throws SQLException {
        deleteDb("lob");
        Connection conn = reconnect(null);
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE TEST(ID INT, DATA CLOB)");
        stat.execute("INSERT INTO TEST VALUES(1, SPACE(100000))");
        stat.execute("SET @TOTAL = SELECT DATA FROM TEST WHERE ID=1");
        stat.execute("DROP TABLE TEST");
        stat.execute("CALL @TOTAL LIKE '%X'");
        stat.execute("CREATE TABLE TEST(ID INT, DATA CLOB)");
        stat.execute("INSERT INTO TEST VALUES(1, @TOTAL)");
        stat.execute("INSERT INTO TEST VALUES(2, @TOTAL)");
        stat.execute("DROP TABLE TEST");
        stat.execute("CALL @TOTAL LIKE '%X'");
        conn.close();
    }

    private void testLobDrop() throws SQLException {
        if (config.networked) {
            return;
        }
        deleteDb("lob");
        Connection conn = reconnect(null);
        Statement stat = conn.createStatement();
        for (int i = 0; i < 500; i++) {
            stat.execute("CREATE TABLE T" + i + "(ID INT, C CLOB)");
        }
        stat.execute("CREATE TABLE TEST(ID INT, C CLOB)");
        stat.execute("INSERT INTO TEST VALUES(1, SPACE(10000))");
        for (int i = 0; i < 500; i++) {
            stat.execute("DROP TABLE T" + i);
        }
        ResultSet rs = stat.executeQuery("SELECT * FROM TEST");
        while (rs.next()) {
            rs.getString("C");
        }
        conn.close();
    }

    private void testLobNoClose() throws Exception {
        if (config.networked) {
            return;
        }
        deleteDb("lob");
        Connection conn = reconnect(null);
        conn.createStatement().execute(
                "CREATE TABLE TEST(ID IDENTITY, DATA CLOB)");
        conn.createStatement().execute(
                "INSERT INTO TEST VALUES(1, SPACE(10000))");
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT DATA FROM TEST");
        rs.next();
        SysProperties.lobCloseBetweenReads = true;
        Reader in = rs.getCharacterStream(1);
        in.read();
        conn.createStatement().execute("DELETE FROM TEST");
        SysProperties.lobCloseBetweenReads = false;
        conn.createStatement().execute(
                "INSERT INTO TEST VALUES(1, SPACE(10000))");
        rs = conn.createStatement().executeQuery(
                "SELECT DATA FROM TEST");
        rs.next();
        in = rs.getCharacterStream(1);
        in.read();
        conn.setAutoCommit(false);
        try {
            conn.createStatement().execute("DELETE FROM TEST");
            conn.commit();
            // DELETE does not fail in Linux, but in Windows
            // error("Error expected");
            // but reading afterwards should fail
            int len = 0;
            while (true) {
                int x = in.read();
                if (x < 0) {
                    break;
                }
                len++;
            }
            in.close();
            if (len > 0) {
                // in Linux, it seems it is still possible to read in files
                // even if they are deleted
                if (System.getProperty("os.name").indexOf("Windows") > 0) {
                    fail("Error expected; len=" + len);
                }
            }
        } catch (SQLException e) {
            assertKnownException(e);
        }
        conn.rollback();
        conn.close();
    }

    private void testLobTransactions(int spaceLen) throws SQLException {
        deleteDb("lob");
        Connection conn = reconnect(null);
        conn.createStatement().execute("CREATE TABLE TEST(ID IDENTITY, " +
                "DATA CLOB, DATA2 VARCHAR)");
        conn.setAutoCommit(false);
        Random random = new Random(0);
        int rows = 0;
        Savepoint sp = null;
        int len = getSize(100, 400);
        // config.traceTest = true;
        for (int i = 0; i < len; i++) {
            switch (random.nextInt(10)) {
            case 0:
                trace("insert " + i);
                conn.createStatement().execute(
                        "INSERT INTO TEST(DATA, DATA2) VALUES('" + i +
                        "' || SPACE(" + spaceLen + "), '" + i + "')");
                rows++;
                break;
            case 1:
                if (rows > 0) {
                    int x = random.nextInt(rows);
                    trace("delete " + x);
                    conn.createStatement().execute(
                            "DELETE FROM TEST WHERE ID=" + x);
                }
                break;
            case 2:
                if (rows > 0) {
                    int x = random.nextInt(rows);
                    trace("update " + x);
                    conn.createStatement().execute(
                            "UPDATE TEST SET DATA='x' || DATA, " +
                            "DATA2='x' || DATA2 WHERE ID=" + x);
                }
                break;
            case 3:
                if (rows > 0) {
                    trace("commit");
                    conn.commit();
                    sp = null;
                }
                break;
            case 4:
                if (rows > 0) {
                    trace("rollback");
                    conn.rollback();
                    sp = null;
                }
                break;
            case 5:
                trace("savepoint");
                sp = conn.setSavepoint();
                break;
            case 6:
                if (sp != null) {
                    trace("rollback to savepoint");
                    conn.rollback(sp);
                }
                break;
            case 7:
                if (rows > 0) {
                    trace("checkpoint");
                    conn.createStatement().execute("CHECKPOINT");
                    trace("shutdown immediately");
                    conn.createStatement().execute("SHUTDOWN IMMEDIATELY");
                    trace("shutdown done");
                    conn = reconnect(conn);
                    conn.setAutoCommit(false);
                    sp = null;
                }
                break;
            default:
            }
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT * FROM TEST");
            while (rs.next()) {
                int id = rs.getInt("ID");
                String d1 = rs.getString("DATA").trim();
                String d2 = rs.getString("DATA2");
                assertEquals("id:" + id, d2, d1);
            }

        }
        conn.close();
    }

    private void testLobRollbackStop() throws SQLException {
        deleteDb("lob");
        Connection conn = reconnect(null);
        conn.createStatement().execute(
                "CREATE TABLE TEST(ID INT PRIMARY KEY, DATA CLOB)");
        conn.createStatement().execute(
                "INSERT INTO TEST VALUES(1, SPACE(10000))");
        conn.setAutoCommit(false);
        conn.createStatement().execute("DELETE FROM TEST");
        conn.createStatement().execute("CHECKPOINT");
        conn.createStatement().execute("SHUTDOWN IMMEDIATELY");
        conn = reconnect(conn);
        ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM TEST");
        assertTrue(rs.next());
        rs.getInt(1);
        assertEquals(10000, rs.getString(2).length());
        conn.close();
    }

    private void testLobCopy() throws SQLException {
        deleteDb("lob");
        Connection conn = reconnect(null);
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int, data clob)");
        stat.execute("insert into test values(1, space(1000));");
        stat.execute("insert into test values(2, space(10000));");
        stat.execute("create table test2(id int, data clob);");
        stat.execute("insert into test2 select * from test;");
        stat.execute("drop table test;");
        stat.execute("select * from test2;");
        stat.execute("update test2 set id=id;");
        stat.execute("select * from test2;");
        conn.close();
    }

    private void testLobHibernate() throws Exception {
        deleteDb("lob");
        Connection conn0 = reconnect(null);

        conn0.getAutoCommit();
        conn0.setAutoCommit(false);
        DatabaseMetaData dbMeta0 = conn0.getMetaData();
        dbMeta0.getDatabaseProductName();
        dbMeta0.getDatabaseMajorVersion();
        dbMeta0.getDatabaseProductVersion();
        dbMeta0.getDriverName();
        dbMeta0.getDriverVersion();
        dbMeta0.supportsResultSetType(1004);
        dbMeta0.supportsBatchUpdates();
        dbMeta0.dataDefinitionCausesTransactionCommit();
        dbMeta0.dataDefinitionIgnoredInTransactions();
        dbMeta0.supportsGetGeneratedKeys();
        conn0.getAutoCommit();
        conn0.getAutoCommit();
        conn0.commit();
        conn0.setAutoCommit(true);
        Statement stat0 = conn0.createStatement();
        stat0.executeUpdate("drop table CLOB_ENTITY if exists");
        stat0.getWarnings();
        stat0.executeUpdate("create table CLOB_ENTITY (ID bigint not null, " +
                "DATA clob, CLOB_DATA clob, primary key (ID))");
        stat0.getWarnings();
        stat0.close();
        conn0.getWarnings();
        conn0.clearWarnings();
        conn0.setAutoCommit(false);
        conn0.getAutoCommit();
        conn0.getAutoCommit();
        PreparedStatement prep0 = conn0.prepareStatement(
                "select max(ID) from CLOB_ENTITY");
        ResultSet rs0 = prep0.executeQuery();
        rs0.next();
        rs0.getLong(1);
        rs0.wasNull();
        rs0.close();
        prep0.close();
        conn0.getAutoCommit();
        PreparedStatement prep1 = conn0
                .prepareStatement("insert into CLOB_ENTITY" +
                        "(DATA, CLOB_DATA, ID) values (?, ?, ?)");
        prep1.setNull(1, 2005);
        StringBuilder buff = new StringBuilder(10000);
        for (int i = 0; i < 10000; i++) {
            buff.append((char) ('0' + (i % 10)));
        }
        Reader x = new StringReader(buff.toString());
        prep1.setCharacterStream(2, x, 10000);
        prep1.setLong(3, 1);
        prep1.addBatch();
        prep1.executeBatch();
        prep1.close();
        conn0.getAutoCommit();
        conn0.getAutoCommit();
        conn0.commit();
        conn0.isClosed();
        conn0.getWarnings();
        conn0.clearWarnings();
        conn0.getAutoCommit();
        conn0.getAutoCommit();
        PreparedStatement prep2 = conn0
                .prepareStatement("select c_.ID as ID0_0_, c_.DATA as S_, " +
                        "c_.CLOB_DATA as CLOB3_0_0_ from CLOB_ENTITY c_ where c_.ID=?");
        prep2.setLong(1, 1);
        ResultSet rs1 = prep2.executeQuery();
        rs1.next();
        rs1.getCharacterStream("S_");
        Clob clob0 = rs1.getClob("CLOB3_0_0_");
        rs1.wasNull();
        rs1.next();
        rs1.close();
        prep2.getMaxRows();
        prep2.getQueryTimeout();
        prep2.close();
        conn0.getAutoCommit();
        Reader r;
        int ch;
        r = clob0.getCharacterStream();
        for (int i = 0; i < 10000; i++) {
            ch = r.read();
            if (ch != ('0' + (i % 10))) {
                fail("expected " + (char) ('0' + (i % 10)) +
                        " got: " + ch + " (" + (char) ch + ")");
            }
        }
        ch = r.read();
        if (ch != -1) {
            fail("expected -1 got: " + ch);
        }
        r.close();
        r = clob0.getCharacterStream(1235, 1000);
        for (int i = 1234; i < 2234; i++) {
            ch = r.read();
            if (ch != ('0' + (i % 10))) {
                fail("expected " + (char) ('0' + (i % 10)) +
                        " got: " + ch + " (" + (char) ch + ")");
            }
        }
        ch = r.read();
        if (ch != -1) {
            fail("expected -1 got: " + ch);
        }
        r.close();
        assertThrows(ErrorCode.INVALID_VALUE_2, clob0).getCharacterStream(10001, 1);
        assertThrows(ErrorCode.INVALID_VALUE_2, clob0).getCharacterStream(10002, 0);
        conn0.close();
    }

    private void testLobCopy2() throws SQLException {
        deleteDb("lob");
        Connection conn;
        conn = reconnect(null);
        Statement stat = conn.createStatement();
        conn = reconnect(conn);
        stat = conn.createStatement();
        stat.execute("create table test(text clob)");
        stat.execute("create table test2(text clob)");
        StringBuilder buff = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            buff.append(' ');
        }
        String spaces = buff.toString();
        stat.execute("insert into test values('" + spaces + "')");
        stat.execute("insert into test2 select * from test");
        ResultSet rs = stat.executeQuery("select * from test2");
        rs.next();
        assertEquals(spaces, rs.getString(1));
        stat.execute("drop table test");
        rs = stat.executeQuery("select * from test2");
        rs.next();
        assertEquals(spaces, rs.getString(1));
        stat.execute("alter table test2 add column id int before text");
        rs = stat.executeQuery("select * from test2");
        rs.next();
        assertEquals(spaces, rs.getString("text"));
        conn.close();
    }

    private void testManyLobs() throws Exception {
        deleteDb("lob");
        Connection conn;
        conn = reconnect(null);
        conn.createStatement().execute(
                "CREATE TABLE TEST(ID INT PRIMARY KEY, B BLOB, C CLOB)");
        int len = getSize(10, 2000);
        if (config.networked) {
            len = 100;
        }

        int first = 1, increment = 19;

        PreparedStatement prep = conn.prepareStatement(
                "INSERT INTO TEST(ID, B, C) VALUES(?, ?, ?)");
        for (int i = first; i < len; i += increment) {
            int l = i;
            prep.setInt(1, i);
            prep.setBinaryStream(2, getRandomStream(l, i), -1);
            prep.setCharacterStream(3, getRandomReader(l, i), -1);
            prep.execute();
        }

        conn = reconnect(conn);
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT * FROM TEST ORDER BY ID");
        while (rs.next()) {
            int i = rs.getInt("ID");
            Blob b = rs.getBlob("B");
            Clob c = rs.getClob("C");
            int l = i;
            assertEquals(l, b.length());
            assertEquals(l, c.length());
            assertEqualStreams(getRandomStream(l, i), b.getBinaryStream(), -1);
            assertEqualReaders(getRandomReader(l, i), c.getCharacterStream(), -1);
        }

        prep = conn.prepareStatement(
                "UPDATE TEST SET B=?, C=? WHERE ID=?");
        for (int i = first; i < len; i += increment) {
            int l = i;
            prep.setBinaryStream(1, getRandomStream(l, -i), -1);
            prep.setCharacterStream(2, getRandomReader(l, -i), -1);
            prep.setInt(3, i);
            prep.execute();
        }

        conn = reconnect(conn);
        rs = conn.createStatement().executeQuery(
                "SELECT * FROM TEST ORDER BY ID");
        while (rs.next()) {
            int i = rs.getInt("ID");
            Blob b = rs.getBlob("B");
            Clob c = rs.getClob("C");
            int l = i;
            assertEquals(l, b.length());
            assertEquals(l, c.length());
            assertEqualStreams(getRandomStream(l, -i), b.getBinaryStream(), -1);
            assertEqualReaders(getRandomReader(l, -i), c.getCharacterStream(), -1);
        }

        conn.close();
    }

    private void testClob() throws Exception {
        deleteDb("lob");
        Connection conn;
        conn = reconnect(null);
        conn.createStatement().execute(
                "CREATE TABLE TEST(ID IDENTITY, C CLOB)");
        PreparedStatement prep = conn.prepareStatement(
                "INSERT INTO TEST(C) VALUES(?)");
        prep.setCharacterStream(1,
                new CharArrayReader("Bohlen".toCharArray()), "Bohlen".length());
        prep.execute();
        prep.setCharacterStream(1,
                new CharArrayReader("B\u00f6hlen".toCharArray()), "B\u00f6hlen".length());
        prep.execute();
        prep.setCharacterStream(1, getRandomReader(501, 1), -1);
        prep.execute();
        prep.setCharacterStream(1, getRandomReader(1501, 2), 401);
        prep.execute();
        conn = reconnect(conn);
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT * FROM TEST ORDER BY ID");
        rs.next();
        assertEquals("Bohlen", rs.getString("C"));
        assertEqualReaders(new CharArrayReader("Bohlen".toCharArray()),
                rs.getCharacterStream("C"), -1);
        rs.next();
        assertEqualReaders(new CharArrayReader("B\u00f6hlen".toCharArray()),
                rs.getCharacterStream("C"), -1);
        rs.next();
        assertEqualReaders(getRandomReader(501, 1),
                rs.getCharacterStream("C"), -1);
        Clob clob = rs.getClob("C");
        assertEqualReaders(getRandomReader(501, 1),
                clob.getCharacterStream(), -1);
        assertEquals(501, clob.length());
        rs.next();
        assertEqualReaders(getRandomReader(401, 2),
                rs.getCharacterStream("C"), -1);
        assertEqualReaders(getRandomReader(1500, 2),
                rs.getCharacterStream("C"), 401);
        clob = rs.getClob("C");
        assertEqualReaders(getRandomReader(1501, 2),
                clob.getCharacterStream(), 401);
        assertEqualReaders(getRandomReader(401, 2),
                clob.getCharacterStream(), 401);
        assertEquals(401, clob.length());
        assertFalse(rs.next());
        conn.close();
    }

    private Connection reconnect(Connection conn) throws SQLException {
        long time = System.nanoTime();
        if (conn != null) {
            JdbcUtils.closeSilently(conn);
        }
        conn = getConnection("lob");
        trace("re-connect=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - time));
        return conn;
    }

    private void testUpdateLob() throws SQLException {
        deleteDb("lob");
        Connection conn;
        conn = reconnect(null);

        PreparedStatement prep = conn
                .prepareStatement(
                        "CREATE TABLE IF NOT EXISTS p( id int primary key, rawbyte BLOB ); ");
        prep.execute();
        prep.close();
        prep = conn.prepareStatement("INSERT INTO p(id) VALUES(?);");
        for (int i = 0; i < 10; i++) {
            prep.setInt(1, i);
            prep.execute();
        }
        prep.close();

        prep = conn.prepareStatement("UPDATE p set rawbyte=? WHERE id=?");
        for (int i = 0; i < 8; i++) {
            prep.setBinaryStream(1, getRandomStream(10000, i), 0);
            prep.setInt(2, i);
            prep.execute();
        }
        prep.close();
        conn.commit();

        conn = reconnect(conn);

        conn.setAutoCommit(true);
        prep = conn.prepareStatement("UPDATE p set rawbyte=? WHERE id=?");
        for (int i = 8; i < 10; i++) {
            prep.setBinaryStream(1, getRandomStream(10000, i), 0);
            prep.setInt(2, i);
            prep.execute();
        }
        prep.close();

        prep = conn.prepareStatement("SELECT * from p");
        ResultSet rs = prep.executeQuery();
        while (rs.next()) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                rs.getMetaData().getColumnName(i);
                rs.getString(i);
            }
        }
        conn.close();
    }

    private void testLobReconnect() throws Exception {
        deleteDb("lob");
        Connection conn = reconnect(null);
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, TEXT CLOB)");
        PreparedStatement prep;
        prep = conn.prepareStatement("INSERT INTO TEST VALUES(1, ?)");
        String s = new String(getRandomChars(10000, 1));
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        // if we keep the string, debugging with Eclipse is not possible
        // because Eclipse wants to display the large string and fails
        s = "";
        prep.setBinaryStream(1, new ByteArrayInputStream(data), 0);
        prep.execute();

        conn = reconnect(conn);
        stat = conn.createStatement();
        ResultSet rs = stat.executeQuery("SELECT * FROM TEST WHERE ID=1");
        rs.next();
        InputStream in = new ByteArrayInputStream(data);
        assertEqualStreams(in, rs.getBinaryStream("TEXT"), -1);

        prep = conn.prepareStatement("UPDATE TEST SET TEXT = ?");
        prep.setBinaryStream(1, new ByteArrayInputStream(data), 0);
        prep.execute();

        conn = reconnect(conn);
        stat = conn.createStatement();
        rs = stat.executeQuery("SELECT * FROM TEST WHERE ID=1");
        rs.next();
        assertEqualStreams(rs.getBinaryStream("TEXT"),
                new ByteArrayInputStream(data), -1);

        stat.execute("DROP TABLE IF EXISTS TEST");
        conn.close();
    }

    private void testLob(boolean clob) throws Exception {
        deleteDb("lob");
        Connection conn = reconnect(null);
        conn = reconnect(conn);
        Statement stat = conn.createStatement();
        stat.execute("DROP TABLE IF EXISTS TEST");
        PreparedStatement prep;
        ResultSet rs;
        long time;
        stat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, V " +
                (clob ? "CLOB" : "BLOB") + ")");

        int len = getSize(1, 1000);
        if (config.networked && config.big) {
            len = 100;
        }

        time = System.nanoTime();
        prep = conn.prepareStatement("INSERT INTO TEST VALUES(?, ?)");
        for (int i = 0; i < len; i += i + i + 1) {
            prep.setInt(1, i);
            int size = i * i;
            if (clob) {
                prep.setCharacterStream(2, getRandomReader(size, i), 0);
            } else {
                prep.setBinaryStream(2, getRandomStream(size, i), 0);
            }
            prep.execute();
        }
        trace("insert=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - time));
        traceMemory();
        conn = reconnect(conn);

        time = System.nanoTime();
        prep = conn.prepareStatement("SELECT ID, V FROM TEST");
        rs = prep.executeQuery();
        while (rs.next()) {
            int id = rs.getInt("ID");
            int size = id * id;
            if (clob) {
                Reader rt = rs.getCharacterStream(2);
                assertEqualReaders(getRandomReader(size, id), rt, -1);
                Object obj = rs.getObject(2);
                if (obj instanceof Clob) {
                    obj = ((Clob) obj).getCharacterStream();
                }
                assertEqualReaders(getRandomReader(size, id),
                        (Reader) obj, -1);
            } else {
                InputStream in = rs.getBinaryStream(2);
                assertEqualStreams(getRandomStream(size, id), in, -1);
                Object obj = rs.getObject(2);
                if (obj instanceof Blob) {
                    obj = ((Blob) obj).getBinaryStream();
                }
                assertEqualStreams(getRandomStream(size, id),
                        (InputStream) obj, -1);
            }
        }
        trace("select=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - time));
        traceMemory();

        conn = reconnect(conn);

        time = System.nanoTime();
        prep = conn.prepareStatement("DELETE FROM TEST WHERE ID=?");
        for (int i = 0; i < len; i++) {
            prep.setInt(1, i);
            prep.executeUpdate();
        }
        trace("delete=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - time));
        traceMemory();
        conn = reconnect(conn);

        conn.setAutoCommit(false);
        prep = conn.prepareStatement("INSERT INTO TEST VALUES(1, ?)");
        if (clob) {
            prep.setCharacterStream(1, getRandomReader(0, 0), 0);
        } else {
            prep.setBinaryStream(1, getRandomStream(0, 0), 0);
        }
        prep.execute();
        conn.rollback();
        prep.execute();
        conn.commit();

        conn.createStatement().execute("DELETE FROM TEST WHERE ID=1");
        conn.rollback();
        conn.createStatement().execute("DELETE FROM TEST WHERE ID=1");
        conn.commit();

        conn.createStatement().execute("DROP TABLE TEST");
        conn.close();
    }

    private void testJavaObject() throws SQLException {
        deleteDb("lob");
        JdbcConnection conn = (JdbcConnection) getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, DATA OTHER)");
        PreparedStatement prep = conn.prepareStatement(
                    "INSERT INTO TEST VALUES(1, ?)");
        prep.setObject(1, new TestLobObject("abc"));
        prep.execute();
        ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM TEST");
        rs.next();
        Object oa = rs.getObject(2);
        assertEquals(TestLobObject.class.getName(), oa.getClass().getName());
        Object ob = rs.getObject("DATA");
        assertEquals(TestLobObject.class.getName(), ob.getClass().getName());
        assertEquals("TestLobObject: abc", oa.toString());
        assertEquals("TestLobObject: abc", ob.toString());
        assertFalse(rs.next());

        conn.createStatement().execute("drop table test");
        stat.execute("create table test(v other)");
        prep = conn.prepareStatement("insert into test values(?)");
        prep.setObject(1, JdbcUtils.serialize("", conn.getJavaObjectSerializer()));
        prep.execute();
        rs = stat.executeQuery("select v from test");
        while (rs.next()) {
            assertEquals("", (String) rs.getObject("v"));
        }
        conn.close();
    }

    /**
     * Test a bug where the usage of BufferedInputStream in LobStorageMap was
     * causing a deadlock.
     */
    private void testBufferedInputStreamBug() throws SQLException {
        deleteDb("lob");
        JdbcConnection conn = (JdbcConnection) getConnection("lob");
        conn.createStatement().execute("CREATE TABLE TEST(test BLOB)");
        PreparedStatement ps = conn.prepareStatement("INSERT INTO TEST(test) VALUES(?)");
        ps.setBlob(1, new ByteArrayInputStream(new byte[257]));
        ps.executeUpdate();
        conn.close();
    }

    private static Reader getRandomReader(int len, int seed) {
        return new CharArrayReader(getRandomChars(len, seed));
    }

    private static char[] getRandomChars(int len, int seed) {
        Random random = new Random(seed);
        char[] buff = new char[len];
        for (int i = 0; i < len; i++) {
            char ch;
            do {
                ch = (char) random.nextInt(Character.MAX_VALUE);
                // UTF8: String.getBytes("UTF-8") only returns 1 byte for
                // 0xd800-0xdfff
            } while (ch >= 0xd800 && ch <= 0xdfff);
            buff[i] = ch;
        }
        return buff;
    }

    private static InputStream getRandomStream(int len, int seed) {
        Random random = new Random(seed);
        byte[] buff = new byte[len];
        random.nextBytes(buff);
        return new ByteArrayInputStream(buff);
    }

    /**
     * Test the combination of updating a table which contains an LOB, and
     * reading from the LOB at the same time
     */
    private void testUpdatingLobRow() throws Exception {
        if (config.memory) {
            return;
        }
        deleteDb("lob");
        Connection conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int primary key, " +
                "name clob, counter int)");
        stat.execute("insert into test(id, name) select x, " +
                "space(100000) from system_range(1, 3)");

        ResultSet rs = stat.executeQuery("select name " +
                "from test where id = 1");
        rs.next();
        Reader r = rs.getClob("name").getCharacterStream();
        Random random = new Random();
        char[] tmp = new char[256];
        while (r.read(tmp) > 0) {
            stat.execute("update test set counter = " +
                    random.nextInt(1000) + " where id = 1");
        }
        r.close();
        conn.close();
    }

    private void testCommitOnExclusiveConnection() throws Exception {
        deleteDb("lob");
        Connection conn = getConnection("lob;EXCLUSIVE=1");
        Statement statement = conn.createStatement();
        statement.execute("drop table if exists TEST");
        statement.execute("create table TEST (COL INTEGER, LOB CLOB)");
        conn.setAutoCommit(false);
        statement.execute("insert into TEST (COL, LOB) values (1, '" +
                MORE_THAN_128_CHARS + "')");
        statement.execute("update TEST set COL=2");
        // OK
        // statement.execute("commit");
        // KO : should not hang
        conn.commit();
        conn.close();
    }

    private void testClobWithRandomUnicodeChars() throws Exception {
        // This tests an issue we had with storing unicode surrogate pairs,
        // which only manifested at the boundaries between blocks i.e. at 4k
        // boundaries
        deleteDb("lob");
        Connection conn = getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE logs" +
                "(id int primary key auto_increment, message CLOB)");
        PreparedStatement s1 = conn.prepareStatement(
                "INSERT INTO logs (message) VALUES ?");
        final Random rand = new Random(1);
        for (int i = 1; i <= 100; i++) {
            String data = randomUnicodeString(rand);
            s1.setString(1, data);
            s1.executeUpdate();
            ResultSet rs = stat.executeQuery("SELECT id, message " +
                    "FROM logs ORDER BY id DESC LIMIT 1");
            rs.next();
            String read = rs.getString(2);
            if (!read.equals(data)) {
                for (int j = 0; j < read.length(); j++) {
                    assertEquals("pos: " + j + " i:" + i, read.charAt(j), data.charAt(j));
                }
            }
            assertEquals(read, data);
        }
        conn.close();
    }

    private static String randomUnicodeString(Random rand) {
        int count = 10000;
        final char[] buffer = new char[count];
        while (count-- != 0) {
            char ch = (char) rand.nextInt();
            if (ch >= 56320 && ch <= 57343) {
                if (count == 0) {
                    count++;
                } else {
                    // low surrogate, insert high surrogate after putting it
                    // in
                    buffer[count] = ch;
                    count--;
                    buffer[count] = (char) (55296 + rand.nextInt(128));
                }
            } else if (ch >= 55296 && ch <= 56191) {
                if (count == 0) {
                    count++;
                } else {
                    // high surrogate, insert low surrogate before putting
                    // it in
                    buffer[count] = (char) (56320 + rand.nextInt(128));
                    count--;
                    buffer[count] = ch;
                }
            } else if (ch >= 56192 && ch <= 56319) {
                // private high surrogate: no clue, so skip it
                count++;
            } else {
                buffer[count] = ch;
            }
        }
        return new String(buffer);
    }

    private void testLobInValueResultSet() throws SQLException {
        deleteDb("lob");
        JdbcConnection conn = (JdbcConnection) getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("CREATE ALIAS VRS FOR '" + getClass().getName() + ".testLobInValueResultSetGet'");
        ResultSet rs = stat.executeQuery("SELECT * FROM VRS()");
        assertTrue(rs.next());
        Clob clob = rs.getClob(1);
        assertFalse(rs.next());
        assertEquals(MORE_THAN_128_CHARS, clob.getSubString(1, Integer.MAX_VALUE));
        conn.close();
    }

    /**
     * This method is called via reflection from the database.
     *
     * @param conn connection
     * @return the result set
     * @throws SQLException on exception
     */
    public static SimpleResultSet testLobInValueResultSetGet(Connection conn) throws SQLException {
        final Clob c = conn.createClob();
        c.setString(1, MORE_THAN_128_CHARS);
        SimpleResultSet rs = new SimpleResultSet() {
            @Override
            public Object getObject(int columnIndex) throws SQLException {
                return c;
            }
        };
        rs.addColumn("L", Types.CLOB, 1000, 0);
        rs.addRow(MORE_THAN_128_CHARS);
        return rs;
    }

    private void testLimits() throws Exception {
        deleteDb("lob");
        JdbcConnection conn = (JdbcConnection) getConnection("lob");
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE TEST(ID INTEGER, B BLOB, C CLOB)");
        PreparedStatement ps = conn.prepareStatement("INSERT INTO TEST VALUES (?, ?, ?)");
        ps.setInt(1, 1);
        byte[] b = new byte[Constants.MAX_STRING_LENGTH];
        Arrays.fill(b, (byte) 'A');
        String s = new String(b, StandardCharsets.UTF_8);
        ps.setBytes(2, b);
        ps.setString(3, s);
        ps.executeUpdate();
        byte[] b2 = new byte[Constants.MAX_STRING_LENGTH + 1];
        Arrays.fill(b2, (byte) 'A');
        String s2 = new String(b2, StandardCharsets.UTF_8);
        assertThrows(ErrorCode.VALUE_TOO_LONG_2, ps).setBytes(2, b2);
        ps.setBinaryStream(2, new ByteArrayInputStream(b2));
        assertThrows(ErrorCode.VALUE_TOO_LONG_2, ps).setString(3, s2);
        ps.setCharacterStream(3, new StringReader(s2));
        ps.executeUpdate();
        try (ResultSet rs = stat.executeQuery("TABLE TEST ORDER BY ID")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            testLimitsSmall(b, s, rs, 2);
            testLimitsSmall(b, s, rs, 2);
            testLimitsSmall(b, s, rs, 3);
            testLimitsSmall(b, s, rs, 3);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            testLimitsLarge(b2, s2, rs, 2);
            testLimitsLarge(b2, s2, rs, 2);
            testLimitsLarge(b2, s2, rs, 3);
            testLimitsLarge(b2, s2, rs, 3);
            assertFalse(rs.next());
        }
        conn.close();
        testLimitsSmall(b, s, ValueBlob.createSmall(b));
        testLimitsSmall(b, s, ValueClob.createSmall(b, Constants.MAX_STRING_LENGTH));
        testLimitsLarge(b2, s2, ValueBlob.createSmall(b2));
        testLimitsLarge(b2, s2, ValueClob.createSmall(b2, Constants.MAX_STRING_LENGTH + 1));
    }

    private void testLimitsSmall(byte[] b, String s, ResultSet rs, int index) throws SQLException {
        assertEquals(b, rs.getBytes(index));
        assertEquals(s, rs.getString(index));
    }

    private void testLimitsLarge(byte[] b, String s, ResultSet rs, int index) throws SQLException, IOException {
        assertThrows(ErrorCode.VALUE_TOO_LONG_2, rs).getBytes(index);
        assertEquals(b, IOUtils.readBytesAndClose(rs.getBlob(index).getBinaryStream(), -1));
        assertThrows(ErrorCode.VALUE_TOO_LONG_2, rs).getString(index);
        assertEquals(s, IOUtils.readStringAndClose(rs.getClob(index).getCharacterStream(), -1));
    }

    private void testLimitsSmall(byte[] b, String s, ValueLob v) {
        assertEquals(b, v.getBytesNoCopy());
        assertEquals(s, v.getString());
        assertEquals(s, v.getString());
    }

    private void testLimitsLarge(byte[] b, String s, ValueLob v) throws IOException {
        try {
            assertEquals(b, v.getBytesNoCopy());
            throw new AssertionError();
        } catch (DbException e) {
            assertEquals(ErrorCode.VALUE_TOO_LONG_2, e.getErrorCode());
        }
        assertEquals(b, IOUtils.readBytesAndClose(v.getInputStream(), -1));
        for (int i = 0; i < 2; i++) {
            try {
                assertEquals(s, v.getString());
                throw new AssertionError();
            } catch (DbException e) {
                assertEquals(ErrorCode.VALUE_TOO_LONG_2, e.getErrorCode());
            }
            assertEquals(s, IOUtils.readStringAndClose(v.getReader(), -1));
        }
    }

    public void testConcurrentSelectAndUpdate() throws SQLException, InterruptedException {
        deleteDb("lob");
        try (JdbcConnection conn1 = (JdbcConnection) getConnection("lob")) {
            try (JdbcConnection conn2 = (JdbcConnection) getConnection("lob")) {

                try (Statement st = conn1.createStatement()) {
                    String createTable = "create table t1 (id int, ver bigint, data text, primary key (id));";
                    st.execute(createTable);
                }

                String insert = "insert into t1 (id, ver, data) values (1, 0, ?)";
                try (PreparedStatement insertStmt = conn1.prepareStatement(insert)) {
                    String largeData = org.h2.util.StringUtils.pad("", 512, "x", false);
                    insertStmt.setString(1, largeData);
                    insertStmt.executeUpdate();
                }

                long startTimeNs = System.nanoTime();

                Thread thread1 = new Thread(() -> {
                    try {
                        String update = "update t1 set ver = ver + 1 where id = 1";
                        try (PreparedStatement ps = conn2.prepareStatement(update)) {
                            while (!Thread.currentThread().isInterrupted()
                                    && System.nanoTime() - startTimeNs < 10_000_000_000L) {
                                ps.executeUpdate();
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                thread1.start();

                try (PreparedStatement st = conn1.prepareStatement("select * from t1 where id = 1")) {
                    while (System.nanoTime() - startTimeNs  < 10_000_000_000L) {
                        st.executeQuery();
                    }
                }
                thread1.join();
            }
        }
    }
}
