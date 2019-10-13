/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2019 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

public class DropTableTest extends AbstractGriffinTest {

    @Test
    public void testDropExisting() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            CompiledQuery cc = compiler.compile("create table instrument (a int)");
            Assert.assertEquals(CompiledQuery.CREATE_TABLE, cc.getType());

            cc = compiler.compile("drop table instrument");
            Assert.assertEquals(CompiledQuery.DROP, cc.getType());

        });
    }

    @Test
    public void testDropUtf8() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            CompiledQuery cc = compiler.compile("create table научный (a int)");
            Assert.assertEquals(CompiledQuery.CREATE_TABLE, cc.getType());

            cc = compiler.compile("drop table научный");
            Assert.assertEquals(CompiledQuery.DROP, cc.getType());
        });
    }

    @Test
    public void testDropUtf8Quoted() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            CompiledQuery cc = compiler.compile("create table 'научный руководитель'(a int)");
            Assert.assertEquals(CompiledQuery.CREATE_TABLE, cc.getType());

            cc = compiler.compile("drop table 'научный руководитель'");
            Assert.assertEquals(CompiledQuery.DROP, cc.getType());
        });
    }

    @Test
    public void testDropQuoted() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            CompiledQuery cc = compiler.compile("create table 'large table' (a int)");
            Assert.assertEquals(CompiledQuery.CREATE_TABLE, cc.getType());

            cc = compiler.compile("drop table 'large table'");
            Assert.assertEquals(CompiledQuery.DROP, cc.getType());
        });
    }

    @Test
    public void testDropMissingFrom() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try {
                compiler.compile("drop i_am_missing");
            } catch (SqlException e) {
                Assert.assertEquals(5, e.getPosition());
                TestUtils.assertContains("'table' expected", e.getFlyweightMessage());
            }
        });
    }
}