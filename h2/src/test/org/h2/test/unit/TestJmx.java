/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Set;
import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.h2.engine.Constants;
import org.h2.test.TestBase;
import org.h2.test.TestDb;

/**
 * Tests the JMX feature.
 */
public class TestJmx extends TestDb {

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase base = TestBase.createCaller().init();
        base.testFromMain();
    }

    @Override
    public void test() throws Exception {
        HashMap<String, MBeanAttributeInfo> attrMap;
        HashMap<String, MBeanOperationInfo> opMap;
        String result;
        MBeanInfo info;
        ObjectName name;
        Connection conn;
        Statement stat;

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

        conn = getConnection("mem:jmx;jmx=true");
        stat = conn.createStatement();

        name = new ObjectName("org.h2:name=JMX,path=mem_jmx");
        info = mbeanServer.getMBeanInfo(name);
        assertEquals("0", mbeanServer.
                getAttribute(name, "CacheSizeMax").toString());
        // cache size is ignored for in-memory databases
        mbeanServer.setAttribute(name, new Attribute("CacheSizeMax", 1));
        assertEquals("0", mbeanServer.
                getAttribute(name, "CacheSizeMax").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "CacheSize").toString());
        assertEquals("false", mbeanServer.
                getAttribute(name, "Exclusive").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "FileSize").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "FileReadCount").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "FileWriteCount").toString());
        assertEquals("REGULAR", mbeanServer.
                getAttribute(name, "Mode").toString());
        assertEquals("false", mbeanServer.
                getAttribute(name, "ReadOnly").toString());
        assertEquals("1", mbeanServer.
                getAttribute(name, "TraceLevel").toString());
        mbeanServer.setAttribute(name, new Attribute("TraceLevel", 0));
        assertEquals("0", mbeanServer.
                getAttribute(name, "TraceLevel").toString());
        assertEquals(Constants.FULL_VERSION, mbeanServer.getAttribute(name, "Version").toString());
        assertEquals(10, info.getAttributes().length);
        result = mbeanServer.invoke(name, "listSettings", null, null).toString();
        assertContains(result, "ANALYZE_AUTO");

        conn.setAutoCommit(false);
        stat.execute("create table test(id int)");
        stat.execute("insert into test values(1)");

        result = mbeanServer.invoke(name, "listSessions", null, null).toString();
        assertContains(result, "session id");
        assertContains(result, "read lock");

        assertEquals(2, info.getOperations().length);
        assertContains(info.getDescription(), "database");
        attrMap = new HashMap<>();
        for (MBeanAttributeInfo a : info.getAttributes()) {
            attrMap.put(a.getName(), a);
        }
        assertContains(attrMap.get("CacheSize").getDescription(), "KB");
        opMap = new HashMap<>();
        for (MBeanOperationInfo o : info.getOperations()) {
            opMap.put(o.getName(), o);
        }
        assertContains(opMap.get("listSessions").getDescription(), "lock");
        assertEquals(MBeanOperationInfo.INFO, opMap.get("listSessions").getImpact());

        conn.close();

        conn = getConnection("jmx;jmx=true");
        conn.close();
        conn = getConnection("jmx;jmx=true");

        name = new ObjectName("org.h2:name=JMX,*");
        @SuppressWarnings("rawtypes")
        Set set = mbeanServer.queryNames(name, null);
        name = (ObjectName) set.iterator().next();

        if (config.memory) {
            assertEquals("0", mbeanServer.
                    getAttribute(name, "CacheSizeMax").toString());
        } else {
            assertEquals("16384", mbeanServer.
                    getAttribute(name, "CacheSizeMax").toString());
        }
        mbeanServer.setAttribute(name, new Attribute("CacheSizeMax", 1));
        if (config.memory) {
            assertEquals("0", mbeanServer.
                    getAttribute(name, "CacheSizeMax").toString());
        } else {
            assertEquals("1024", mbeanServer.
                    getAttribute(name, "CacheSizeMax").toString());
            assertEquals("0", mbeanServer.
                    getAttribute(name, "CacheSize").toString());
            assertTrue(0 < (Long) mbeanServer.
                    getAttribute(name, "FileReadCount"));
            // FileWriteCount can be not yet updated and may return 0
            assertTrue(0 <= (Long) mbeanServer.getAttribute(name, "FileWriteCount"));
        }

        conn.close();

    }

}
